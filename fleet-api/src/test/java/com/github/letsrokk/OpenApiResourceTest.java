package com.github.letsrokk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class OpenApiResourceTest {

    @Test
    void servesOpenApiAsJsonWhenRequested() {
        given()
                .queryParam("format", "json")
        .when()
                .get("/__fleet/api/openapi")
        .then()
                .statusCode(200)
                .contentType(startsWith("application/json"))
                .body(containsString("\"openapi\""));
    }

    @Test
    void mapsMappingOperationFailuresToJsonApiError() {
        given()
        .when()
                .get("/__fleet/api/mappings/INVALID/tree")
        .then()
                .statusCode(503)
                .contentType(startsWith("application/json"))
                .body("code", equalTo("MAPPINGS_STORAGE_DISABLED"))
                .body("message", equalTo("Persistent mappings storage is disabled."))
                .body("retryable", equalTo(false))
                .body("stateMayHaveChanged", equalTo(false))
                .body("details.size()", equalTo(0));
    }

    @Test
    void documentsMappingOperationFailuresAsJsonApiErrors() throws Exception {
        String document = given()
                .queryParam("format", "json")
        .when()
                .get("/__fleet/api/openapi")
        .then()
                .statusCode(200)
                .extract().asString();
        JsonNode root = new ObjectMapper().readTree(document);

        assertApiError(root, "/__fleet/api/mappings/{mockId}/tree", "get", "400", "404", "503");
        assertApiError(root, "/__fleet/api/mappings/{mockId}", "delete", "400", "503");
        assertApiError(root, "/__fleet/api/mappings/{mockId}/files", "get", "400", "404", "503");
        assertApiError(root, "/__fleet/api/mappings/{mockId}/files", "delete", "400", "503");
    }

    private void assertApiError(JsonNode root, String path, String operation, String... statuses) {
        for (String status : statuses) {
            JsonNode response = root.path("paths").path(path).path(operation).path("responses").path(status);
            if (response.has("$ref")) {
                assertEquals("#/components/responses/ApiError", response.path("$ref").asText(),
                        path + " " + operation + " " + status);
            } else {
                JsonNode schema = response.path("content").path("application/json").path("schema");
                assertEquals("#/components/schemas/ApiError", schema.path("$ref").asText(),
                        path + " " + operation + " " + status);
            }
            assertTrue(response.path("content").path("text/plain").isMissingNode(),
                    path + " " + operation + " " + status);
        }
    }
}
