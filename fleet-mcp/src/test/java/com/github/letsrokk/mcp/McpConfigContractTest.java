package com.github.letsrokk.mcp;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.MockitoConfig;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class McpConfigContractTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @InjectMock
    @MockitoConfig(convertScopes = true)
    FleetApiClient fleetApi;

    @InjectMock
    @MockitoConfig(convertScopes = true)
    WireMockAdminClient wireMock;

    private String sessionId;

    @BeforeEach
    void initialize() {
        when(wireMock.version(any())).thenReturn(new WireMockVersion(3, 13, 2));
        sessionId = given()
                .contentType("application/json")
                .accept("application/json, text/event-stream")
                .body("""
                        {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                          "protocolVersion":"2025-11-25","capabilities":{},
                          "clientInfo":{"name":"test","version":"1"}}}
                        """)
        .when()
                .post("/__fleet/mcp")
        .then()
                .statusCode(200)
                .extract().header("Mcp-Session-Id");

        given()
                .header("Mcp-Session-Id", sessionId)
                .header("Mcp-Protocol-Version", "2025-11-25")
                .contentType("application/json")
                .accept("application/json, text/event-stream")
                .body("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")
        .when()
                .post("/__fleet/mcp")
        .then()
                .statusCode(202);
    }

    @Test
    void returnsFocusedConfigAndMetadataResponses() throws Exception {
        when(fleetApi.getConfig()).thenReturn(configView(true));

        JsonNode config = structured(callTool("get_mock_config", "{\"mockId\":\"catalog\"}"));
        assertEquals("42", config.path("resourceVersion").asText());
        assertEquals("catalog", config.path("mock").path("mockId").asText());
        assertTrue(config.path("mock").path("user").path("resources").isNull());
        assertTrue(config.has("routing"));
        assertFalse(config.has("optionDefinitions"));

        JsonNode metadata = structured(callTool("list_option_definitions", "{}"));
        assertEquals("--verbose", metadata.path("optionDefinitions").path(0).path("name").asText());
        assertEquals(1, metadata.size());
    }

    @Test
    void returnsStructuredNotFoundForMissingConfig() throws Exception {
        when(fleetApi.getConfig()).thenReturn(configView(false));

        JsonNode result = callTool("get_mock_config", "{\"mockId\":\"missing\"}");

        assertTrue(result.path("isError").asBoolean());
        assertEquals("NOT_FOUND", result.path("structuredContent").path("code").asText());
        assertEquals("missing", result.path("structuredContent").path("details").path("mockId").asText());
    }

    @Test
    void returnsCompactUpdateAndDeleteResponses() throws Exception {
        JsonNode view = configView(true);
        when(fleetApi.updateConfig("catalog", "41", List.of("--verbose"), null, ConfigApplyMode.futureOnly))
                .thenReturn(view);
        when(fleetApi.deleteConfig("catalog", "42", ConfigApplyMode.restartActive))
                .thenReturn(configView(false));

        JsonNode update = structured(callTool("update_mock_config", """
                {"mockId":"catalog","resourceVersion":"41","options":["--verbose"],"applyMode":"futureOnly"}
                """));
        assertEquals("42", update.path("resourceVersion").asText());
        assertEquals("catalog", update.path("mock").path("mockId").asText());
        assertEquals("futureOnly", update.path("applyMode").asText());
        assertFalse(update.has("mocks"));
        assertFalse(update.has("options"));
        assertFalse(update.has("mockIds"));

        JsonNode deletion = structured(callTool("delete_mock_config", """
                {"mockId":"catalog","resourceVersion":"42","applyMode":"restartActive"}
                """));
        assertEquals("catalog", deletion.path("mockId").asText());
        assertTrue(deletion.path("deleted").asBoolean());
        assertEquals("restartActive", deletion.path("applyMode").asText());
        assertFalse(deletion.has("mocks"));
    }

    @Test
    void rejectsIncompleteResourceOverridesWithTheSpecificField() throws Exception {
        JsonNode result = callTool("update_mock_config", """
                {"mockId":"catalog","resourceVersion":"41","options":[],
                 "resources":{"requests":{}},"applyMode":"futureOnly"}
                """);

        assertTrue(result.path("isError").asBoolean());
        assertEquals("INVALID_ARGUMENT", result.path("structuredContent").path("code").asText());
        assertEquals("resources.limits is required when resources are provided",
                result.path("structuredContent").path("message").asText());
    }

    @Test
    void keepsExplicitEmptyResourcesDistinctFromInheritance() throws Exception {
        MockResources emptyOverride = new MockResources(Map.of(), Map.of());
        when(fleetApi.updateConfig("catalog", "41", List.of(), emptyOverride, ConfigApplyMode.futureOnly))
                .thenReturn(configView(true));

        JsonNode update = structured(callTool("update_mock_config", """
                {"mockId":"catalog","resourceVersion":"41","options":[],
                 "resources":{"requests":{},"limits":{}},"applyMode":"futureOnly"}
                """));

        assertEquals("catalog", update.path("mock").path("mockId").asText());
    }

    @Test
    void rejectsInconsistentUpstreamMutationResponses() throws Exception {
        when(fleetApi.updateConfig("catalog", "41", List.of(), null, ConfigApplyMode.futureOnly))
                .thenReturn(configView(false));
        when(fleetApi.deleteConfig("catalog", "42", ConfigApplyMode.futureOnly))
                .thenReturn(configView(true));
        when(fleetApi.deleteConfig("catalog", "43", ConfigApplyMode.futureOnly))
                .thenReturn(mapper.readTree("{\"resourceVersion\":\"44\"}"));

        JsonNode missingUpdate = callTool("update_mock_config", """
                {"mockId":"catalog","resourceVersion":"41","options":[],"applyMode":"futureOnly"}
                """);
        JsonNode lingeringDelete = callTool("delete_mock_config", """
                {"mockId":"catalog","resourceVersion":"42","applyMode":"futureOnly"}
                """);
        JsonNode malformedDelete = callTool("delete_mock_config", """
                {"mockId":"catalog","resourceVersion":"43","applyMode":"futureOnly"}
                """);

        assertEquals("INVALID_UPSTREAM_RESPONSE",
                missingUpdate.path("structuredContent").path("code").asText());
        assertEquals("INVALID_UPSTREAM_RESPONSE",
                lingeringDelete.path("structuredContent").path("code").asText());
        assertEquals("INVALID_UPSTREAM_RESPONSE",
                malformedDelete.path("structuredContent").path("code").asText());
    }

    @Test
    void passesNestedRequestPatternsThroughToolsCall() throws Exception {
        when(wireMock.countRequests(eq("catalog"), any(JsonNode.class))).thenAnswer(invocation -> {
            JsonNode pattern = invocation.getArgument(1);
            int count = pattern.path("headers").path("X-Tenant").path("equalTo").asText().equals("payments")
                    ? 3 : 0;
            return mapper.createObjectNode().put("count", count);
        });

        JsonNode response = structured(callTool("count_requests", """
                {"mockId":"catalog","requestPattern":{
                  "headers":{"X-Tenant":{"equalTo":"payments"}}
                }}
                """));

        assertEquals(3, response.path("count").asInt());
    }

    private JsonNode callTool(String name, String arguments) throws Exception {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{"
                + "\"name\":\"" + name + "\",\"arguments\":" + arguments + "}}";
        String response = given()
                .header("Mcp-Session-Id", sessionId)
                .header("Mcp-Protocol-Version", "2025-11-25")
                .contentType("application/json")
                .accept("application/json, text/event-stream")
                .body(body)
        .when()
                .post("/__fleet/mcp")
        .then()
                .statusCode(200)
                .extract().asString();
        return mapper.readTree(response).path("result");
    }

    private JsonNode structured(JsonNode result) {
        assertFalse(result.path("isError").asBoolean(), result.toPrettyString());
        return result.path("structuredContent");
    }

    private JsonNode configView(boolean includeCatalog) throws Exception {
        String mocks = includeCatalog ? """
                [{"mockId":"catalog","active":false,
                  "baseline":{"options":["--verbose"],"resources":{"requests":{"cpu":"0.5"},"limits":{"cpu":"1"}}},
                  "user":{"options":[],"resources":null},
                  "effective":{"options":["--verbose"],"resources":{"requests":{"cpu":"0.5"},"limits":{"cpu":"1"}}}}]
                """ : "[]";
        return mapper.readTree("""
                {"resourceVersion":"42","mockIds":%s,"mocks":%s,
                 "options":[{"name":"--verbose","label":"Verbose","kind":"flag","group":"Logging","description":"Log details","values":[]}],
                 "routing":{"mode":"PATH","host":"mock-fleet.localhost"}}
                """.formatted(includeCatalog ? "[\"catalog\"]" : "[]", mocks));
    }
}
