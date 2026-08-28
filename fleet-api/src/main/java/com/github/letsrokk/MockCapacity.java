package com.github.letsrokk;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@ApplicationScoped
public class MockCapacity {

    static final String RESERVATION_MAP_NAME = "mock-capacity-reservation-map";
    private static final String CAPACITY_LOCK_KEY = "__mock-fleet-capacity-lock__";

    private final IMap<String, String> reservations;
    private final IMap<String, MockPodRef> pods;
    private final IMap<String, MockPodLifecycle> lifecycles;
    private final int maxActiveMocks;
    private final long startupLeaseMillis;

    @Inject
    public MockCapacity(HazelcastInstance hazelcastInstance, MockFleetConfig config) {
        validate(config);
        this.reservations = hazelcastInstance.getMap(RESERVATION_MAP_NAME);
        this.pods = hazelcastInstance.getMap(HazelcastMemberConfig.POD_MAP_NAME);
        this.lifecycles = hazelcastInstance.getMap(HazelcastMemberConfig.POD_LIFECYCLE_MAP_NAME);
        this.maxActiveMocks = config.maxActiveMocks();
        this.startupLeaseMillis = doubledMillis(config.podCreationTimeout());
    }

    @PostConstruct
    void reconcileOnStartup() {
        reconcile();
    }

    public void reserve(String mockId, String attemptId) {
        reserve(mockId, attemptId, () -> { });
    }

    void reserve(String mockId, String attemptId, Runnable publishStarting) {
        Objects.requireNonNull(mockId, "mockId");
        Objects.requireNonNull(attemptId, "attemptId");
        Objects.requireNonNull(publishStarting, "publishStarting");
        reservations.lock(CAPACITY_LOCK_KEY);
        try {
            String currentAttempt = reservations.get(mockId);
            Set<String> activeMockIds = activeMockIds();
            if (!attemptId.equals(currentAttempt)
                    && currentAttempt == null
                    && !activeMockIds.contains(mockId)
                    && activeMockIds.size() >= maxActiveMocks) {
                throw new CapacityExceededException(maxActiveMocks);
            }
            if (!attemptId.equals(currentAttempt)) {
                reservations.put(mockId, attemptId);
            }
            try {
                publishStarting.run();
            } catch (RuntimeException failure) {
                reservations.remove(mockId, attemptId);
                throw failure;
            }
        } finally {
            reservations.unlock(CAPACITY_LOCK_KEY);
        }
    }

    public void release(String mockId, String attemptId) {
        if (mockId == null || attemptId == null) {
            return;
        }
        reservations.lock(CAPACITY_LOCK_KEY);
        try {
            reservations.remove(mockId, attemptId);
        } finally {
            reservations.unlock(CAPACITY_LOCK_KEY);
        }
    }

    public boolean complete(String mockId, String attemptId, Runnable publishRunning) {
        Objects.requireNonNull(mockId, "mockId");
        Objects.requireNonNull(attemptId, "attemptId");
        Objects.requireNonNull(publishRunning, "publishRunning");
        reservations.lock(CAPACITY_LOCK_KEY);
        try {
            if (!attemptId.equals(reservations.get(mockId))) {
                return false;
            }
            try {
                publishRunning.run();
            } catch (RuntimeException failure) {
                reservations.remove(mockId, attemptId);
                throw failure;
            }
            reservations.remove(mockId, attemptId);
            return true;
        } finally {
            reservations.unlock(CAPACITY_LOCK_KEY);
        }
    }

    public int activeCount() {
        reservations.lock(CAPACITY_LOCK_KEY);
        try {
            return activeMockIds().size();
        } finally {
            reservations.unlock(CAPACITY_LOCK_KEY);
        }
    }

    public void reconcile() {
        reservations.lock(CAPACITY_LOCK_KEY);
        try {
            long now = System.currentTimeMillis();
            reservations.entrySet().forEach(entry -> {
                MockPodLifecycle lifecycle = lifecycles.get(entry.getKey());
                if (!isFreshStartingAttempt(lifecycle, entry.getValue(), now)) {
                    reservations.remove(entry.getKey(), entry.getValue());
                }
            });
        } finally {
            reservations.unlock(CAPACITY_LOCK_KEY);
        }
    }

    private Set<String> activeMockIds() {
        long now = System.currentTimeMillis();
        Set<String> active = new HashSet<>(reservations.keySet());
        active.addAll(pods.keySet());
        lifecycles.forEach((mockId, lifecycle) -> {
            if (lifecycle.status() == MockLifecycleStatus.RUNNING
                    || isFreshStarting(lifecycle, now)) {
                active.add(mockId);
            }
        });
        return active;
    }

    private boolean isFreshStartingAttempt(MockPodLifecycle lifecycle, String attemptId, long now) {
        return lifecycle != null
                && Objects.equals(lifecycle.attemptId(), attemptId)
                && isFreshStarting(lifecycle, now);
    }

    private boolean isFreshStarting(MockPodLifecycle lifecycle, long now) {
        if (lifecycle == null || lifecycle.status() != MockLifecycleStatus.STARTING
                || lifecycle.startedAtEpochMillis() <= 0L) {
            return false;
        }
        return now < lifecycle.startedAtEpochMillis()
                || now - lifecycle.startedAtEpochMillis() < startupLeaseMillis;
    }

    private static void validate(MockFleetConfig config) {
        if (config.maxActiveMocks() <= 0) {
            throw new IllegalArgumentException("mock-fleet.max-active-mocks must be positive");
        }
        if (config.maxConcurrentStarts() <= 0) {
            throw new IllegalArgumentException("mock-fleet.max-concurrent-starts must be positive");
        }
        if (config.queuedStartCapacity() <= 0) {
            throw new IllegalArgumentException("mock-fleet.queued-start-capacity must be positive");
        }
        if (config.maxConcurrentStarts() > config.maxActiveMocks()) {
            throw new IllegalArgumentException(
                    "mock-fleet.max-concurrent-starts must not exceed mock-fleet.max-active-mocks");
        }
    }

    private static long doubledMillis(Duration duration) {
        long millis = Math.max(1L, duration.toMillis());
        return millis > Long.MAX_VALUE / 2L ? Long.MAX_VALUE : millis * 2L;
    }

    public static final class CapacityExceededException extends WebApplicationException {
        CapacityExceededException(int maxActiveMocks) {
            super("Mock capacity is exhausted.", Response.status(Response.Status.TOO_MANY_REQUESTS)
                    .type(MediaType.APPLICATION_JSON_TYPE)
                    .entity(new ApiError(
                            "MOCK_CAPACITY_EXHAUSTED",
                            "Mock capacity is exhausted.",
                            true,
                            false,
                            Map.of("maxActiveMocks", maxActiveMocks)))
                    .build());
        }
    }
}
