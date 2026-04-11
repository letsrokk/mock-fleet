package com.github.letsrokk;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@QuarkusTest
@TestProfile(PathRoutingProfile.class)
class PathRoutingProxyResourceTest {

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
    void stripsMockIdPrefixBeforeForwardingToUpstream() {
        when(podManager.getUpstreamBaseUrl("demo")).thenReturn(upstreamBaseUrl);

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
        assertEquals("mock-fleet.localhost", request.headers().get("Host"));
    }

    @Test
    void forwardsRootWhenPathContainsOnlyMockId() {
        when(podManager.getUpstreamBaseUrl("demo")).thenReturn(upstreamBaseUrl);

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
        when(podManager.getUpstreamBaseUrl("demo")).thenReturn(upstreamBaseUrl);
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
    void returnsBadRequestWhenPathDoesNotContainMockId() {
        given()
                .header("Host", "mock-fleet.localhost")
        .when()
                .get("/")
        .then()
                .statusCode(404);
    }

    @Test
    void keepsFleetApiRequestsLocalInPathMode() {
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
