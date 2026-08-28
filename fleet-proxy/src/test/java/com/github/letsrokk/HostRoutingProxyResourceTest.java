package com.github.letsrokk;

import com.github.letsrokk.exceptions.MockIdNotFound;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@QuarkusTest
@TestProfile(HostRoutingProfile.class)
class HostRoutingProxyResourceTest {

    private static Vertx upstreamVertx;
    private static HttpServer upstreamServer;
    private static String upstreamBaseUrl;
    private static final AtomicReference<UpstreamResponse> nextResponse =
            new AtomicReference<>(new UpstreamResponse(200, "ok", Map.of()));
    private static final AtomicReference<CapturedRequest> capturedRequest = new AtomicReference<>();

    @InjectMock
    FleetApiClient fleetApiClient;

    @BeforeAll
    static void startUpstream() {
        upstreamVertx = Vertx.vertx();
        upstreamServer = upstreamVertx.createHttpServer()
                .requestHandler(request -> request.bodyHandler(body -> {
                    capturedRequest.set(new CapturedRequest(
                            request.method().name(),
                            request.uri(),
                            request.headers(),
                            body.getBytes()));

                    UpstreamResponse response = nextResponse.get();
                    response.headers().forEach((name, values) -> values.forEach(value -> request.response().headers().add(name, value)));
                    request.response()
                            .setStatusCode(response.statusCode())
                            .end(response.body());
                }));
        upstreamServer.listen(0, "127.0.0.1").toCompletionStage().toCompletableFuture().join();
        upstreamBaseUrl = "http://127.0.0.1:" + upstreamServer.actualPort();
    }

    @AfterAll
    static void stopUpstream() {
        if (upstreamServer != null) {
            upstreamServer.close().toCompletionStage().toCompletableFuture().join();
        }
        if (upstreamVertx != null) {
            upstreamVertx.close().toCompletionStage().toCompletableFuture().join();
        }
    }

    @BeforeEach
    void setUp() {
        nextResponse.set(new UpstreamResponse(200, "ok", Map.of()));
        capturedRequest.set(null);
    }

    @Test
    void proxiesNestedGetRequestsWithPathAndQueryParameters() {
        mockUpstream("demo");

        given()
                .header("Host", "demo.mock-fleet.localhost")
                .queryParam("alpha", "1")
                .queryParam("beta", "two")
        .when()
                .get("/nested/path")
        .then()
                .statusCode(200)
                .body(is("ok"));

        CapturedRequest request = capturedRequest.get();
        assertNotNull(request);
        assertEquals("GET", request.method());
        assertEquals("/nested/path?alpha=1&beta=two", request.uri());
    }

    @Test
    void rejectsEncodedBackslashTargetsBeforeResolvingOrContactingTheUpstream() {
        mockUpstream("demo");

        given()
                .urlEncodingEnabled(false)
                .header("Host", "demo.mock-fleet.localhost")
        .when()
                .get("/%5cattacker")
        .then()
                .statusCode(400)
                .body(containsString("origin-form"));

        verifyNoInteractions(fleetApiClient);
        assertEquals(null, capturedRequest.get());
    }

    @Test
    void returnsControlledBadRequestForInvalidHostHeader() {
        given()
                .header("Host", "!!!:8080")
        .when()
                .get("/anything")
        .then()
                .statusCode(400)
                .body(containsString("Unable to extract mock id"));
    }

    @Test
    void rejectsSingleLabelHostWithoutSpawningMock() {
        given()
                .header("Host", "localhost")
        .when()
                .get("/anything")
        .then()
                .statusCode(400)
                .body(containsString("Unable to extract mock id"));

        verifyNoInteractions(fleetApiClient);
    }

    @Test
    void keepsFleetApiRequestsLocalInsteadOfProxyingFromFleetHost() {
        given()
                .header("Host", "mock-fleet.localhost")
        .when()
                .get("/__fleet/api/mocks")
        .then()
                .statusCode(404);

        verifyNoInteractions(fleetApiClient);
        assertEquals(null, capturedRequest.get());
    }

    @Test
    void proxiesFleetApiPathsForMockHosts() {
        mockUpstream("demo");

        given()
                .header("Host", "demo.mock-fleet.localhost")
        .when()
                .get("/__fleet/api/mocks")
        .then()
                .statusCode(200)
                .body(is("ok"));

        CapturedRequest request = capturedRequest.get();
        assertNotNull(request);
        assertEquals("/__fleet/api/mocks", request.uri());
    }

    @Test
    void keepsHealthRequestsLocalForInternalHosts() {
        given()
                .header("Host", "10.42.0.17:8080")
        .when()
                .get("/__fleet/proxy/health/started")
        .then()
                .statusCode(200)
                .body(containsString("\"status\""))
                .body(containsString("\"UP\""));

        verifyNoInteractions(fleetApiClient);
        assertEquals(null, capturedRequest.get());
    }

    @Test
    void keepsFleetHostRequestsLocalEvenOutsideReservedPaths() {
        given()
                .header("Host", "mock-fleet.localhost")
        .when()
                .get("/anything")
        .then()
                .statusCode(404);

        verifyNoInteractions(fleetApiClient);
        assertEquals(null, capturedRequest.get());
    }

    @Test
    void redirectsFleetHostRootToDashboard() {
        given()
                .redirects().follow(false)
                .header("Host", "mock-fleet.localhost")
        .when()
                .get("/")
        .then()
                .statusCode(302)
                .header("Location", "/__fleet/")
                .body(is(""));

        verifyNoInteractions(fleetApiClient);
        assertEquals(null, capturedRequest.get());
    }

    @Test
    void redirectsFleetHostRootOnHeadRequests() {
        given()
                .redirects().follow(false)
                .header("Host", "mock-fleet.localhost")
        .when()
                .head("/")
        .then()
                .statusCode(302)
                .header("Location", "/__fleet/");

        verifyNoInteractions(fleetApiClient);
        assertEquals(null, capturedRequest.get());
    }

    @Test
    void redirectsFleetDashboardEntryToCanonicalPath() {
        given()
                .redirects().follow(false)
                .header("Host", "mock-fleet.localhost")
        .when()
                .get("/__fleet")
        .then()
                .statusCode(302)
                .header("Location", "/__fleet/")
                .body(is(""));

        verifyNoInteractions(fleetApiClient);
        assertEquals(null, capturedRequest.get());
    }

    @Test
    void proxiesFleetDashboardEntryOnMockHosts() {
        mockUpstream("demo");

        given()
                .header("Host", "demo.mock-fleet.localhost")
        .when()
                .get("/__fleet")
        .then()
                .statusCode(200)
                .body(is("ok"));

        CapturedRequest request = capturedRequest.get();
        assertNotNull(request);
        assertEquals("/__fleet", request.uri());
    }

    @Test
    void redirectsFleetDashboardEntryOnHeadRequests() {
        given()
                .redirects().follow(false)
                .header("Host", "mock-fleet.localhost")
        .when()
                .head("/__fleet")
        .then()
                .statusCode(302)
                .header("Location", "/__fleet/");

        verifyNoInteractions(fleetApiClient);
        assertEquals(null, capturedRequest.get());
    }

    @Test
    void doesNotRedirectFleetDashboardEntryOnPostRequests() {
        given()
                .header("Host", "mock-fleet.localhost")
                .body("payload")
        .when()
                .post("/__fleet")
        .then()
                .statusCode(405)
                .header("Allow", "GET, HEAD");

        verifyNoInteractions(fleetApiClient);
        assertEquals(null, capturedRequest.get());
    }

    @Test
    void doesNotRedirectFleetHostRootOnPostRequests() {
        given()
                .header("Host", "mock-fleet.localhost")
                .body("payload")
        .when()
                .post("/")
        .then()
                .statusCode(405)
                .header("Allow", "GET, HEAD");

        verifyNoInteractions(fleetApiClient);
        assertEquals(null, capturedRequest.get());
    }

    @Test
    void stillProxiesMockHostRootRequests() {
        mockUpstream("demo");

        given()
                .header("Host", "demo.mock-fleet.localhost")
        .when()
                .get("/")
        .then()
                .statusCode(200)
                .header("Location", nullValue())
                .body(is("ok"));

        CapturedRequest request = capturedRequest.get();
        assertNotNull(request);
        assertEquals("/", request.uri());
    }

    @Test
    void proxiesFleetApiHealthRequestsForMockHosts() {
        mockUpstream("demo");

        given()
                .header("Host", "demo.mock-fleet.localhost")
        .when()
                .get("/__fleet/api/health/started")
        .then()
                .statusCode(200)
                .body(is("ok"));

        CapturedRequest request = capturedRequest.get();
        assertNotNull(request);
        assertEquals("/__fleet/api/health/started", request.uri());
    }

    @Test
    void proxiesFaviconRequestsForMockHosts() {
        mockUpstream("favicon");

        given()
                .header("Host", "favicon.mock-fleet.localhost")
        .when()
                .get("/favicon.ico")
        .then()
                .statusCode(200)
                .body(is("ok"));

        CapturedRequest request = capturedRequest.get();
        assertNotNull(request);
        assertEquals("/favicon.ico", request.uri());
    }

    @Test
    void forwardsUpstreamClientErrorsWithoutMaskingThem() {
        mockUpstream("demo");
        nextResponse.set(new UpstreamResponse(404, "missing", Map.of("X-Upstream", List.of("true"))));

        given()
                .header("Host", "demo.mock-fleet.localhost")
        .when()
                .get("/missing")
        .then()
                .statusCode(404)
                .header("X-Upstream", "true")
                .body(is("missing"));
    }

    @Test
    void forwardsRequestHeadersAndBody() {
        mockUpstream("demo");
        nextResponse.set(new UpstreamResponse(201, "created", Map.of()));

        given()
                .header("Host", "demo.mock-fleet.localhost")
                .header("X-Test", "value")
                .body("payload")
        .when()
                .post("/headers/check?mode=full")
        .then()
                .statusCode(201)
                .body(is("created"));

        CapturedRequest request = capturedRequest.get();
        assertNotNull(request);
        assertEquals("POST", request.method());
        assertEquals("/headers/check?mode=full", request.uri());
        assertEquals("value", request.headers().get("X-Test"));
        assertArrayEquals("payload".getBytes(StandardCharsets.UTF_8), request.body());
    }

    @Test
    void replacesAuthorityAndFramingHeadersAndRemovesInboundHopByHopHeaders() {
        mockUpstream("demo");

        given()
                .header("Host", "demo.mock-fleet.localhost")
                .header("Connection", "X-Remove")
                .header("X-Remove", "secret")
                .header("Proxy-Connection", "close")
                .header("Keep-Alive", "timeout=5")
                .header("TE", "trailers")
                .header("Trailer", "X-Trailer")
                .header("Proxy-Authorization", "Basic secret")
                .body("payload")
        .when()
                .post("/hop-by-hop-headers")
        .then()
                .statusCode(200);

        MultiMap headers = capturedRequest.get().headers();
        assertEquals("127.0.0.1:" + upstreamServer.actualPort(), headers.get("Host"));
        assertEquals(null, headers.get("X-Remove"));
        assertEquals(null, headers.get("Proxy-Connection"));
        assertEquals(null, headers.get("Keep-Alive"));
        assertEquals(null, headers.get("Transfer-Encoding"));
        assertEquals(null, headers.get("TE"));
        assertEquals(null, headers.get("Trailer"));
        assertEquals(null, headers.get("Upgrade"));
        assertEquals(null, headers.get("Proxy-Authorization"));
    }

    @Test
    void headerCopyRejectsEveryConfiguredAndConnectionNominatedHopByHopHeader() {
        MultiMap inbound = MultiMap.caseInsensitiveMultiMap()
                .add("Host", "attacker.example")
                .add("Connection", "X-Remove")
                .add("X-Remove", "secret")
                .add("Proxy-Connection", "close")
                .add("Keep-Alive", "timeout=5")
                .add("Content-Length", "999")
                .add("Transfer-Encoding", "chunked")
                .add("TE", "trailers")
                .add("Trailer", "X-Trailer")
                .add("Upgrade", "h2c")
                .add("Proxy-Authorization", "Basic secret")
                .add("X-Repeat", "one")
                .add("X-Repeat", "two");
        MultiMap outbound = MultiMap.caseInsensitiveMultiMap();

        ProxyForwarder.copyForwardableRequestHeaders(inbound, outbound);

        assertEquals(null, outbound.get("Host"));
        assertEquals(null, outbound.get("Connection"));
        assertEquals(null, outbound.get("X-Remove"));
        assertEquals(null, outbound.get("Proxy-Connection"));
        assertEquals(null, outbound.get("Keep-Alive"));
        assertEquals(null, outbound.get("Content-Length"));
        assertEquals(null, outbound.get("Transfer-Encoding"));
        assertEquals(null, outbound.get("TE"));
        assertEquals(null, outbound.get("Trailer"));
        assertEquals(null, outbound.get("Upgrade"));
        assertEquals(null, outbound.get("Proxy-Authorization"));
        assertEquals(List.of("one", "two"), outbound.getAll("X-Repeat"));
    }

    @Test
    void directlyProxiesUnauthenticatedAdminRequestsAndResolvesTheMock() {
        mockUpstream("demo");
        nextResponse.set(new UpstreamResponse(200, "{\"mappings\":[]}",
                Map.of("Content-Type", List.of("application/json"))));

        given()
                .header("Host", "demo.mock-fleet.localhost")
        .when()
                .get("/__admin/mappings")
        .then()
                .statusCode(200)
                .body(is("{\"mappings\":[]}"));

        verify(fleetApiClient).resolveUpstreamBaseUrl("demo");
        assertEquals("/__admin/mappings", capturedRequest.get().uri());
    }

    @Test
    void proxiesToLocalhostUpstreamReturnedByFleetApi() {
        mockUpstream("demo");

        given()
                .header("Host", "demo.mock-fleet.localhost")
        .when()
                .get("/local-debug")
        .then()
                .statusCode(200)
                .body(is("ok"));

        CapturedRequest request = capturedRequest.get();
        assertNotNull(request);
        assertEquals("/local-debug", request.uri());
    }

    @Test
    void preservesDuplicateRequestAndResponseHeaders() {
        mockUpstream("demo");
        nextResponse.set(new UpstreamResponse(200, "ok", Map.of(
                "X-Repeat", List.of("alpha", "beta"),
                "Set-Cookie", List.of("one=1; Path=/", "two=2; Path=/"))));

        var response = given()
                .header("Host", "demo.mock-fleet.localhost")
                .header("X-Repeat", "one", "two")
        .when()
                .get("/duplicate-headers")
        .then()
                .statusCode(200)
                .extract().response();

        assertEquals(List.of("one", "two"), capturedRequest.get().headers().getAll("X-Repeat"));
        assertEquals(List.of("alpha", "beta"), response.getHeaders().getValues("X-Repeat"));
        assertEquals(List.of("one=1; Path=/", "two=2; Path=/"), response.getHeaders().getValues("Set-Cookie"));
    }

    @Test
    void forwardsMultipleOrdinaryHeaders() {
        mockUpstream("demo");

        given()
                .header("Host", "demo.mock-fleet.localhost")
                .header("X-Correlation-Id", "abc-123")
                .header("Accept", "application/json")
        .when()
                .get("/headers")
        .then()
                .statusCode(200);

        CapturedRequest request = capturedRequest.get();
        assertNotNull(request);
        assertEquals("abc-123", request.headers().get("X-Correlation-Id"));
        assertEquals("application/json", request.headers().get("Accept"));
    }

    record CapturedRequest(String method,
                           String uri,
                           MultiMap headers,
                           byte[] body) {
    }

    record UpstreamResponse(int statusCode,
                            String body,
                            Map<String, List<String>> headers) {
    }

    private void mockUpstream(String mockId) {
        when(fleetApiClient.resolveUpstreamBaseUrl(mockId)).thenReturn(Future.succeededFuture(upstreamBaseUrl));
    }
}
