package com.github.letsrokk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        assertApiError(root, "/__fleet/api/mappings/{mockId}", "delete", "400", "404", "503");
        assertApiError(root, "/__fleet/api/mappings/{mockId}/files", "get", "400", "404", "503");
        assertApiError(root, "/__fleet/api/mappings/{mockId}/files", "delete", "400", "503");
    }

    @Test
    void documentsEveryMappingFileResponseMediaTypeAsBinary() throws Exception {
        String document = given()
                .queryParam("format", "json")
        .when()
                .get("/__fleet/api/openapi")
        .then()
                .statusCode(200)
                .extract().asString();
        JsonNode content = new ObjectMapper().readTree(document)
                .path("paths")
                .path("/__fleet/api/mappings/{mockId}/files")
                .path("get")
                .path("responses")
                .path("200")
                .path("content");
        Set<String> actualMediaTypes = new HashSet<>();
        content.fieldNames().forEachRemaining(actualMediaTypes::add);
        Set<String> expectedMediaTypes = Set.of(
                "application/json",
                "application/xml",
                "application/pdf",
                "text/plain",
                "application/octet-stream");

        assertEquals(expectedMediaTypes, actualMediaTypes);
        for (String mediaType : expectedMediaTypes) {
            JsonNode schema = content.path(mediaType).path("schema");
            assertEquals("string", schema.path("type").asText(), mediaType);
            assertEquals("binary", schema.path("format").asText(), mediaType);
        }
    }

    @Test
    void documentsDefaultedConfigUpdateFieldsAsOptional() throws Exception {
        String document = given()
                .queryParam("format", "json")
        .when()
                .get("/__fleet/api/openapi")
        .then()
                .statusCode(200)
                .extract().asString();
        JsonNode schema = new ObjectMapper().readTree(document)
                .path("components")
                .path("schemas")
                .path("ConfigUpdateRequest");

        assertTrue(schema.path("required").isMissingNode() || schema.path("required").isEmpty());
        assertTrue(schema.path("properties").has("resourceVersion"));
        assertTrue(schema.path("properties").has("options"));
        assertTrue(schema.path("properties").has("resources"));
        assertTrue(schema.path("properties").has("applyMode"));
    }

    @Test
    void documentsDefaultedResourceMapsAsOptional() throws Exception {
        String document = given()
                .queryParam("format", "json")
        .when()
                .get("/__fleet/api/openapi")
        .then()
                .statusCode(200)
                .extract().asString();
        JsonNode schema = new ObjectMapper().readTree(document)
                .path("components")
                .path("schemas")
                .path("ResourceData");

        assertTrue(schema.path("required").isMissingNode() || schema.path("required").isEmpty());
        assertTrue(schema.path("properties").has("requests"));
        assertTrue(schema.path("properties").has("limits"));
    }

    @Test
    void documentsConfiguredWireMockVersionMetadata() throws Exception {
        String document = given()
                .queryParam("format", "json")
        .when()
                .get("/__fleet/api/openapi")
        .then()
                .statusCode(200)
                .extract().asString();
        JsonNode schemas = new ObjectMapper().readTree(document).path("components").path("schemas");

        JsonNode config = schemas.path("ConfigView");
        assertTrue(config.path("required").toString().contains("wireMock"));
        assertEquals("#/components/schemas/WireMockVersionView",
                config.path("properties").path("wireMock").path("$ref").asText());

    }

    @Test
    void documentsTheVersionedPublicOptionCatalog() throws Exception {
        JsonNode root = new ObjectMapper().readTree(given()
                .queryParam("format", "json")
        .when()
                .get("/__fleet/api/openapi")
        .then()
                .statusCode(200)
                .extract().asString());

        JsonNode operation = root.path("paths").path("/__fleet/api/config/options").path("get");
        assertEquals("getWireMockOptionCatalog", operation.path("operationId").asText());
        assertEquals("version", operation.path("parameters").get(0).path("name").asText());
        assertEquals("#/components/schemas/OptionCatalogView", operation.path("responses").path("200")
                .path("content").path("application/json").path("schema").path("$ref").asText());
        assertEquals("#/components/responses/ApiError", operation.path("responses").path("400")
                .path("$ref").asText());

        JsonNode config = root.path("components").path("schemas").path("ConfigView");
        assertFalse(config.path("required").toString().contains("options"));
        assertFalse(config.path("properties").has("options"));

        JsonNode catalog = root.path("components").path("schemas").path("OptionCatalogView");
        assertEquals("wireMockVersion", catalog.path("required").get(0).asText());
        assertEquals("catalogStatus", catalog.path("required").get(1).asText());
        assertEquals("options", catalog.path("required").get(2).asText());

        JsonNode option = root.path("components").path("schemas").path("PublicOptionDefinition");
        assertFalse(option.path("properties").has("available"));
        assertFalse(option.path("properties").has("compatibility"));
        assertFalse(option.path("properties").has("versionRanges"));
        assertTrue(option.path("properties").has("minimum"));
        assertTrue(option.path("properties").has("maximum"));
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
