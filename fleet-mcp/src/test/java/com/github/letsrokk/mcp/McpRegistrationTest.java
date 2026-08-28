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
            "list_mocks", "list_mock_configs", "get_mock_config", "list_option_definitions", "update_mock_config",
            "delete_mock_config", "start_mock", "stop_mock",
            "list_stubs", "list_unmatched_stubs", "get_stub", "create_stub", "update_stub", "delete_stub",
            "persist_stub", "unpersist_stub", "send_request", "find_requests", "count_requests",
            "list_unmatched_requests", "get_near_misses", "reset_request_journal", "start_recording",
            "get_recording_status", "stop_recording", "snapshot_requests", "list_body_files", "get_body_file",
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

        assertTrue(tool(tools, "create_stub").path("inputSchema").path("properties").path("mapping")
                .path("examples").isArray());
        assertTrue(tool(tools, "find_requests").path("inputSchema").path("properties").path("requestPattern")
                .path("examples").isArray());
        assertTrue(tool(tools, "start_recording").path("inputSchema").path("properties").path("recording")
                .path("examples").isArray());
        assertTrue(tool(tools, "snapshot_requests").path("inputSchema").path("properties").path("snapshot")
                .path("examples").isArray());

        for (String toolName : List.of("send_request", "put_body_file")) {
            JsonNode body = tool(tools, toolName).path("inputSchema").path("properties").path("body");
            assertEquals("object", body.path("type").asText(), toolName);
            assertEquals(List.of("utf8", "base64"), textValues(body.path("properties").path("encoding").path("enum")));
            assertEquals(Set.of("encoding", "data", "sizeBytes"), Set.copyOf(textValues(body.path("required"))));
            assertFalse(tool(tools, toolName).path("inputSchema").path("properties").has("bodyBase64"));
            assertFalse(tool(tools, toolName).path("inputSchema").path("properties").has("contentBase64"));
        }
    }

    @Test
    void everyToolPublishesTypedSuccessAndStrictErrorOutputSchema() throws Exception {
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
        assertEquals(32, tools.size());
        for (JsonNode tool : tools) {
            JsonNode schema = tool.path("outputSchema");
            assertEquals(2, schema.path("oneOf").size(), tool.path("name").asText() + ": " + schema);
            JsonNode success = schema.path("oneOf").get(0);
            JsonNode failure = schema.path("oneOf").get(1);
            assertEquals("object", success.path("type").asText(), tool.path("name").asText());
            assertFalse(success.path("properties").isEmpty(), tool.path("name").asText());
            assertFalse(success.path("additionalProperties").asBoolean(true), tool.path("name").asText());
            assertEquals(List.of("error"), textValues(failure.path("required")), tool.path("name").asText());
            JsonNode error = failure.path("properties").path("error");
            assertEquals(Set.of("code", "message", "retryable", "stateMayHaveChanged", "details"),
                    Set.copyOf(textValues(error.path("required"))), tool.path("name").asText());
            assertFalse(error.path("additionalProperties").asBoolean(true), tool.path("name").asText());
            assertFalse(failure.path("additionalProperties").asBoolean(true), tool.path("name").asText());
        }

        JsonNode getStub = tool(tools, "get_stub").path("outputSchema").path("oneOf").get(0);
        assertEquals(Set.of("mockId", "stub"), Set.copyOf(textValues(getStub.path("required"))));
        assertTrue(getStub.path("properties").path("stub").path("additionalProperties").asBoolean());
        JsonNode sendRequest = tool(tools, "send_request").path("outputSchema").path("oneOf").get(0);
        assertEquals("object", sendRequest.path("properties").path("response").path("type").asText());
        assertEquals(List.of("utf8", "base64"), textValues(sendRequest.path("properties").path("response")
                .path("properties").path("body").path("properties").path("encoding").path("enum")));

        for (String toolName : List.of(
                "list_mock_configs", "get_mock_config", "update_mock_config", "delete_mock_config")) {
            JsonNode resourceVersion = tool(tools, toolName).path("outputSchema").path("oneOf").get(0)
                    .path("properties").path("resourceVersion");
            assertEquals(List.of("string", "null"), textValues(resourceVersion.path("type")), toolName);
        }

        JsonNode stopMock = tool(tools, "stop_mock").path("outputSchema").path("oneOf").get(0);
        assertEquals(List.of("STOPPED"), textValues(stopMock.path("properties").path("status").path("enum")));
        assertEquals(0, stopMock.path("properties").path("retryAfterMs").path("minimum").asInt(-1));
        assertEquals(Set.of("mockId", "status", "podName", "message", "retryAfterMs"),
                Set.copyOf(textValues(stopMock.path("required"))));
    }

    @Test
    void getMockConfigRequiresOnlyTheSingularMockIdArgument() {
        var arguments = toolManager.getTool("get_mock_config").arguments();

        assertEquals(1, arguments.size());
        assertEquals("mockId", arguments.getFirst().name());
        assertEquals("java.lang.String", arguments.getFirst().type().getTypeName());
        assertTrue(arguments.getFirst().required());
    }

    @Test
    void listMockConfigsIsReadOnlyWithOptionalPaginationArguments() {
        var tool = toolManager.getTool("list_mock_configs");

        assertEquals(List.of("limit", "offset"), tool.arguments().stream().map(argument -> argument.name()).toList());
        assertTrue(tool.arguments().stream().noneMatch(argument -> argument.required()));
        assertTrue(tool.annotations().orElseThrow().readOnlyHint());
        assertFalse(tool.annotations().orElseThrow().destructiveHint());
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
