package com.github.letsrokk;

import com.github.letsrokk.exceptions.MockIdNotFound;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
    PodManager podManager;

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
                    response.headers().forEach((name, values) -> values.forEach(value -> request.response().putHeader(name, value)));
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
        when(podManager.getUpstreamBaseUrl("demo"))
                .thenReturn(upstreamBaseUrl);

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

        verifyNoInteractions(podManager);
    }

    @Test
    void keepsFleetApiRequestsLocalInsteadOfProxying() {
        when(podManager.listActiveMocks()).thenReturn(List.of(new PodManager.ActiveMockPod("demo", "mock-fleet-demo-1")));

        given()
                .header("Host", "mock-fleet.localhost")
        .when()
                .get("/__fleet/api/mocks")
        .then()
                .statusCode(200)
                .body("[0].mockId", is("demo"))
                .body("[0].podName", is("mock-fleet-demo-1"));

        verify(podManager).listActiveMocks();
        assertEquals(null, capturedRequest.get());
    }

    @Test
    void proxiesFleetApiPathsForMockHosts() {
        when(podManager.getUpstreamBaseUrl("demo")).thenReturn(upstreamBaseUrl);

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
                .get("/__fleet/health/started")
        .then()
                .statusCode(200)
                .body(containsString("\"status\""))
                .body(containsString("\"UP\""));

        verifyNoInteractions(podManager);
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

        verifyNoInteractions(podManager);
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

        verifyNoInteractions(podManager);
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

        verifyNoInteractions(podManager);
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

        verifyNoInteractions(podManager);
        assertEquals(null, capturedRequest.get());
    }

    @Test
    void proxiesFleetDashboardEntryOnMockHosts() {
        when(podManager.getUpstreamBaseUrl("demo")).thenReturn(upstreamBaseUrl);

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

        verifyNoInteractions(podManager);
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

        verifyNoInteractions(podManager);
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

        verifyNoInteractions(podManager);
        assertEquals(null, capturedRequest.get());
    }

    @Test
    void stillProxiesMockHostRootRequests() {
        when(podManager.getUpstreamBaseUrl("demo")).thenReturn(upstreamBaseUrl);

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
    void proxiesFleetHealthRequestsForMockHosts() {
        when(podManager.getUpstreamBaseUrl("demo")).thenReturn(upstreamBaseUrl);

        given()
                .header("Host", "demo.mock-fleet.localhost")
        .when()
                .get("/__fleet/health/started")
        .then()
                .statusCode(200)
                .body(is("ok"));

        CapturedRequest request = capturedRequest.get();
        assertNotNull(request);
        assertEquals("/__fleet/health/started", request.uri());
    }

    @Test
    void proxiesFaviconRequestsForMockHosts() {
        when(podManager.getUpstreamBaseUrl("favicon")).thenReturn(upstreamBaseUrl);

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
        when(podManager.getUpstreamBaseUrl("demo"))
                .thenReturn(upstreamBaseUrl);
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
        when(podManager.getUpstreamBaseUrl("demo"))
                .thenReturn(upstreamBaseUrl);
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
    void proxiesToLocalhostUpstreamReturnedByPodManager() {
        when(podManager.getUpstreamBaseUrl("demo"))
                .thenReturn(upstreamBaseUrl);

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
    void forwardsMultipleOrdinaryHeaders() {
        when(podManager.getUpstreamBaseUrl("demo"))
                .thenReturn(upstreamBaseUrl);

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
}
