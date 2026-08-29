package com.github.letsrokk.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    private int responseStatus;
    private String responseBody;
    private long deleteDelayMillis;

    @BeforeEach
    void startServer() {
        responseStatus = 200;
        responseBody = "{}";
        deleteDelayMillis = 0;
        vertx = Vertx.vertx();
        server = vertx.createHttpServer().requestHandler(request -> request.bodyHandler(body -> {
            requests.add(new CapturedRequest(request.method().name(), request.uri(), body.toString()));
            String response = request.uri().equals("/__fleet/api/mappings") ? "{\"enabled\":true}" : responseBody;
            int status = request.method().name().equals("DELETE")
                    && request.uri().startsWith("/__fleet/api/mocks/") ? 200 : responseStatus;
            Runnable reply = () -> request.response().setStatusCode(status)
                    .putHeader("Content-Type", "application/json").end(response);
            if (deleteDelayMillis > 0 && request.method().name().equals("DELETE")
                    && request.uri().startsWith("/__fleet/api/mocks/")) {
                vertx.setTimer(deleteDelayMillis, ignored -> reply.run());
            } else {
                reply.run();
            }
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
        client.startMock("orders");
        client.stopMock("orders");
        client.getConfig();
        client.getOptionCatalog(null);
        client.getOptionCatalog("3.13.2+candidate");
        client.updateConfig("orders", "42", List.of("--verbose"),
                new MockResources(Map.of(), Map.of()), ConfigApplyMode.futureOnly);
        client.deleteConfig("orders", "43", ConfigApplyMode.restartActive);
        assertTrue(client.storageEnabled());

        assertEquals(List.of(
                "GET /__fleet/api/mocks",
                "POST /__fleet/api/mocks/orders/start",
                "DELETE /__fleet/api/mocks/orders",
                "GET /__fleet/api/config",
                "GET /__fleet/api/config/options",
                "GET /__fleet/api/config/options?version=3.13.2%2Bcandidate",
                "PUT /__fleet/api/config/orders",
                "DELETE /__fleet/api/config/orders",
                "GET /__fleet/api/mappings"), requests.stream().map(CapturedRequest::summary).toList());
        assertFalse(requests.stream().anyMatch(request -> request.uri().contains("/internal/mocks/")));

        var update = mapper.readTree(requests.stream()
                .filter(request -> request.summary().equals("PUT /__fleet/api/config/orders"))
                .findFirst().orElseThrow().body());
        assertEquals("42", update.path("resourceVersion").asText());
        assertEquals("futureOnly", update.path("applyMode").asText());
        assertEquals(List.of("--verbose"), mapper.convertValue(update.path("options"), List.class));
        assertEquals(Map.of(), mapper.convertValue(update.path("resources").path("requests"), Map.class));
    }

    @Test
    void returnsThePublicStartingLifecycleResponse() {
        responseStatus = 202;
        responseBody = """
                {"mockId":"orders","status":"STARTING","podName":null,"message":null,"retryAfterMs":1000}
                """;

        var result = client.startMock("orders");

        assertEquals("STARTING", result.path("status").asText());
        assertEquals(1000, result.path("retryAfterMs").asInt());
        assertEquals(List.of("POST /__fleet/api/mocks/orders/start"),
                requests.stream().map(CapturedRequest::summary).toList());
    }

    @Test
    void translatesFleetApiErrorsWithoutDiscardingMutationState() {
        responseStatus = 503;
        responseBody = """
                {"code":"MOCK_START_FAILED","message":"ImagePullBackOff: denied","retryable":true,
                 "stateMayHaveChanged":false,"details":{"mockId":"orders","podName":"mock-orders-1"}}
                """;

        McpOperationException error = assertThrows(McpOperationException.class, () -> client.startMock("orders"));

        assertEquals("MOCK_START_FAILED", error.code());
        assertEquals("ImagePullBackOff: denied", error.getMessage());
        assertTrue(error.retryable());
        assertFalse(error.stateMayHaveChanged());
        assertEquals("mock-orders-1", error.details().get("podName"));
    }

    @Test
    void preservesExplicitMutationStateForStructuredUnavailableErrors() {
        responseStatus = 503;
        responseBody = """
                {"code":"UPSTREAM_UNAVAILABLE","message":"Kubernetes API was unavailable","retryable":true,
                 "stateMayHaveChanged":false,"details":{"mockId":"orders"}}
                """;

        McpOperationException error = assertThrows(McpOperationException.class, () -> client.startMock("orders"));

        assertEquals("UPSTREAM_UNAVAILABLE", error.code());
        assertFalse(error.stateMayHaveChanged());
    }

    @Test
    void usesTheLongerLifecycleTimeoutForPodDeletion() {
        deleteDelayMillis = 100;
        client.close();
        client = new FleetApiClient(vertx, URI.create("http://127.0.0.1:" + server.actualPort()),
                Duration.ofMillis(20), Duration.ofSeconds(1), 4096, mapper,
                new McpMetrics(new SimpleMeterRegistry()));

        client.stopMock("orders");

        assertEquals(List.of("DELETE /__fleet/api/mocks/orders"),
                requests.stream().map(CapturedRequest::summary).toList());
    }

    @Test
    void marksTimedOutPodDeletionAsPotentiallyChanged() {
        deleteDelayMillis = 200;
        client.close();
        client = new FleetApiClient(vertx, URI.create("http://127.0.0.1:" + server.actualPort()),
                Duration.ofSeconds(1), Duration.ofMillis(20), 4096, mapper,
                new McpMetrics(new SimpleMeterRegistry()));

        McpOperationException error = assertThrows(McpOperationException.class,
                () -> client.stopMock("orders"));

        assertEquals("UPSTREAM_UNAVAILABLE", error.code());
        assertTrue(error.retryable());
        assertTrue(error.stateMayHaveChanged());
    }

    @Test
    void marksMalformedPodDeletionResponseAsPotentiallyChanged() {
        responseBody = "not-json";

        McpOperationException error = assertThrows(McpOperationException.class,
                () -> client.stopMock("orders"));

        assertEquals("INVALID_UPSTREAM_RESPONSE", error.code());
        assertTrue(error.stateMayHaveChanged());
    }

    @Test
    void marksEmptyPodDeletionResponseAsPotentiallyChanged() {
        responseBody = "";

        McpOperationException error = assertThrows(McpOperationException.class,
                () -> client.stopMock("orders"));

        assertEquals("INVALID_UPSTREAM_RESPONSE", error.code());
        assertTrue(error.stateMayHaveChanged());
    }

    @Test
    void marksOversizedPodDeletionResponseAsPotentiallyChanged() {
        responseBody = "{\"mockId\":\"orders\",\"status\":\"STOPPED\"}";
        client.close();
        client = new FleetApiClient(vertx, URI.create("http://127.0.0.1:" + server.actualPort()),
                Duration.ofSeconds(1), Duration.ofSeconds(1), 8, mapper,
                new McpMetrics(new SimpleMeterRegistry()));

        McpOperationException error = assertThrows(McpOperationException.class,
                () -> client.stopMock("orders"));

        assertEquals("RESULT_TOO_LARGE", error.code());
        assertTrue(error.stateMayHaveChanged());
    }

    @Test
    void writesNullResourcesToPreserveInheritance() throws Exception {
        client.updateConfig("orders", "42", List.of(), null, ConfigApplyMode.futureOnly);

        var update = mapper.readTree(requests.getFirst().body());
        assertTrue(update.path("resources").isNull());
    }

    @Test
    void rejectsInvalidConfigFieldsBeforeCallingFleetApi() {
        IllegalArgumentException resourceVersion = assertThrows(IllegalArgumentException.class,
                () -> client.updateConfig("orders", " ", List.of(), null, ConfigApplyMode.futureOnly));
        assertEquals("resourceVersion is required", resourceVersion.getMessage());

        IllegalArgumentException requestsError = assertThrows(IllegalArgumentException.class,
                () -> client.updateConfig("orders", "42", List.of(),
                        new MockResources(null, Map.of()), ConfigApplyMode.futureOnly));
        assertEquals("resources.requests is required when resources are provided", requestsError.getMessage());

        IllegalArgumentException limits = assertThrows(IllegalArgumentException.class,
                () -> client.updateConfig("orders", "42", List.of(),
                        new MockResources(Map.of(), null), ConfigApplyMode.futureOnly));
        assertEquals("resources.limits is required when resources are provided", limits.getMessage());

        IllegalArgumentException applyMode = assertThrows(IllegalArgumentException.class,
                () -> client.updateConfig("orders", "42", List.of(), null, null));
        assertEquals("applyMode is required", applyMode.getMessage());
        assertEquals(0, requests.size());
    }

    private record CapturedRequest(String method, String uri, String body) {
        String summary() {
            return method + " " + uri;
        }
    }
}
