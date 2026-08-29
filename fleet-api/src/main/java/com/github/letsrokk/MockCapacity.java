package com.github.letsrokk;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.Serial;
import java.io.Serializable;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@ApplicationScoped
public class MockCapacity {

    static final String RESERVATION_MAP_NAME = "mock-capacity-reservation-map";
    static final String RESERVATION_LIVENESS_MAP_NAME = "mock-capacity-liveness-map";
    private static final String CAPACITY_LOCK_KEY = "__mock-fleet-capacity-lock__";
    private static final String RECLAIMED_START_MESSAGE = "Pod startup ownership expired.";

    private final IMap<String, String> reservations;
    private final IMap<String, ReservationLiveness> reservationLiveness;
    private final IMap<String, MockPodRef> pods;
    private final IMap<String, MockPodLifecycle> lifecycles;
    private final int maxActiveMocks;
    private final long reservationLeaseMillis;
    private final String ownerId;

    @Inject
    public MockCapacity(HazelcastInstance hazelcastInstance, MockFleetConfig config) {
        validate(config);
        this.reservations = hazelcastInstance.getMap(RESERVATION_MAP_NAME);
        this.reservationLiveness = hazelcastInstance.getMap(RESERVATION_LIVENESS_MAP_NAME);
        this.pods = hazelcastInstance.getMap(HazelcastMemberConfig.POD_MAP_NAME);
        this.lifecycles = hazelcastInstance.getMap(HazelcastMemberConfig.POD_LIFECYCLE_MAP_NAME);
        this.maxActiveMocks = config.maxActiveMocks();
        this.reservationLeaseMillis = doubledMillis(config.podCreationTimeout());
        this.ownerId = UUID.randomUUID().toString();
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
        withCapacityLock(() -> {
            long now = System.currentTimeMillis();
            reconcileExpiredReservations(now);
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
                reservationLiveness.put(mockId, new ReservationLiveness(attemptId, ownerId, now));
            } else {
                renewLocked(mockId, attemptId, now);
            }
            try {
                publishStarting.run();
            } catch (RuntimeException failure) {
                reservations.remove(mockId, attemptId);
                removeLiveness(mockId, attemptId);
                throw failure;
            }
            return null;
        });
    }

    public void release(String mockId, String attemptId) {
        if (mockId == null || attemptId == null) {
            return;
        }
        withCapacityLock(() -> {
            reservations.remove(mockId, attemptId);
            removeLiveness(mockId, attemptId);
            return null;
        });
    }

    public boolean complete(String mockId, String attemptId, Runnable publishRunning) {
        Objects.requireNonNull(mockId, "mockId");
        Objects.requireNonNull(attemptId, "attemptId");
        Objects.requireNonNull(publishRunning, "publishRunning");
        return withCapacityLock(() -> {
            if (!attemptId.equals(reservations.get(mockId))) {
                return false;
            }
            try {
                publishRunning.run();
            } catch (RuntimeException failure) {
                reservations.remove(mockId, attemptId);
                removeLiveness(mockId, attemptId);
                throw failure;
            }
            reservations.remove(mockId, attemptId);
            removeLiveness(mockId, attemptId);
            return true;
        });
    }

    public int activeCount() {
        return withCapacityLock(() -> {
            long now = System.currentTimeMillis();
            reconcileExpiredReservations(now);
            return activeMockIds().size();
        });
    }

    public void reconcile() {
        withCapacityLock(() -> {
            reconcileExpiredReservations(System.currentTimeMillis());
            return null;
        });
    }

    boolean renew(String mockId, String attemptId) {
        if (mockId == null || attemptId == null) {
            return false;
        }
        return withCapacityLock(() -> {
            long now = System.currentTimeMillis();
            reconcileExpiredReservations(now);
            return renewLocked(mockId, attemptId, now);
        });
    }

    boolean isCurrentReservation(String mockId, String attemptId) {
        if (mockId == null || attemptId == null) {
            return false;
        }
        return withCapacityLock(() -> {
            reconcileExpiredReservations(System.currentTimeMillis());
            return attemptId.equals(reservations.get(mockId));
        });
    }

    <T> T withReservationFence(String mockId, String attemptId, Supplier<T> action) {
        Objects.requireNonNull(mockId, "mockId");
        Objects.requireNonNull(attemptId, "attemptId");
        Objects.requireNonNull(action, "action");
        return withCapacityLock(() -> {
            long now = System.currentTimeMillis();
            reconcileExpiredReservations(now);
            if (!renewLocked(mockId, attemptId, now)) {
                throw new ReservationOwnershipLostException();
            }
            T result = action.get();
            renewLocked(mockId, attemptId, System.currentTimeMillis());
            return result;
        });
    }

    <T> T withCapacityLock(Supplier<T> action) {
        reservations.lock(CAPACITY_LOCK_KEY);
        try {
            return action.get();
        } finally {
            reservations.unlock(CAPACITY_LOCK_KEY);
        }
    }

    long reservationLeaseMillis() {
        return reservationLeaseMillis;
    }

    private boolean renewLocked(String mockId, String attemptId, long now) {
        if (!attemptId.equals(reservations.get(mockId))) {
            return false;
        }
        ReservationLiveness current = reservationLiveness.get(mockId);
        if (current == null
                || !attemptId.equals(current.attemptId())
                || !ownerId.equals(current.ownerId())) {
            return false;
        }
        reservationLiveness.put(mockId, new ReservationLiveness(attemptId, ownerId, now));
        return true;
    }

    private void reconcileExpiredReservations(long now) {
        reservations.entrySet().forEach(entry -> {
            String mockId = entry.getKey();
            String attemptId = entry.getValue();
            lifecycles.lock(mockId);
            try {
                MockPodLifecycle lifecycle = lifecycles.get(mockId);
                ReservationLiveness liveness = reservationLiveness.get(mockId);
                boolean currentStartingAttempt = lifecycle != null
                        && lifecycle.status() == MockLifecycleStatus.STARTING
                        && Objects.equals(lifecycle.attemptId(), attemptId);
                boolean liveReservation = liveness != null
                        && Objects.equals(liveness.attemptId(), attemptId)
                        && isFresh(liveness.renewedAtEpochMillis(), now);
                if (!currentStartingAttempt || !liveReservation) {
                    if (currentStartingAttempt) {
                        lifecycles.put(mockId, MockPodLifecycle.failed(
                                attemptId, lifecycle.podName(), RECLAIMED_START_MESSAGE),
                                30L, TimeUnit.SECONDS);
                    }
                    reservations.remove(mockId, attemptId);
                    removeLiveness(mockId, attemptId);
                }
            } finally {
                lifecycles.unlock(mockId);
            }
        });
    }

    private Set<String> activeMockIds() {
        Set<String> active = new HashSet<>(reservations.keySet());
        active.addAll(pods.keySet());
        lifecycles.forEach((mockId, lifecycle) -> {
            if (lifecycle.status() == MockLifecycleStatus.RUNNING) {
                active.add(mockId);
            }
        });
        return active;
    }

    private boolean isFresh(long renewedAtEpochMillis, long now) {
        return renewedAtEpochMillis > 0L
                && (now < renewedAtEpochMillis
                || now - renewedAtEpochMillis < reservationLeaseMillis);
    }

    private void removeLiveness(String mockId, String attemptId) {
        ReservationLiveness current = reservationLiveness.get(mockId);
        if (current != null && Objects.equals(current.attemptId(), attemptId)) {
            reservationLiveness.remove(mockId, current);
        }
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

    record ReservationLiveness(String attemptId, String ownerId,
                               long renewedAtEpochMillis) implements Serializable {
        @Serial
        private static final long serialVersionUID = 0L;
    }

    static final class ReservationOwnershipLostException extends RuntimeException {
        @Serial
        private static final long serialVersionUID = 0L;
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
