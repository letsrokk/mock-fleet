package com.github.letsrokk.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FleetApiClientTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<CapturedRequest> requests = new ArrayList<>();
    private Vertx vertx;
    private HttpServer server;
    private FleetApiClient client;

    @BeforeEach
    void startServer() {
        vertx = Vertx.vertx();
        server = vertx.createHttpServer().requestHandler(request -> request.bodyHandler(body -> {
            requests.add(new CapturedRequest(request.method().name(), request.uri(), body.toString()));
            String response = request.uri().equals("/__fleet/api/mappings") ? "{\"enabled\":true}" : "{}";
            request.response().setStatusCode(request.method().name().equals("DELETE")
                    && request.uri().startsWith("/__fleet/api/mocks/") ? 204 : 200)
                    .putHeader("Content-Type", "application/json").end(response);
        })).listen(0, "127.0.0.1").toCompletionStage().toCompletableFuture().join();
        client = new FleetApiClient(vertx, URI.create("http://127.0.0.1:" + server.actualPort()),
                Duration.ofSeconds(2), 4096, mapper, new McpMetrics(new SimpleMeterRegistry()));
    }

    @AfterEach
    void stopServer() {
        client.close();
        server.close().toCompletionStage().toCompletableFuture().join();
        vertx.close().toCompletionStage().toCompletableFuture().join();
    }

    @Test
    void usesOnlyThePublishedFleetApiRoutes() throws Exception {
        client.listMocks();
        client.stopMock("orders");
        client.getConfig();
        client.updateConfig("orders", "42", List.of("--verbose"),
                mapper.readTree("{\"requests\":{},\"limits\":{}}"), "futureOnly");
        client.deleteConfig("orders", "43", "restartActive");
        assertTrue(client.storageEnabled());

        assertEquals(List.of(
                "GET /__fleet/api/mocks",
                "DELETE /__fleet/api/mocks/orders",
                "GET /__fleet/api/config",
                "PUT /__fleet/api/config/orders",
                "DELETE /__fleet/api/config/orders",
                "GET /__fleet/api/mappings"), requests.stream().map(CapturedRequest::summary).toList());
        assertFalse(requests.stream().anyMatch(request -> request.uri().contains("/internal/mocks/")));

        var update = mapper.readTree(requests.get(3).body());
        assertEquals("42", update.path("resourceVersion").asText());
        assertEquals("futureOnly", update.path("applyMode").asText());
        assertEquals(List.of("--verbose"), mapper.convertValue(update.path("options"), List.class));
        assertEquals(Map.of(), mapper.convertValue(update.path("resources").path("requests"), Map.class));
    }

    @Test
    void rejectsPartialConfigWritesBeforeCallingFleetApi() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> client.updateConfig("orders", "42", List.of(),
                mapper.readTree("{\"requests\":{}}"), "futureOnly"));
        assertThrows(IllegalArgumentException.class, () -> client.updateConfig("orders", "42", List.of(),
                mapper.readTree("{\"requests\":{},\"limits\":{}}"), "now"));
        assertEquals(0, requests.size());
    }

    private record CapturedRequest(String method, String uri, String body) {
        String summary() {
            return method + " " + uri;
        }
    }
}
