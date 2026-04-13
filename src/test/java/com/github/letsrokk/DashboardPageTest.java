package com.github.letsrokk;

import io.quarkiverse.quinoa.testing.QuinoaTestProfiles;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;

@QuarkusTest
@TestProfile(QuinoaTestProfiles.Enable.class)
class DashboardPageTest {

    @Test
    void servesDashboardFromFleetNamespace() {
        given()
            .header("Host", "mock-fleet.localhost")
        .when()
            .get("/__fleet/")
        .then()
            .statusCode(200)
            .body(containsString("<div id=\"root\"></div>"))
            .body(containsString("Mock Fleet"))
            .body(containsString("/__fleet/assets/"));
    }
}
