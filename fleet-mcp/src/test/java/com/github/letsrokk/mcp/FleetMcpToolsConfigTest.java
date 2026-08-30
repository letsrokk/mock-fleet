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
              "mockIds":["runtime-only","alpha","zeta"],
              "savedMockIds":["alpha","zeta"],
              "mocks":[
                {"mockId":"alpha","lifecycle":"STOPPED","baseline":{"version":null,"options":[],"resources":{"requests":{},"limits":{}}},"user":{"version":"3.12.1","options":["--verbose"],"resources":{"requests":{},"limits":{}}},"effective":{"version":"3.12.1","options":["--verbose"],"resources":{"requests":{},"limits":{}}},"wireMockVersion":"3.12.1","runtimeVersion":null},
                {"mockId":"zeta","lifecycle":"RUNNING","baseline":{"version":null,"options":[],"resources":{"requests":{},"limits":{}}},"user":{"version":null,"options":[],"resources":{"requests":{},"limits":{}}},"effective":{"version":"3.13.2","options":[],"resources":{"requests":{},"limits":{}}},"wireMockVersion":"3.13.2","runtimeVersion":"3.12.1"},
                {"mockId":"runtime-only","lifecycle":"STARTING","baseline":{"version":null,"options":[],"resources":{"requests":{},"limits":{}}},"user":{"version":null,"options":[],"resources":null},"effective":{"version":"3.13.2","options":[],"resources":{"requests":{},"limits":{}}},"wireMockVersion":"3.13.2","runtimeVersion":"3.13.2"}
              ],
              "routing":{"mode":"PATH","host":"mock-fleet.localhost"},"defaultVersion":"3.13.2",
              "versions":[{"version":"3.13.2","image":"wiremock/wiremock:3.13.2-2","selectable":true}],
              "catalogResourceVersion":"7"
            }
            """;

    private static final String OPTION_CATALOG = """
            {"wireMockVersion":"3.13.2","catalogStatus":"newer_unresearched",
             "options":[{"name":"--verbose","label":"Verbose","kind":"flag","group":"Logging",
             "description":"Log details","values":[],"minimum":null,"maximum":null}]}
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
    void mergesConfiguredInactiveActiveAndRuntimeOnlyMocksFromOneConfigSnapshot() throws Exception {
        var response = tools.listMocks(null, null);

        assertFalse(response.isError(), response.toString());
        assertEquals(mapper.readTree("""
                {
                  "mocks":[
                    {"mockId":"alpha","lifecycle":"STOPPED","wireMockVersion":"3.12.1",
                     "runtimeVersion":null,"hasSavedConfig":true},
                    {"mockId":"runtime-only","lifecycle":"STARTING","wireMockVersion":"3.13.2",
                     "runtimeVersion":"3.13.2","hasSavedConfig":false},
                    {"mockId":"zeta","lifecycle":"RUNNING","wireMockVersion":"3.13.2",
                     "runtimeVersion":"3.12.1","hasSavedConfig":true}
                  ],
                  "page":{"limit":50,"returned":3,"hasMore":false,"nextCursor":null}
                }
                """), response.structuredContent());
        assertEquals(List.of("GET /__fleet/api/config"), requests);
    }

    @Test
    void sortsMocksBeforeApplyingCursorPagination() throws Exception {
        var firstResponse = tools.listMocks(2, null);

        assertFalse(firstResponse.isError(), firstResponse.toString());
        ObjectNode first = (ObjectNode) firstResponse.structuredContent();
        assertEquals(mapper.readTree("[\"alpha\",\"runtime-only\"]"), mockIds(first));
        assertTrue(first.path("page").path("hasMore").asBoolean());

        var secondResponse = tools.listMocks(2, first.path("page").path("nextCursor").asText());

        assertFalse(secondResponse.isError(), secondResponse.toString());
        ObjectNode second = (ObjectNode) secondResponse.structuredContent();
        assertEquals(mapper.readTree("[\"zeta\"]"), mockIds(second));
        assertFalse(second.path("page").path("hasMore").asBoolean());
        assertEquals(List.of("GET /__fleet/api/config", "GET /__fleet/api/config"), requests);
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
    void forwardsTheVersionedCatalogWithoutReshapingIt() throws Exception {
        responseBody = OPTION_CATALOG;

        var response = tools.listOptionDefinitions("3.13.2+candidate");

        assertFalse(response.isError());
        assertEquals(mapper.readTree(OPTION_CATALOG), response.structuredContent());
        assertEquals(List.of("GET /__fleet/api/config/options?version=3.13.2%2Bcandidate"), requests);
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

    private com.fasterxml.jackson.databind.JsonNode mockIds(ObjectNode result) {
        return mapper.valueToTree(java.util.stream.StreamSupport.stream(result.path("mocks").spliterator(), false)
                .map(row -> row.path("mockId").asText())
                .toList());
    }
}
