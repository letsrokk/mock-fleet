package com.github.letsrokk;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;

@QuarkusTest
class VersionResourceTest {

    @Test
    void returnsProxyVersion() {
        given()
        .when()
                .get("/__fleet/proxy/version")
        .then()
                .statusCode(200)
                .body("component", is("proxy"))
                .body("version", is("1.2.0-SNAPSHOT"));
    }
}
