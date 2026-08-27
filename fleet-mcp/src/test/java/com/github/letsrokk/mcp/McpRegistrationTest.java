package com.github.letsrokk.mcp;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkiverse.mcp.server.ToolManager;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

@QuarkusTest
class McpRegistrationTest {

    private static final Set<String> EXPECTED_TOOLS = Set.of(
            "list_mocks", "get_mock_config", "list_option_definitions", "update_mock_config", "delete_mock_config", "stop_mock",
            "list_stubs", "list_unmatched_stubs", "get_stub", "create_stub", "update_stub", "delete_stub",
            "persist_stub", "unpersist_stub", "send_request", "find_requests", "count_requests",
            "list_unmatched_requests", "get_near_misses", "reset_request_journal", "start_recording",
            "recording_status", "stop_recording", "snapshot_requests", "list_body_files", "get_body_file",
            "put_body_file", "delete_body_file", "list_scenarios", "reset_scenarios");

    @Inject
    ToolManager toolManager;

    @Test
    void registersOnlyTheV1ToolSurface() {
        Set<String> actual = new HashSet<>();
        toolManager.forEach(tool -> actual.add(tool.name()));
        assertEquals(EXPECTED_TOOLS, actual);
    }

    @Test
    void publishesDirectJsonAndTypedConfigSchemas() throws Exception {
        String sessionId = initializeSession();
        initializeClient(sessionId);

        String response = given()
                .header("Mcp-Session-Id", sessionId)
                .header("Mcp-Protocol-Version", "2025-11-25")
                .contentType("application/json")
                .accept("application/json, text/event-stream")
                .body("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}")
        .when()
                .post("/__fleet/mcp")
        .then()
                .statusCode(200)
                .extract().asString();

        JsonNode tools = new ObjectMapper().readTree(response).path("result").path("tools");
        JsonNode updateConfig = tool(tools, "update_mock_config").path("inputSchema");
        JsonNode resources = updateConfig.path("properties").path("resources");
        assertTrue(resources.path("properties").has("requests"), updateConfig.toPrettyString());
        assertTrue(resources.path("properties").has("limits"));
        assertEquals(Set.of("requests", "limits"), Set.copyOf(textValues(resources.path("required"))));
        assertEquals("string", resources.path("properties").path("requests")
                .path("additionalProperties").path("type").asText());
        assertEquals("string", resources.path("properties").path("limits")
                .path("additionalProperties").path("type").asText());
        assertFalse(resources.toString().contains("$ref"));
        assertFalse(resources.path("properties").has("map"));
        assertFalse(textValues(updateConfig.path("required")).contains("resources"));
        assertEquals(List.of("futureOnly", "restartActive"),
                textValues(updateConfig.path("properties").path("applyMode").path("enum")));

        Map<String, String> arbitraryJsonArguments = Map.of(
                "create_stub", "mapping",
                "send_request", "headers",
                "find_requests", "requestPattern",
                "start_recording", "recording",
                "snapshot_requests", "snapshot");
        arbitraryJsonArguments.forEach((toolName, argumentName) -> {
            JsonNode argument = tool(tools, toolName).path("inputSchema").path("properties").path(argumentName);
            assertEquals("object", argument.path("type").asText(), toolName + "." + argumentName);
            assertFalse(argument.path("properties").has("map"), toolName + "." + argumentName);
        });
    }

    @Test
    void initializesStreamableHttpAtFleetPath() {
        given()
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
                .body(containsString("protocolVersion"))
                .body(containsString("mock-fleet"));
    }

    @Test
    void rejectsBrowserInitializationFromAnUnlistedOrigin() {
        given()
                .header("Origin", "https://attacker.example")
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
                .statusCode(403);
    }

    @Test
    void acceptsBrowserInitializationFromAConfiguredOrigin() {
        given()
                .header("Origin", "http://localhost:8080")
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
                .header("Access-Control-Allow-Origin", "http://localhost:8080");
    }

    @Test
    void serializesToolExecutionFailuresAsStructuredMcpErrors() {
        String sessionId = initializeSession();
        initializeClient(sessionId);

        given()
                .header("Mcp-Session-Id", sessionId)
                .header("Mcp-Protocol-Version", "2025-11-25")
                .contentType("application/json")
                .accept("application/json, text/event-stream")
                .body("""
                        {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{
                          "name":"list_mocks","arguments":{}}}
                        """)
        .when()
                .post("/__fleet/mcp")
        .then()
                .statusCode(200)
                .body(containsString("\"isError\":true"))
                .body(containsString("\"code\":\"FLEET_API_ERROR\""))
                .body(containsString("\"retryable\":false"));
    }

    private String initializeSession() {
        return given()
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
    }

    private void initializeClient(String sessionId) {
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

    private JsonNode tool(JsonNode tools, String name) {
        for (JsonNode tool : tools) {
            if (name.equals(tool.path("name").asText())) {
                return tool;
            }
        }
        throw new AssertionError("Tool not found: " + name);
    }

    private List<String> textValues(JsonNode values) {
        List<String> result = new ArrayList<>();
        values.forEach(value -> result.add(value.asText()));
        return result;
    }
}
