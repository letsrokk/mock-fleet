package com.github.letsrokk;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@QuarkusTest
class FleetResourceTest {

    @InjectMock
    PodManager podManager;

    @Test
    void listsActiveMocks() {
        when(podManager.listActiveMocks()).thenReturn(List.of(new PodManager.ActiveMockPod("demo", "mock-fleet-demo-1")));

        given()
        .when()
                .get("/__fleet/api/mocks")
        .then()
                .statusCode(200)
                .body("[0].mockId", is("demo"))
                .body("[0].podName", is("mock-fleet-demo-1"));

        verify(podManager).listActiveMocks();
    }

    @Test
    void deletesActiveMock() {
        when(podManager.deleteMock("demo")).thenReturn(PodManager.DeleteMockResult.DELETED);

        given()
        .when()
                .delete("/__fleet/api/mocks/demo")
        .then()
                .statusCode(204);

        verify(podManager).deleteMock("demo");
    }
}
