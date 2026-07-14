package com.github.letsrokk;

import io.quarkus.test.junit.QuarkusTest;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;

@QuarkusTest
class VersionResourceTest {

    @ConfigProperty(name = "quarkus.application.version")
    String version;

    @Test
    void returnsApiVersion() {
        given()
        .when()
                .get("/__fleet/api/version")
        .then()
                .statusCode(200)
                .body("component", is("api"))
                .body("version", is(version));
    }
}
