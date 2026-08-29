package com.github.letsrokk.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FleetMcpToolsConfigTest {

    private static final String CONFIG_VIEW = """
            {
              "resourceVersion":"42",
              "mockIds":["active-only","alpha","zeta"],
              "savedMockIds":["alpha","beta","zeta"],
              "mocks":[
                {"mockId":"alpha","active":false,"baseline":{"options":[],"resources":{"requests":{},"limits":{}}},"user":{"options":["--verbose"],"resources":{"requests":{},"limits":{}}},"effective":{"options":["--verbose"],"resources":{"requests":{},"limits":{}}}},
                {"mockId":"zeta","active":true,"baseline":{"options":[],"resources":{"requests":{},"limits":{}}},"user":{"options":[],"resources":{"requests":{},"limits":{}}},"effective":{"options":[],"resources":{"requests":{},"limits":{}}}}
              ],
              "options":[{"name":"--verbose"}],
              "routing":{"mode":"PATH","host":"mock-fleet.localhost"}
            }
            """;

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<String> requests = new ArrayList<>();
    private Vertx vertx;
    private HttpServer server;
    private FleetApiClient fleetApi;
    private FleetMcpTools tools;
    private String responseBody;

    @BeforeEach
    void startServer() {
        responseBody = CONFIG_VIEW;
        vertx = Vertx.vertx();
        server = vertx.createHttpServer().requestHandler(request -> request.bodyHandler(body -> {
            requests.add(request.method().name() + " " + request.uri());
            request.response().putHeader("Content-Type", "application/json").end(responseBody);
        })).listen(0, "127.0.0.1").toCompletionStage().toCompletableFuture().join();
        var registry = new SimpleMeterRegistry();
        var metrics = new McpMetrics(registry);
        fleetApi = new FleetApiClient(vertx, URI.create("http://127.0.0.1:" + server.actualPort()),
                Duration.ofSeconds(2), 4096, mapper, metrics);
        tools = new FleetMcpTools(fleetApi, null, config(), mapper, null, null,
                new McpToolExecutor(registry), metrics);
    }

    @AfterEach
    void stopServer() {
        fleetApi.close();
        server.close().toCompletionStage().toCompletableFuture().join();
        vertx.close().toCompletionStage().toCompletableFuture().join();
    }

    @Test
    void listsOnlySavedMockIdsWithStablePaginationMetadata() throws Exception {
        var firstResponse = tools.listMockConfigs(2, null);

        assertFalse(firstResponse.isError());
        ObjectNode first = (ObjectNode) firstResponse.structuredContent();
        String cursor = first.path("page").path("nextCursor").asText();
        var response = tools.listMockConfigs(2, cursor);

        assertFalse(response.isError());
        ObjectNode result = (ObjectNode) response.structuredContent();
        assertEquals("42", result.path("resourceVersion").asText());
        assertEquals(mapper.readTree("[\"zeta\"]"), result.path("mockIds"));
        assertEquals(2, result.path("page").path("limit").asInt());
        assertEquals(1, result.path("page").path("returned").asInt());
        assertFalse(result.path("page").path("hasMore").asBoolean());
        assertTrue(result.path("page").path("nextCursor").isNull());
        assertFalse(result.has("mocks"));
        assertEquals(List.of("GET /__fleet/api/config", "GET /__fleet/api/config"), requests);
    }

    @Test
    void listsAnEmptySavedConfigCollection() throws Exception {
        responseBody = """
                {"resourceVersion":"42","mockIds":["active-only"],"savedMockIds":[],"mocks":[],"options":[],"routing":{"mode":"PATH","host":"mock-fleet.localhost"}}
                """;

        var response = tools.listMockConfigs(null, null);

        assertFalse(response.isError());
        ObjectNode result = (ObjectNode) response.structuredContent();
        assertEquals(mapper.readTree("[]"), result.path("mockIds"));
        assertEquals(50, result.path("page").path("limit").asInt());
        assertEquals(0, result.path("page").path("returned").asInt());
        assertFalse(result.path("page").path("hasMore").asBoolean());
        assertTrue(result.path("page").path("nextCursor").isNull());
    }

    @Test
    void preservesNullResourceVersionInConfigWrapper() {
        responseBody = """
                {"resourceVersion":null,"mockIds":[],"savedMockIds":[],"mocks":[],"options":[],
                 "routing":{"mode":"PATH","host":"mock-fleet.localhost"}}
                """;

        var response = tools.listMockConfigs(null, null);

        assertFalse(response.isError());
        assertTrue(((ObjectNode) response.structuredContent()).path("resourceVersion").isNull());
    }

    @Test
    void updateSchemaExplainsTheClusterOwnedResourceEnvelope() {
        String description = new UpdateMockConfigInputSchemaGenerator().generate(null)
                .getJsonObject("properties")
                .getJsonObject("resources")
                .getString("description");

        assertTrue(description.contains("cpu and memory"));
        assertTrue(description.contains("request floor"));
        assertTrue(description.contains("limit ceiling"));
    }

    @Test
    void getsOneCompactConfig() {
        var response = tools.getMockConfig("alpha");

        assertFalse(response.isError());
        ObjectNode result = (ObjectNode) response.structuredContent();
        assertEquals("42", result.path("resourceVersion").asText());
        assertEquals("alpha", result.path("mock").path("mockId").asText());
        assertEquals("PATH", result.path("routing").path("mode").asText());
        assertFalse(result.has("mocks"));
        assertFalse(result.has("missingMockIds"));
        assertFalse(result.has("optionDefinitions"));
        assertEquals(List.of("GET /__fleet/api/config"), requests);
    }

    @Test
    void reportsMissingConfigAsNotFound() {
        var response = tools.getMockConfig("missing");

        assertTrue(response.isError());
        assertEquals("NOT_FOUND", ((McpToolExecutor.ErrorEnvelope) response.structuredContent()).error().code());
        assertEquals(List.of("GET /__fleet/api/config"), requests);
    }

    @Test
    void rejectsInvalidMockIdBeforeCallingFleetApi() {
        var response = tools.getMockConfig("bad_id");

        assertTrue(response.isError());
        assertEquals("INVALID_ARGUMENT", ((McpToolExecutor.ErrorEnvelope) response.structuredContent()).error().code());
        assertEquals(List.of(), requests);
    }

    private FleetMcpConfig config() {
        return new FleetMcpConfig() {
            @Override public URI apiBaseUrl() { return URI.create("http://api"); }
            @Override public URI proxyBaseUrl() { return URI.create("http://proxy"); }
            @Override public RoutingMode routingMode() { return RoutingMode.PATH; }
            @Override public Optional<String> fleetHost() { return Optional.empty(); }
            @Override public String wiremockImage() { return "wiremock/wiremock:3.13.2-2"; }
            @Override public boolean storageEnabled() { return false; }
            @Override public Duration timeout() { return Duration.ofSeconds(1); }
            @Override public Duration lifecycleTimeout() { return Duration.ofSeconds(2); }
            @Override public int defaultPageSize() { return 50; }
            @Override public int maxPageSize() { return 200; }
            @Override public int maxPayloadBytes() { return 4096; }
            @Override public int includedBodyBytes() { return 4096; }
            @Override public Duration dependencyHealthTimeout() { return Duration.ofSeconds(1); }
            @Override public long maxCollectionScanBytes() { return 64 * 1024 * 1024; }
            @Override public int maxCollectionScanItems() { return 100_000; }
            @Override public List<String> sensitiveHeaders() { return List.of("Authorization"); }
            @Override public Optional<List<String>> outboundExceptions() { return Optional.empty(); }
            @Override public Optional<List<String>> outboundAllowedListeners() { return Optional.empty(); }
        };
    }
}
