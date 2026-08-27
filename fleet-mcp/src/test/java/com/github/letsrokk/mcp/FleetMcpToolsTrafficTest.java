package com.github.letsrokk.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkiverse.mcp.server.ToolResponse;
import io.vertx.core.http.HttpMethod;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FleetMcpToolsTrafficTest {

    @Test
    void createStubCannotRaceRecorderSnapshotForTheSameMock() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var transport = new BlockingRecorderTransport();
        var registry = new SimpleMeterRegistry();
        var metrics = new McpMetrics(registry);
        var wireMock = new WireMockAdminClient(transport, mapper, 4096, Set.of("authorization"), metrics,
                new WireMockVersion(3, 13, 2));
        var tools = new FleetMcpTools(null, wireMock, config(), mapper,
                new OutboundTargetValidator(new TargetUrlPolicy(Set.of())), new PerMockCoordinator(),
                new McpToolExecutor(registry), metrics);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var create = executor.submit(() -> tools.createStub("orders", Map.of()));
            assertTrue(transport.createEntered.await(1, TimeUnit.SECONDS));

            var snapshot = executor.submit(() -> tools.snapshotRequests("orders", Map.of()));
            assertFalse(transport.snapshotScanEntered.await(100, TimeUnit.MILLISECONDS));

            transport.releaseCreate.countDown();
            assertFalse(create.get(1, TimeUnit.SECONDS).isError());
            assertFalse(snapshot.get(1, TimeUnit.SECONDS).isError());
            assertTrue(transport.snapshotScanEntered.await(1, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { 404, 500 })
    void sendRequestReturnsIntentionalNonSuccessResponsesAsToolResults(int status) {
        ObjectMapper mapper = new ObjectMapper();
        var transport = new QueuedTransport();
        transport.respond(200, "{\"version\":\"3.13.2\"}");
        transport.respond(status, "upstream-body");
        var registry = new SimpleMeterRegistry();
        var metrics = new McpMetrics(registry);
        var wireMock = new WireMockAdminClient(transport, mapper, 4096, Set.of("authorization"), metrics,
                new WireMockVersion(3, 13, 2));
        var tools = new FleetMcpTools(null, wireMock, config(), mapper, null, null,
                new McpToolExecutor(registry), metrics);

        ToolResponse response = tools.sendRequest("orders", "GET", "/expected-error", null, null, null);

        assertFalse(response.isError());
        ObjectNode structured = (ObjectNode) response.structuredContent();
        assertEquals(status, structured.path("status").asInt());
        assertEquals("upstream-body", structured.path("body").asText());
    }

    private static FleetMcpConfig config() {
        return new FleetMcpConfig() {
            @Override public URI apiBaseUrl() { return URI.create("http://api"); }
            @Override public URI proxyBaseUrl() { return URI.create("http://proxy"); }
            @Override public RoutingMode routingMode() { return RoutingMode.PATH; }
            @Override public Optional<String> fleetHost() { return Optional.empty(); }
            @Override public String wiremockImage() { return "wiremock/wiremock:3.13.2-2"; }
            @Override public boolean storageEnabled() { return false; }
            @Override public Duration timeout() { return Duration.ofSeconds(1); }
            @Override public int defaultPageSize() { return 50; }
            @Override public int maxPageSize() { return 200; }
            @Override public int maxPayloadBytes() { return 4096; }
            @Override public int includedBodyBytes() { return 4096; }
            @Override public List<String> sensitiveHeaders() { return List.of("Authorization"); }
            @Override public Optional<List<String>> outboundExceptions() { return Optional.empty(); }
            @Override public Optional<List<String>> outboundAllowedListeners() { return Optional.empty(); }
        };
    }

    private static final class QueuedTransport implements FleetProxyTransport {
        private final ArrayDeque<TransportResponse> responses = new ArrayDeque<>();

        void respond(int status, String body) {
            responses.add(new TransportResponse(status, Map.of("content-type", List.of("text/plain")),
                    body.getBytes(StandardCharsets.UTF_8)));
        }

        @Override
        public TransportResponse execute(String mockId, TransportRequest request) {
            return responses.removeFirst();
        }
    }

    private static final class BlockingRecorderTransport implements FleetProxyTransport {
        private final CountDownLatch createEntered = new CountDownLatch(1);
        private final CountDownLatch releaseCreate = new CountDownLatch(1);
        private final CountDownLatch snapshotScanEntered = new CountDownLatch(1);

        @Override
        public TransportResponse execute(String mockId, TransportRequest request) {
            String body;
            if ("/__admin/version".equals(request.target())) {
                body = "{\"version\":\"3.13.2\"}";
            } else if (request.method() == HttpMethod.POST && "/__admin/mappings".equals(request.target())) {
                createEntered.countDown();
                await(releaseCreate);
                body = "{\"id\":\"created-id\",\"persistent\":false}";
            } else if (request.method() == HttpMethod.GET
                    && request.target().startsWith("/__admin/mappings?limit=")) {
                snapshotScanEntered.countDown();
                body = "{\"mappings\":[],\"meta\":{\"total\":0}}";
            } else if (request.method() == HttpMethod.POST
                    && "/__admin/recordings/snapshot".equals(request.target())) {
                body = "{\"ids\":[]}";
            } else {
                throw new AssertionError("Unexpected request: " + request.method() + " " + request.target());
            }
            return new TransportResponse(200, Map.of("content-type", List.of("application/json")),
                    body.getBytes(StandardCharsets.UTF_8));
        }

        private static void await(CountDownLatch latch) {
            try {
                latch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }
}
