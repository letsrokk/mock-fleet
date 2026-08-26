package com.github.letsrokk;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@QuarkusTest
class FleetResourceTest {

    @InjectMock
    PodManager podManager;

    @InjectMock
    PodState podState;

    @Test
    void listsActiveMocks() {
        when(podManager.listMocks()).thenReturn(List.of(new PodManager.MockPodStatus(
                "demo", "mock-fleet-demo-1", MockLifecycleStatus.STARTING, null)));

        given()
        .when()
                .get("/__fleet/api/mocks")
        .then()
                .statusCode(200)
                .body("[0].mockId", is("demo"))
                .body("[0].podName", is("mock-fleet-demo-1"))
                .body("[0].status", is("STARTING"));

        verify(podManager).listMocks();
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

    @Test
    void streamsInitialAndChangedActiveMockSnapshots() throws Exception {
        BroadcastProcessor<Long> changes = BroadcastProcessor.create();
        when(podState.podChanges()).thenReturn(changes);
        when(podManager.listMocks())
                .thenReturn(List.of(new PodManager.MockPodStatus(
                        "alpha", "mock-fleet-alpha-1", MockLifecycleStatus.STARTING, null)))
                .thenReturn(List.of(new PodManager.MockPodStatus(
                        "beta", "mock-fleet-beta-1", MockLifecycleStatus.FAILED, "image pull failed")));

        FleetResource resource = new FleetResource();
        resource.podManager = podManager;
        resource.podState = podState;
        var snapshots = resource.streamActiveMocks().select().first(2).collect().asList()
                .subscribeAsCompletionStage();
        changes.onNext(1L);

        List<List<FleetResource.MockRow>> result = snapshots.toCompletableFuture().get(1, TimeUnit.SECONDS);

        assertEquals(List.of(
                List.of(new FleetResource.MockRow(
                        "alpha", "mock-fleet-alpha-1", MockLifecycleStatus.STARTING, null)),
                List.of(new FleetResource.MockRow(
                        "beta", "mock-fleet-beta-1", MockLifecycleStatus.FAILED, "image pull failed"))), result);
    }
}
