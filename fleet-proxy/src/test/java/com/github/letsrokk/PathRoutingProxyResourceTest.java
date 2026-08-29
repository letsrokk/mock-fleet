package com.github.letsrokk;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@QuarkusTest
@TestProfile(PathRoutingProfile.class)
class PathRoutingProxyResourceTest {

    private static Vertx upstreamVertx;
    private static HttpServer upstreamServer;
    private static HttpServer alternateUpstreamServer;
    private static String upstreamBaseUrl;
    private static final AtomicReference<UpstreamResponse> nextResponse =
            new AtomicReference<>(new UpstreamResponse(200, "ok", Map.of()));
    private static final AtomicReference<CapturedRequest> capturedRequest = new AtomicReference<>();
    private static final AtomicReference<CapturedRequest> alternateCapturedRequest = new AtomicReference<>();

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

        alternateUpstreamServer = upstreamVertx.createHttpServer()
                .requestHandler(request -> request.bodyHandler(body -> {
                    alternateCapturedRequest.set(new CapturedRequest(
                            request.method().name(),
                            request.uri(),
                            request.headers(),
                            body.getBytes()));
                    request.response().end("alternate");
                }));
        alternateUpstreamServer.listen(0, "127.0.0.1").toCompletionStage().toCompletableFuture().join();
    }

    @AfterAll
    static void stopUpstream() {
        if (alternateUpstreamServer != null) {
            alternateUpstreamServer.close().toCompletionStage().toCompletableFuture().join();
        }
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
        alternateCapturedRequest.set(null);
    }

    @Test
    void stripsMockIdPrefixBeforeForwardingToUpstream() {
        mockUpstream("demo");

        given()
                .header("Host", "mock-fleet.localhost")
                .queryParam("alpha", "1")
                .queryParam("beta", "two")
        .when()
                .get("/demo/nested/path")
        .then()
                .statusCode(200)
                .body(is("ok"));

        CapturedRequest request = capturedRequest.get();
        assertNotNull(request);
        assertEquals("GET", request.method());
        assertEquals("/nested/path?alpha=1&beta=two", request.uri());
        assertEquals("127.0.0.1:" + upstreamServer.actualPort(), request.headers().get("Host"));
    }

    @Test
    void rejectsEncodedBackslashTargetsBeforeResolvingOrContactingAnyUpstream() {
        mockUpstream("demo");

        given()
                .urlEncodingEnabled(false)
                .header("Host", "mock-fleet.localhost")
        .when()
                .get("/demo/%5c%5c127.0.0.1:" + alternateUpstreamServer.actualPort() + "/escape")
        .then()
                .statusCode(400)
                .body(containsString("origin-form"));

        verifyNoInteractions(fleetApiClient);
        assertEquals(null, capturedRequest.get());
        assertEquals(null, alternateCapturedRequest.get());
    }

    @Test
    void forwardsRootWhenPathContainsOnlyMockId() {
        mockUpstream("demo");

        given()
                .header("Host", "mock-fleet.localhost")
        .when()
                .get("/demo")
        .then()
                .statusCode(200)
                .body(is("ok"));

        CapturedRequest request = capturedRequest.get();
        assertNotNull(request);
        assertEquals("/", request.uri());
    }

    @Test
    void forwardsRequestHeadersAndBodyInPathMode() {
        mockUpstream("demo");
        nextResponse.set(new UpstreamResponse(201, "created", Map.of("X-Upstream", List.of("true"))));

        given()
                .header("Host", "mock-fleet.localhost")
                .header("X-Test", "value")
                .body("payload")
        .when()
                .post("/demo/headers/check?mode=full")
        .then()
                .statusCode(201)
                .header("X-Upstream", "true")
                .body(is("created"));

        CapturedRequest request = capturedRequest.get();
        assertNotNull(request);
        assertEquals("/headers/check?mode=full", request.uri());
        assertEquals("value", request.headers().get("X-Test"));
        assertArrayEquals("payload".getBytes(StandardCharsets.UTF_8), request.body());
    }

    @Test
    void preservesDuplicateRequestAndResponseHeaders() {
        mockUpstream("demo");
        nextResponse.set(new UpstreamResponse(200, "ok", Map.of(
                "X-Repeat", List.of("alpha", "beta"),
                "Set-Cookie", List.of("one=1; Path=/", "two=2; Path=/"))));

        var response = given()
                .header("Host", "mock-fleet.localhost")
                .header("X-Repeat", "one", "two")
        .when()
                .get("/demo/duplicate-headers")
        .then()
                .statusCode(200)
                .extract().response();

        assertEquals(List.of("one", "two"), capturedRequest.get().headers().getAll("X-Repeat"));
        assertEquals(List.of("alpha", "beta"), response.getHeaders().getValues("X-Repeat"));
        assertEquals(List.of("one=1; Path=/", "two=2; Path=/"), response.getHeaders().getValues("Set-Cookie"));
    }

    @Test
    void directlyProxiesUnauthenticatedAdminRequestsAndResolvesTheMock() {
        mockUpstream("demo");
        nextResponse.set(new UpstreamResponse(200, "{\"mappings\":[]}",
                Map.of("Content-Type", List.of("application/json"))));

        given()
                .header("Host", "mock-fleet.localhost")
        .when()
                .get("/demo/__admin/mappings")
        .then()
                .statusCode(200)
                .body(is("{\"mappings\":[]}"));

        verify(fleetApiClient).resolveUpstreamBaseUrl("demo");
        assertEquals("/__admin/mappings", capturedRequest.get().uri());
    }

    @Test
    void returnsBadRequestWhenPathDoesNotContainMockId() {
        given()
                .header("Host", "mock-fleet.localhost")
        .when()
                .get("/__")
        .then()
                .statusCode(400)
                .body(containsString("Unable to extract mock id"));
    }

    @Test
    void keepsFleetDashboardPathsLocalInPathMode() {
        given()
                .header("Host", "mock-fleet.localhost")
        .when()
                .get("/__fleet/")
        .then()
                .statusCode(404);

        assertEquals(null, capturedRequest.get());
    }

    @Test
    void keepsFleetApiRequestsLocalInPathMode() {
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
    void redirectsFleetDashboardEntryToCanonicalPathInPathMode() {
        given()
                .redirects().follow(false)
                .header("Host", "mock-fleet.localhost")
        .when()
                .get("/__fleet")
        .then()
                .statusCode(302)
                .header("Location", "/__fleet/")
                .body(is(""));

        assertEquals(null, capturedRequest.get());
    }

    @Test
    void redirectsFleetDashboardEntryOnHeadRequestsInPathMode() {
        given()
                .redirects().follow(false)
                .header("Host", "mock-fleet.localhost")
        .when()
                .head("/__fleet")
        .then()
                .statusCode(302)
                .header("Location", "/__fleet/");

        assertEquals(null, capturedRequest.get());
    }

    @Test
    void doesNotRedirectFleetDashboardEntryOnPostRequestsInPathMode() {
        given()
                .header("Host", "mock-fleet.localhost")
                .body("payload")
        .when()
                .post("/__fleet")
        .then()
                .statusCode(405)
                .header("Allow", "GET, HEAD");

        assertEquals(null, capturedRequest.get());
    }

    @Test
    void redirectsRootToDashboardInPathMode() {
        given()
                .redirects().follow(false)
                .header("Host", "mock-fleet.localhost")
        .when()
                .get("/")
        .then()
                .statusCode(302)
                .header("Location", "/__fleet/")
                .body(is(""));

        assertEquals(null, capturedRequest.get());
    }

    @Test
    void redirectsRootOnHeadRequestsInPathMode() {
        given()
                .redirects().follow(false)
                .header("Host", "mock-fleet.localhost")
        .when()
                .head("/")
        .then()
                .statusCode(302)
                .header("Location", "/__fleet/");

        assertEquals(null, capturedRequest.get());
    }

    @Test
    void doesNotRedirectRootOnPostRequestsInPathMode() {
        given()
                .header("Host", "mock-fleet.localhost")
                .body("payload")
        .when()
                .post("/")
        .then()
                .statusCode(405)
                .header("Allow", "GET, HEAD");

        assertEquals(null, capturedRequest.get());
    }

    @Test
    void keepsDashboardAssetPathsLocalInPathMode() {
        when(fleetApiClient.resolveUpstreamBaseUrl("__fleet")).thenReturn(Future.succeededFuture(upstreamBaseUrl));

        given()
                .header("Host", "mock-fleet.localhost")
        .when()
                .get("/__fleet/assets/app.js")
        .then()
                .statusCode(404);

        assertEquals(null, capturedRequest.get());
    }

    @Test
    void keepsFaviconRequestsLocalInPathMode() {
        when(fleetApiClient.resolveUpstreamBaseUrl("favicon.ico")).thenReturn(Future.succeededFuture(upstreamBaseUrl));

        given()
                .header("Host", "mock-fleet.localhost")
        .when()
                .get("/favicon.ico")
        .then()
                .statusCode(404);

        assertEquals(null, capturedRequest.get());
    }

    @Test
    void rejectsFleetSubdomainHostsInPathMode() {
        given()
                .header("Host", "demo.mock-fleet.localhost")
        .when()
                .get("/demo/anything")
        .then()
                .statusCode(400)
                .body(containsString("does not accept fleet subdomain host"));

        assertEquals(null, capturedRequest.get());
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
