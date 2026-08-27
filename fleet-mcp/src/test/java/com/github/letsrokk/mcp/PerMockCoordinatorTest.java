package com.github.letsrokk.mcp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class PerMockCoordinatorTest {

    @Test
    void serializesOneMockWithoutBlockingAnotherMock() throws Exception {
        var coordinator = new PerMockCoordinator();
        var firstEntered = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var sameMockEntered = new CountDownLatch(1);
        var otherMockEntered = new CountDownLatch(1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> coordinator.serialized("orders", () -> {
                firstEntered.countDown();
                await(releaseFirst);
                return null;
            }));
            assertTrue(firstEntered.await(1, TimeUnit.SECONDS));

            var sameMock = executor.submit(() -> coordinator.serialized("orders", () -> {
                sameMockEntered.countDown();
                return null;
            }));
            var otherMock = executor.submit(() -> coordinator.serialized("customers", () -> {
                otherMockEntered.countDown();
                return null;
            }));

            assertTrue(otherMockEntered.await(1, TimeUnit.SECONDS));
            assertFalse(sameMockEntered.await(100, TimeUnit.MILLISECONDS));
            releaseFirst.countDown();
            assertTrue(sameMockEntered.await(1, TimeUnit.SECONDS));
            first.get(1, TimeUnit.SECONDS);
            sameMock.get(1, TimeUnit.SECONDS);
            otherMock.get(1, TimeUnit.SECONDS);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
