package com.github.letsrokk;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.startsWith;

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
}
