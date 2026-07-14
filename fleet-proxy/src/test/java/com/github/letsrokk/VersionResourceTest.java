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
    void returnsProxyVersion() {
        given()
        .when()
                .get("/__fleet/proxy/version")
        .then()
                .statusCode(200)
                .body("component", is("proxy"))
                .body("version", is(version));
    }
}
