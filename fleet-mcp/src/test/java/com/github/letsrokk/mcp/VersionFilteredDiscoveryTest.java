package com.github.letsrokk.mcp;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
class VersionFilteredDiscoveryTest {

    @Test
    void toolsListRetainsOperationsForMixedVersionMocks() {
        String sessionId = given()
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

        given()
                .header("Mcp-Session-Id", sessionId)
                .header("Mcp-Protocol-Version", "2025-11-25")
                .contentType("application/json")
                .accept("application/json, text/event-stream")
                .body("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}")
        .when()
                .post("/__fleet/mcp")
        .then()
                .statusCode(200)
                .body(containsString("list_stubs"))
                .body(containsString("get_body_file"))
                .body(containsString("list_unmatched_stubs"));
    }
}
