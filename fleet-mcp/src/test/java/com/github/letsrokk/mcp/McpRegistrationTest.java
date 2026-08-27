package com.github.letsrokk.mcp;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkiverse.mcp.server.ToolManager;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

@QuarkusTest
class McpRegistrationTest {

    private static final Set<String> EXPECTED_TOOLS = Set.of(
            "list_mocks", "list_mock_configs", "get_mock_config", "update_mock_config", "delete_mock_config", "stop_mock",
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
    void getMockConfigRequiresOnlyThePluralMockIdsArgument() {
        var arguments = toolManager.getTool("get_mock_config").arguments();

        assertEquals(1, arguments.size());
        assertEquals("mockIds", arguments.getFirst().name());
        assertEquals("java.util.List<java.lang.String>", arguments.getFirst().type().getTypeName());
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
}
