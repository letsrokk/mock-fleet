package com.github.letsrokk;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
        when(podManager.getUpstreamBaseUrlAsync("demo"))
                .thenReturn(CompletableFuture.completedFuture("http://10.1.2.3:8080"));

        given()
        .when()
                .post("/internal/mocks/demo/upstream")
        .then()
                .statusCode(200)
                .body("baseUrl", is("http://10.1.2.3:8080"));

        verify(podManager).getUpstreamBaseUrlAsync("demo");
    }

    @Test
    void completesTheRestRequestAsynchronouslyWhenProvisioningFinishes() throws Exception {
        CompletableFuture<String> upstream = new CompletableFuture<>();
        CountDownLatch resourceCalled = new CountDownLatch(1);
        when(podManager.getUpstreamBaseUrlAsync("demo")).thenAnswer(invocation -> {
            resourceCalled.countDown();
            return upstream;
        });
        ExecutorService caller = Executors.newSingleThreadExecutor();

        try {
            CompletableFuture<String> response = CompletableFuture.supplyAsync(() -> given()
                    .when().post("/internal/mocks/demo/upstream")
                    .then().statusCode(200)
                    .extract().path("baseUrl"), caller);
            org.junit.jupiter.api.Assertions.assertTrue(resourceCalled.await(1, TimeUnit.SECONDS));
            org.junit.jupiter.api.Assertions.assertFalse(response.isDone());

            upstream.complete("http://10.1.2.3:8080");

            org.junit.jupiter.api.Assertions.assertEquals(
                    "http://10.1.2.3:8080", response.get(1, TimeUnit.SECONDS));
        } finally {
            upstream.completeExceptionally(new IllegalStateException("test cleanup"));
            caller.shutdownNow();
        }
    }
}
