package com.github.letsrokk.mcp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FleetProxyClientTest {

    private Vertx vertx;
    private HttpServer server;
    private URI baseUrl;
    private final AtomicReference<CapturedRequest> captured = new AtomicReference<>();

    @BeforeEach
    void startServer() {
        vertx = Vertx.vertx();
        server = vertx.createHttpServer().requestHandler(request -> request.bodyHandler(body -> {
            captured.set(new CapturedRequest(request.method(), request.uri(), request.getHeader("Host"),
                    request.getHeader("X-Test"), body.getBytes()));
            if (request.uri().equals("/orders/large-stream")) {
                request.response().setChunked(true).write(Buffer.buffer(new byte[2048]));
            } else if (request.uri().equals("/orders/collection-error")) {
                request.response().setStatusCode(500)
                        .end("{\"requests\":[{\"id\":1},{\"id\":2},{\"id\":3}]}");
            } else if (request.uri().equals("/orders/collection-stream")) {
                request.response().setChunked(true);
                request.response().write("{\"requests\":[{\"id\":1},{\"id\":2},{\"id\":3},");
                request.response().write(Buffer.buffer(new byte[2048]));
            } else {
                request.response().setStatusCode(207).putHeader("X-Upstream", "yes").end("response");
            }
        })).listen(0, "127.0.0.1").toCompletionStage().toCompletableFuture().join();
        baseUrl = URI.create("http://127.0.0.1:" + server.actualPort());
    }

    @Test
    void completesCollectionScanAfterLimitPlusOneWithoutBufferingTheWholeResponse() {
        var client = client(RoutingMode.PATH);

        CollectionScan page = assertTimeoutPreemptively(Duration.ofSeconds(1),
                () -> client.scanCollection("orders", new TransportRequest(HttpMethod.GET,
                        "/collection-stream", Map.of(), new byte[0]), new ObjectMapper(),
                        "requests", 0, 2, 64 * 1024 * 1024, 100));

        assertEquals(2, page.items().size());
        assertEquals(1, page.items().get(0).path("id").asInt());
        assertEquals(2, page.items().get(1).path("id").asInt());
        assertEquals(2, page.nextPosition());
        assertEquals(true, page.hasMore());
        client.close();
    }

    @Test
    void doesNotTreatAnErrorResponseContainingACollectionAsSuccess() {
        var client = client(RoutingMode.PATH);

        McpOperationException error = assertThrows(McpOperationException.class,
                () -> client.scanCollection("orders", new TransportRequest(HttpMethod.GET,
                        "/collection-error", Map.of(), new byte[0]), new ObjectMapper(),
                        "requests", 0, 2, 64 * 1024 * 1024, 100));

        assertEquals("WIREMOCK_ADMIN_ERROR", error.code());
        assertEquals(500, error.details().get("status"));
        client.close();
    }

    @AfterEach
    void stopServer() {
        server.close().toCompletionStage().toCompletableFuture().join();
        vertx.close().toCompletionStage().toCompletableFuture().join();
    }

    @Test
    void pathModeUsesProxyDestinationAndPrefixesMockId() {
        var client = client(RoutingMode.PATH);

        TransportResponse response = client.execute("orders", new TransportRequest(HttpMethod.POST,
                "/events?type=created", Map.of("X-Test", List.of("value")), new byte[] { 0, 1, 2 }));

        assertEquals(207, response.status());
        assertEquals("yes", response.headers().get("x-upstream").getFirst());
        assertEquals("/orders/events?type=created", captured.get().uri());
        assertEquals("value", captured.get().testHeader());
        assertArrayEquals(new byte[] { 0, 1, 2 }, captured.get().body());
        client.close();
    }

    @Test
    void hostModeKeepsProxyDestinationAndOverridesOnlyHostHeader() {
        var client = client(RoutingMode.HOST);

        client.execute("orders", new TransportRequest(HttpMethod.GET,
                "/__admin/version", Map.of(), new byte[0]));

        assertEquals("/__admin/version", captured.get().uri());
        assertEquals("orders.fleet.example.test", captured.get().host());
        client.close();
    }

    @Test
    void abortsAStreamingResponseAsSoonAsThePayloadLimitIsExceeded() {
        var client = client(RoutingMode.PATH);

        McpOperationException error = assertTimeoutPreemptively(Duration.ofSeconds(1),
                () -> assertThrows(McpOperationException.class,
                        () -> client.execute("orders", new TransportRequest(HttpMethod.GET,
                                "/large-stream", Map.of(), new byte[0]))));

        assertEquals("RESULT_TOO_LARGE", error.code());
        client.close();
    }

    private FleetProxyClient client(RoutingMode mode) {
        return new FleetProxyClient(vertx, baseUrl, mode, "fleet.example.test", Duration.ofSeconds(2),
                1024, new McpMetrics(new SimpleMeterRegistry()));
    }

    private record CapturedRequest(HttpMethod method, String uri, String host, String testHeader, byte[] body) {
    }
}
