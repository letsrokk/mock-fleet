package com.github.letsrokk;

import com.hazelcast.config.Config;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MockCapacityTest {

    private HazelcastInstance hazelcast;
    private HazelcastInstance secondHazelcast;

    @AfterEach
    void closeHazelcast() {
        if (secondHazelcast != null) {
            secondHazelcast.getLifecycleService().terminate();
        }
        if (hazelcast != null) {
            hazelcast.getLifecycleService().terminate();
        }
    }

    @Test
    void concurrentClaimsAcrossCapacityInstancesCannotExceedTheActiveLimit() throws Exception {
        String clusterName = "mock-capacity-joined-" + System.nanoTime();
        int firstMemberPort = freePort();
        int secondMemberPort = freePort();
        List<String> members = List.of(
                "127.0.0.1:" + firstMemberPort,
                "127.0.0.1:" + secondMemberPort);
        hazelcast = newJoinedHazelcast(clusterName, firstMemberPort, members);
        secondHazelcast = newJoinedHazelcast(clusterName, secondMemberPort, members);
        assertEquals(2, hazelcast.getCluster().getMembers().size());
        assertEquals(2, secondHazelcast.getCluster().getMembers().size());
        MockCapacity firstReplica = new MockCapacity(hazelcast, config(2, 2, 2));
        MockCapacity secondReplica = new MockCapacity(secondHazelcast, config(2, 2, 2));
        CountDownLatch ready = new CountDownLatch(3);
        CountDownLatch claim = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger exhausted = new AtomicInteger();
        ExecutorService callers = Executors.newFixedThreadPool(3);
        List<Future<?>> results = new ArrayList<>();

        try {
            for (int index = 0; index < 3; index++) {
                String mockId = "mock-" + index;
                MockCapacity replica = index % 2 == 0 ? firstReplica : secondReplica;
                results.add(callers.submit(() -> {
                    ready.countDown();
                    assertTrue(claim.await(1, TimeUnit.SECONDS));
                    try {
                        reserve(replica, hazelcast, mockId, "attempt-" + mockId);
                        accepted.incrementAndGet();
                    } catch (MockCapacity.CapacityExceededException expected) {
                        exhausted.incrementAndGet();
                    }
                    return null;
                }));
            }
            assertTrue(ready.await(1, TimeUnit.SECONDS));
            claim.countDown();
            for (Future<?> result : results) {
                result.get(2, TimeUnit.SECONDS);
            }

            assertEquals(2, accepted.get());
            assertEquals(1, exhausted.get());
            assertEquals(2, firstReplica.activeCount());
        } finally {
            claim.countDown();
            callers.shutdownNow();
        }
    }

    @Test
    void reserveAndReleaseAreIdempotentByMockAndAttempt() {
        hazelcast = newHazelcast();
        MockCapacity capacity = new MockCapacity(hazelcast, config(1, 1, 1));

        reserve(capacity, hazelcast, "demo", "attempt-1");
        reserve(capacity, hazelcast, "demo", "attempt-1");
        capacity.release("demo", "wrong-attempt");

        assertThrows(MockCapacity.CapacityExceededException.class,
                () -> capacity.reserve("other", "attempt-2"));
        capacity.release("demo", "attempt-1");
        capacity.release("demo", "attempt-1");
        reserve(capacity, hazelcast, "other", "attempt-2");
        assertEquals(1, capacity.activeCount());
    }

    @Test
    void aReplacementAttemptKeepsOneSlotAndCannotBeReleasedByItsPredecessor() {
        hazelcast = newHazelcast();
        MockCapacity capacity = new MockCapacity(hazelcast, config(1, 1, 1));

        reserve(capacity, hazelcast, "demo", "attempt-1");
        reserve(capacity, hazelcast, "demo", "attempt-2");
        capacity.release("demo", "attempt-1");

        assertThrows(MockCapacity.CapacityExceededException.class,
                () -> capacity.reserve("other", "attempt-3"));
        capacity.release("demo", "attempt-2");
        reserve(capacity, hazelcast, "other", "attempt-3");
    }

    @Test
    void activeAccountingRequiresStartOwnershipAndIncludesRunningState() {
        hazelcast = newHazelcast();
        MockCapacity capacity = new MockCapacity(hazelcast, config(2, 1, 1));
        hazelcast.<String, MockPodLifecycle>getMap(HazelcastMemberConfig.POD_LIFECYCLE_MAP_NAME)
                .put("starting", MockPodLifecycle.starting("attempt-starting", null, System.currentTimeMillis()));
        hazelcast.<String, MockPodRef>getMap(HazelcastMemberConfig.POD_MAP_NAME)
                .put("running", new MockPodRef("mock-fleet-running-1", "10.0.0.1"));

        assertEquals(1, capacity.activeCount());
        reserve(capacity, hazelcast, "third", "attempt-third");
        assertEquals(2, capacity.activeCount());
    }

    @Test
    void startupReconciliationDropsReservationsWithoutACurrentStartingAttempt() {
        hazelcast = newHazelcast();
        MockCapacity capacity = new MockCapacity(hazelcast, config(2, 1, 1));
        hazelcast.<String, String>getMap(MockCapacity.RESERVATION_MAP_NAME)
                .put("stale", "attempt-stale");
        hazelcast.<String, String>getMap(MockCapacity.RESERVATION_MAP_NAME)
                .put("current", "attempt-current");
        hazelcast.<String, MockPodLifecycle>getMap(HazelcastMemberConfig.POD_LIFECYCLE_MAP_NAME)
                .put("current", MockPodLifecycle.starting(
                        "attempt-current", null, System.currentTimeMillis()));

        capacity.reconcile();

        assertEquals(null, hazelcast.<String, String>getMap(MockCapacity.RESERVATION_MAP_NAME).get("stale"));
        assertEquals(null,
                hazelcast.<String, String>getMap(MockCapacity.RESERVATION_MAP_NAME).get("current"));
        assertEquals(MockLifecycleStatus.FAILED,
                hazelcast.<String, MockPodLifecycle>getMap(
                        HazelcastMemberConfig.POD_LIFECYCLE_MAP_NAME).get("current").status());
    }

    @Test
    void startupReconciliationRejectsAnUnownedAttemptEvenWhenItsLifecycleIsFresh() {
        hazelcast = newHazelcast();
        MockCapacity capacity = new MockCapacity(hazelcast,
                config(1, 1, 1, Duration.ofMillis(5)));
        var reservations = hazelcast.<String, String>getMap(MockCapacity.RESERVATION_MAP_NAME);
        var lifecycles = hazelcast.<String, MockPodLifecycle>getMap(
                HazelcastMemberConfig.POD_LIFECYCLE_MAP_NAME);
        long startedAt = System.currentTimeMillis();
        reservations.put("alpha", "attempt-alpha");
        lifecycles.put("alpha", MockPodLifecycle.starting("attempt-alpha", null, startedAt));

        capacity.reconcile();
        reserve(capacity, hazelcast, "beta", "attempt-beta");

        assertEquals(null, reservations.get("alpha"));
        assertEquals("attempt-beta", reservations.get("beta"));
    }

    @Test
    void renewedQueuedAttemptSurvivesItsNominalLifecycleLeaseAndCanComplete() {
        hazelcast = newHazelcast();
        MockCapacity capacity = new MockCapacity(hazelcast,
                config(1, 1, 1, Duration.ofMillis(5)));
        var lifecycles = hazelcast.<String, MockPodLifecycle>getMap(
                HazelcastMemberConfig.POD_LIFECYCLE_MAP_NAME);
        long expiredLifecycleStart = System.currentTimeMillis() - 20L;
        capacity.reserve("alpha", "attempt-alpha", () -> lifecycles.put("alpha",
                MockPodLifecycle.starting("attempt-alpha", null, expiredLifecycleStart)));

        assertTrue(capacity.renew("alpha", "attempt-alpha"));

        assertThrows(MockCapacity.CapacityExceededException.class,
                () -> capacity.reserve("beta", "attempt-beta"));
        assertTrue(capacity.complete("alpha", "attempt-alpha", () -> lifecycles.put("alpha",
                MockPodLifecycle.running("attempt-alpha", "mock-fleet-alpha-attempt-alpha"))));
    }

    @Test
    void activeCountRemovesExpiredReservationsWithoutAReplicaRestart() {
        hazelcast = newHazelcast();
        MockCapacity capacity = new MockCapacity(hazelcast,
                config(1, 1, 1, Duration.ofMillis(5)));
        var reservations = hazelcast.<String, String>getMap(MockCapacity.RESERVATION_MAP_NAME);
        var lifecycles = hazelcast.<String, MockPodLifecycle>getMap(
                HazelcastMemberConfig.POD_LIFECYCLE_MAP_NAME);
        long startedAt = System.currentTimeMillis();
        capacity.reserve("alpha", "attempt-alpha", () -> lifecycles.put("alpha",
                MockPodLifecycle.starting("attempt-alpha", null, startedAt)));
        awaitAfter(startedAt + 10L);

        assertEquals(0, capacity.activeCount());
        assertEquals(null, reservations.get("alpha"));
        assertEquals(MockLifecycleStatus.FAILED, lifecycles.get("alpha").status());
    }

    @Test
    void liveReconciliationRemovesALifecycleLessCrashReservation() {
        hazelcast = newHazelcast();
        MockCapacity capacity = new MockCapacity(hazelcast, config(1, 1, 1));
        var reservations = hazelcast.<String, String>getMap(MockCapacity.RESERVATION_MAP_NAME);

        capacity.reserve("alpha", "attempt-alpha");

        assertEquals(0, capacity.activeCount());
        assertEquals(null, reservations.get("alpha"));
    }

    @Test
    void reconciledStaleAttemptCannotCompleteAfterAnotherMockTakesItsSlot() {
        hazelcast = newHazelcast();
        MockCapacity capacity = new MockCapacity(hazelcast, config(1, 1, 1));
        var reservations = hazelcast.<String, String>getMap(MockCapacity.RESERVATION_MAP_NAME);
        var lifecycles = hazelcast.<String, MockPodLifecycle>getMap(
                HazelcastMemberConfig.POD_LIFECYCLE_MAP_NAME);
        var pods = hazelcast.<String, MockPodRef>getMap(HazelcastMemberConfig.POD_MAP_NAME);
        reservations.put("alpha", "attempt-alpha");
        lifecycles.put("alpha", MockPodLifecycle.starting(
                "attempt-alpha", "mock-fleet-alpha-attempt-alpha",
                System.currentTimeMillis() - Duration.ofMinutes(3).toMillis()));

        capacity.reconcile();
        reserve(capacity, hazelcast, "beta", "attempt-beta");
        boolean completed = capacity.complete("alpha", "attempt-alpha", () -> {
            pods.put("alpha", new MockPodRef("mock-fleet-alpha-attempt-alpha", "10.0.0.1"));
            lifecycles.put("alpha", MockPodLifecycle.running(
                    "attempt-alpha", "mock-fleet-alpha-attempt-alpha"));
        });

        assertEquals(false, completed);
        assertEquals(null, pods.get("alpha"));
        assertEquals(MockLifecycleStatus.FAILED, lifecycles.get("alpha").status());
        assertEquals(1, capacity.activeCount());
    }

    @Test
    void reconciliationCannotObserveTheGapBetweenReservationAndStartingPublication() throws Exception {
        hazelcast = newHazelcast();
        MockCapacity capacity = new MockCapacity(hazelcast, config(1, 1, 1));
        CountDownLatch publisherEntered = new CountDownLatch(1);
        CountDownLatch publishStarting = new CountDownLatch(1);
        CountDownLatch reconciliationCalled = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);

        try {
            Future<?> reservation = workers.submit(() -> capacity.reserve("demo", "attempt-1", () -> {
                publisherEntered.countDown();
                try {
                    assertTrue(publishStarting.await(1, TimeUnit.SECONDS));
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(error);
                }
                hazelcast.<String, MockPodLifecycle>getMap(HazelcastMemberConfig.POD_LIFECYCLE_MAP_NAME)
                        .put("demo", MockPodLifecycle.starting(
                                "attempt-1", null, System.currentTimeMillis()));
            }));
            assertTrue(publisherEntered.await(1, TimeUnit.SECONDS));
            Future<?> reconciliation = workers.submit(() -> {
                reconciliationCalled.countDown();
                capacity.reconcile();
            });
            assertTrue(reconciliationCalled.await(1, TimeUnit.SECONDS));
            assertEquals(false, reconciliation.isDone());

            publishStarting.countDown();
            reservation.get(1, TimeUnit.SECONDS);
            reconciliation.get(1, TimeUnit.SECONDS);

            assertEquals("attempt-1",
                    hazelcast.<String, String>getMap(MockCapacity.RESERVATION_MAP_NAME).get("demo"));
        } finally {
            publishStarting.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    void rejectsNonPositiveAndInconsistentLimits() {
        hazelcast = newHazelcast();

        assertThrows(IllegalArgumentException.class,
                () -> new MockCapacity(hazelcast, config(0, 1, 1)));
        assertThrows(IllegalArgumentException.class,
                () -> new MockCapacity(hazelcast, config(2, 0, 1)));
        assertThrows(IllegalArgumentException.class,
                () -> new MockCapacity(hazelcast, config(2, 1, 0)));
        assertThrows(IllegalArgumentException.class,
                () -> new MockCapacity(hazelcast, config(2, 3, 1)));
    }

    private HazelcastInstance newHazelcast() {
        Config config = new Config();
        config.setClusterName("mock-capacity-" + System.nanoTime());
        config.setProperty("hazelcast.logging.type", "none");
        config.setProperty("hazelcast.phone.home.enabled", "false");
        config.getNetworkConfig().setPort(0).setPortAutoIncrement(true);
        config.getNetworkConfig().getJoin().getAutoDetectionConfig().setEnabled(false);
        config.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false);
        config.getNetworkConfig().getJoin().getTcpIpConfig().setEnabled(false);
        return Hazelcast.newHazelcastInstance(config);
    }

    private HazelcastInstance newJoinedHazelcast(String clusterName, int port, List<String> members) {
        Config config = new Config();
        config.setClusterName(clusterName);
        config.setProperty("hazelcast.logging.type", "none");
        config.setProperty("hazelcast.phone.home.enabled", "false");
        config.getNetworkConfig().setPort(port).setPortAutoIncrement(false);
        config.getNetworkConfig().getJoin().getAutoDetectionConfig().setEnabled(false);
        config.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false);
        var tcpIp = config.getNetworkConfig().getJoin().getTcpIpConfig().setEnabled(true);
        for (String member : members) {
            tcpIp.addMember(member);
        }
        return Hazelcast.newHazelcastInstance(config);
    }

    private int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException error) {
            throw new IllegalStateException("Cannot allocate a local test port.", error);
        }
    }

    private void reserve(MockCapacity capacity, HazelcastInstance instance,
                         String mockId, String attemptId) {
        capacity.reserve(mockId, attemptId, () -> instance
                .<String, MockPodLifecycle>getMap(HazelcastMemberConfig.POD_LIFECYCLE_MAP_NAME)
                .put(mockId, MockPodLifecycle.starting(
                        attemptId, null, System.currentTimeMillis())));
    }

    private MockFleetConfig config(int maxActive, int maxConcurrent, int queuedCapacity) {
        return config(maxActive, maxConcurrent, queuedCapacity, Duration.ofMinutes(1));
    }

    private MockFleetConfig config(int maxActive, int maxConcurrent, int queuedCapacity,
                                   Duration podCreationTimeout) {
        MockFleetConfig config = mock(MockFleetConfig.class);
        when(config.maxActiveMocks()).thenReturn(maxActive);
        when(config.maxConcurrentStarts()).thenReturn(maxConcurrent);
        when(config.queuedStartCapacity()).thenReturn(queuedCapacity);
        when(config.podCreationTimeout()).thenReturn(podCreationTimeout);
        return config;
    }

    private void awaitAfter(long epochMillis) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (System.currentTimeMillis() <= epochMillis) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("Clock did not advance past the startup lease deadline.");
            }
            Thread.onSpinWait();
        }
    }
}
