package com.github.letsrokk;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@QuarkusTest
class InternalMockResourceTest {

    @InjectMock
    PodManager podManager;

    @Test
    void resolvesUpstreamForMockId() {
        when(podManager.getUpstreamBaseUrl("demo")).thenReturn("http://10.1.2.3:8080");

        given()
        .when()
                .post("/internal/mocks/demo/upstream")
        .then()
                .statusCode(200)
                .body("baseUrl", is("http://10.1.2.3:8080"));

        verify(podManager).getUpstreamBaseUrl("demo");
    }
}
