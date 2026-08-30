package com.github.letsrokk;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.EntryEvent;
import com.hazelcast.map.MapEvent;
import com.hazelcast.map.IMap;
import com.hazelcast.map.listener.EntryAddedListener;
import com.hazelcast.map.listener.EntryRemovedListener;
import com.hazelcast.map.listener.EntryUpdatedListener;
import com.hazelcast.map.listener.MapClearedListener;
import com.hazelcast.map.listener.MapEvictedListener;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.function.Supplier;

@ApplicationScoped
public class PodState {

    private final IMap<String, MockPodRef> podMap;
    private final IMap<String, MockPodLifecycle> podLifecycleMap;
    private final IMap<String, Long> lastAccessTimeMap;
    private final BroadcastProcessor<Long> podChanges = BroadcastProcessor.create();
    private final AtomicLong podChangeSequence = new AtomicLong();
    private final UUID podListenerRegistration;
    private final UUID podLifecycleListenerRegistration;

    private static final long FAILED_STARTUP_RETENTION_SECONDS = 30;

    private enum LatestAccessTimestamp implements BiFunction<Long, Long, Long> {
        INSTANCE;

        @Override
        public Long apply(Long current, Long saved) {
            return Math.max(current, saved);
        }
    }

    @Inject
    MockCapacity mockCapacity;

    @Inject
    public PodState(HazelcastInstance hazelcastInstance) {
        this.podMap = hazelcastInstance.getMap(HazelcastMemberConfig.POD_MAP_NAME);
        this.podLifecycleMap = hazelcastInstance.getMap(HazelcastMemberConfig.POD_LIFECYCLE_MAP_NAME);
        this.lastAccessTimeMap = hazelcastInstance.getMap(HazelcastMemberConfig.LAST_ACCESS_MAP_NAME);
        this.podListenerRegistration = podMap.addEntryListener(new PodMapListener(), false);
        this.podLifecycleListenerRegistration = podLifecycleMap.addEntryListener(new PodLifecycleMapListener(), false);
    }

    public MockPodRef getPod(String mockId) {
        return this.podMap.get(mockId);
    }

    public boolean backfillRuntimeVersion(String mockId, MockPodRef expected, String runtimeVersion) {
        if (expected == null || runtimeVersion == null) {
            return false;
        }
        return podMap.replace(mockId, expected,
                new MockPodRef(expected.podName(), expected.podIp(), runtimeVersion));
    }

    public StartClaim claimStart(String mockId, long nowEpochMillis, long startupLeaseMillis) {
        return withCapacityLock(() -> {
            podLifecycleMap.lock(mockId);
            try {
                MockPodRef pod = podMap.get(mockId);
                if (pod != null) {
                    return new StartClaim(false, MockPodLifecycle.running(null, pod.podName()), pod);
                }
                MockPodLifecycle current = podLifecycleMap.get(mockId);
                if (isLiveStartingAttempt(mockId, current, nowEpochMillis, startupLeaseMillis)) {
                    return new StartClaim(false, current, null, null);
                }
                String previousPodName = current == null || current.podName() == null || current.podName().isBlank()
                        ? null
                        : current.podName();
                String attemptId = UUID.randomUUID().toString();
                MockPodLifecycle starting = MockPodLifecycle.starting(attemptId, previousPodName, nowEpochMillis);
                reserveAndPublishStarting(mockId, attemptId, starting);
                return new StartClaim(true, starting, null, previousPodName);
            } finally {
                podLifecycleMap.unlock(mockId);
            }
        });
    }

    public RestartClaim claimRestart(String mockId) {
        return withCapacityLock(() -> {
            podLifecycleMap.lock(mockId);
            try {
                MockPodRef pod = podMap.get(mockId);
                MockPodLifecycle current = podLifecycleMap.get(mockId);
                MockLifecycleStatus currentStatus = pod != null
                        ? MockLifecycleStatus.RUNNING
                        : current == null ? MockLifecycleStatus.STOPPED : current.status();
                if (currentStatus != MockLifecycleStatus.STARTING && currentStatus != MockLifecycleStatus.RUNNING) {
                    MockPodLifecycle lifecycle = current == null ? MockPodLifecycle.stopped() : current;
                    return new RestartClaim(false, lifecycle, null);
                }

                String previousPodName = pod != null ? pod.podName() : current.podName();
                MockPodLifecycle previousLifecycle = pod == null
                        ? current
                        : MockPodLifecycle.running(
                                current == null ? null : current.attemptId(), pod.podName());
                Long previousLastAccessEpochMillis = null;
                if (pod != null) {
                    podMap.remove(mockId);
                    previousLastAccessEpochMillis = lastAccessTimeMap.remove(pod.podName());
                }
                String attemptId = UUID.randomUUID().toString();
                MockPodLifecycle replacement = MockPodLifecycle.starting(
                        attemptId, previousPodName, System.currentTimeMillis());
                reserveAndPublishStarting(mockId, attemptId, replacement);
                return new RestartClaim(true, replacement, previousPodName,
                        pod, previousLifecycle, previousLastAccessEpochMillis);
            } finally {
                podLifecycleMap.unlock(mockId);
            }
        });
    }

    public boolean rollbackRejectedRestart(String mockId, RestartClaim claim) {
        String attemptId = claim.lifecycle().attemptId();
        return withCapacityLock(() -> {
            podLifecycleMap.lock(mockId);
            try {
                MockPodLifecycle current = podLifecycleMap.get(mockId);
                if (!isCurrentStartingAttempt(current, attemptId)
                        || claim.previousPod() == null
                        || claim.previousLifecycle() == null
                        || claim.previousLifecycle().status() != MockLifecycleStatus.RUNNING) {
                    return false;
                }
                podMap.put(mockId, claim.previousPod());
                if (claim.previousLastAccessEpochMillis() != null) {
                    lastAccessTimeMap.merge(
                            claim.previousPod().podName(),
                            claim.previousLastAccessEpochMillis(),
                            LatestAccessTimestamp.INSTANCE);
                }
                podLifecycleMap.put(mockId, claim.previousLifecycle());
                return true;
            } finally {
                try {
                    releaseCapacity(mockId, attemptId);
                } finally {
                    podLifecycleMap.unlock(mockId);
                }
            }
        });
    }

    public boolean markStartupPodName(String mockId, String attemptId, String podName) {
        podLifecycleMap.lock(mockId);
        try {
            MockPodLifecycle current = podLifecycleMap.get(mockId);
            if (!isCurrentStartingAttempt(current, attemptId)) {
                return false;
            }
            podLifecycleMap.put(mockId,
                    MockPodLifecycle.starting(attemptId, podName, current.startedAtEpochMillis()));
            return true;
        } finally {
            podLifecycleMap.unlock(mockId);
        }
    }

    public boolean isCurrentStartingAttempt(String mockId, String attemptId) {
        return isCurrentStartingAttempt(podLifecycleMap.get(mockId), attemptId);
    }

    String currentStartupPodName(String mockId, String attemptId) {
        MockPodLifecycle current = podLifecycleMap.get(mockId);
        return isCurrentStartingAttempt(current, attemptId) ? current.podName() : null;
    }

    public boolean completeStart(String mockId, String attemptId, MockPodRef pod) {
        return completeStart(mockId, attemptId, pod, null);
    }

    public boolean completeStart(String mockId, String attemptId, MockPodRef pod, Long lastAccessEpochMillis) {
        return withCapacityLock(() -> {
            podLifecycleMap.lock(mockId);
            try {
                MockPodLifecycle current = podLifecycleMap.get(mockId);
                if (!isCurrentStartingAttempt(current, attemptId)) {
                    return false;
                }
                MockPodLifecycle running = MockPodLifecycle.running(attemptId, pod.podName());
                Runnable publishRunning = () -> {
                    try {
                        if (lastAccessEpochMillis != null) {
                            lastAccessTimeMap.put(pod.podName(), lastAccessEpochMillis);
                        }
                        podMap.put(mockId, pod);
                        podLifecycleMap.put(mockId, running);
                    } catch (RuntimeException failure) {
                        rollBackStartPublication(
                                mockId, attemptId, pod, lastAccessEpochMillis, current, running, failure);
                        throw failure;
                    }
                };
                if (mockCapacity != null) {
                    return mockCapacity.complete(mockId, attemptId, publishRunning);
                }
                publishRunning.run();
                return true;
            } finally {
                podLifecycleMap.unlock(mockId);
            }
        });
    }

    public void failStart(String mockId, String attemptId, RuntimeException exception) {
        withCapacityLock(() -> {
            podLifecycleMap.lock(mockId);
            try {
                MockPodLifecycle current = podLifecycleMap.get(mockId);
                if (!isCurrentStartingAttempt(current, attemptId)) {
                    return null;
                }
                putFailedLifecycle(mockId,
                        MockPodLifecycle.failed(attemptId, current.podName(), conciseFailureReason(exception)));
                return null;
            } finally {
                try {
                    releaseCapacity(mockId, attemptId);
                } finally {
                    podLifecycleMap.unlock(mockId);
                }
            }
        });
    }

    public StopClaim stop(String mockId) {
        return withCapacityLock(() -> {
            podLifecycleMap.lock(mockId);
            try {
                MockPodRef pod = podMap.remove(mockId);
                MockPodLifecycle lifecycle = podLifecycleMap.get(mockId);
                String podName = pod != null ? pod.podName() : lifecycle == null ? null : lifecycle.podName();
                if (pod != null) {
                    lastAccessTimeMap.remove(pod.podName());
                }
                if (podName != null && !podName.isBlank()) {
                    podLifecycleMap.put(mockId, MockPodLifecycle.stopped(podName));
                } else if (lifecycle != null) {
                    podLifecycleMap.remove(mockId);
                }
                if (lifecycle != null) {
                    releaseCapacity(mockId, lifecycle.attemptId());
                }
                return new StopClaim(pod, podName);
            } finally {
                podLifecycleMap.unlock(mockId);
            }
        });
    }

    public void confirmStopped(String mockId, String podName) {
        podLifecycleMap.lock(mockId);
        try {
            MockPodLifecycle current = podLifecycleMap.get(mockId);
            if (current != null
                    && current.status() == MockLifecycleStatus.STOPPED
                    && java.util.Objects.equals(current.podName(), podName)) {
                podLifecycleMap.remove(mockId);
            }
        } finally {
            podLifecycleMap.unlock(mockId);
        }
    }

    public MockPodLifecycle lifecycle(String mockId) {
        MockPodRef pod = podMap.get(mockId);
        if (pod != null) {
            MockPodLifecycle lifecycle = podLifecycleMap.get(mockId);
            return MockPodLifecycle.running(lifecycle == null ? null : lifecycle.attemptId(), pod.podName());
        }
        MockPodLifecycle lifecycle = podLifecycleMap.get(mockId);
        return lifecycle == null ? MockPodLifecycle.stopped() : lifecycle;
    }

    private boolean isCurrentStartingAttempt(MockPodLifecycle current, String attemptId) {
        return current != null
                && current.status() == MockLifecycleStatus.STARTING
                && java.util.Objects.equals(current.attemptId(), attemptId);
    }

    private boolean isLiveStartingAttempt(String mockId, MockPodLifecycle current,
                                          long nowEpochMillis, long startupLeaseMillis) {
        if (current != null && current.status() == MockLifecycleStatus.STARTING
                && mockCapacity != null) {
            return mockCapacity.isCurrentReservation(mockId, current.attemptId());
        }
        if (current == null || current.status() != MockLifecycleStatus.STARTING
                || current.startedAtEpochMillis() <= 0L) {
            return false;
        }
        if (nowEpochMillis < current.startedAtEpochMillis()) {
            return true;
        }
        return nowEpochMillis - current.startedAtEpochMillis() < Math.max(1L, startupLeaseMillis);
    }

    public IMap<String, MockPodRef> getPods() {
        return this.podMap;
    }

    public IMap<String, MockPodLifecycle> getPodLifecycles() {
        return podLifecycleMap;
    }

    public void markStartupPodName(String mockId, String podName) {
        MockPodLifecycle current = podLifecycleMap.get(mockId);
        podLifecycleMap.put(mockId, MockPodLifecycle.starting(
                current == null ? null : current.attemptId(), podName,
                current == null ? 0L : current.startedAtEpochMillis()));
    }

    void markStartupFailed(String mockId, RuntimeException exception) {
        MockPodLifecycle current = podLifecycleMap.get(mockId);
        String podName = current == null ? null : current.podName();
        String attemptId = current == null ? null : current.attemptId();
        try {
            putFailedLifecycle(mockId,
                    MockPodLifecycle.failed(attemptId, podName, conciseFailureReason(exception)));
        } finally {
            releaseCapacity(mockId, attemptId);
        }
    }

    private void putFailedLifecycle(String mockId, MockPodLifecycle failed) {
        podLifecycleMap.put(mockId, failed, FAILED_STARTUP_RETENTION_SECONDS, TimeUnit.SECONDS);
    }

    private void reserveAndPublishStarting(String mockId, String attemptId, MockPodLifecycle lifecycle) {
        if (mockCapacity == null) {
            podLifecycleMap.put(mockId, lifecycle);
            return;
        }
        mockCapacity.reserve(mockId, attemptId, () -> podLifecycleMap.put(mockId, lifecycle));
    }

    private void releaseCapacity(String mockId, String attemptId) {
        if (mockCapacity != null) {
            mockCapacity.release(mockId, attemptId);
        }
    }

    private <T> T withCapacityLock(Supplier<T> action) {
        return mockCapacity == null ? action.get() : mockCapacity.withCapacityLock(action);
    }

    private void rollBackStartPublication(String mockId, String attemptId, MockPodRef pod,
                                           Long lastAccessEpochMillis, MockPodLifecycle starting,
                                           MockPodLifecycle running, RuntimeException failure) {
        tryCleanup(() -> podLifecycleMap.replace(mockId, running, starting), failure);
        tryCleanup(() -> podMap.remove(mockId, pod), failure);
        if (lastAccessEpochMillis != null) {
            tryCleanup(() -> lastAccessTimeMap.remove(pod.podName(), lastAccessEpochMillis), failure);
        }
        tryCleanup(() -> releaseCapacity(mockId, attemptId), failure);
    }

    private void tryCleanup(Runnable cleanup, RuntimeException failure) {
        try {
            cleanup.run();
        } catch (RuntimeException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    public Multi<Long> podChanges() {
        return podChanges;
    }

    public Long getLastAccessTime(String podName) {
        return this.lastAccessTimeMap.get(podName);
    }

    public void setLastAccessTime(String podName, Long lastAccessTime) {
        this.lastAccessTimeMap.put(podName, lastAccessTime);
    }

    public void removeLastAccessTime(String podName) {
        this.lastAccessTimeMap.remove(podName);
    }

    public void removePod(String mockId) {
        StopClaim stopped = stop(mockId);
        if (stopped.podName() != null) {
            confirmStopped(mockId, stopped.podName());
        }
    }

    @PreDestroy
    void removePodListener() {
        if (podListenerRegistration != null) {
            podMap.removeEntryListener(podListenerRegistration);
        }
        if (podLifecycleListenerRegistration != null) {
            podLifecycleMap.removeEntryListener(podLifecycleListenerRegistration);
        }
    }

    private String conciseFailureReason(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return "Pod startup failed.";
        }

        String firstLine = message.lines().findFirst().orElse(message).trim();
        return firstLine.length() <= 200 ? firstLine : firstLine.substring(0, 197) + "...";
    }

    private void notifyPodChange() {
        podChanges.onNext(podChangeSequence.incrementAndGet());
    }

    private final class PodMapListener implements EntryAddedListener<String, MockPodRef>,
            EntryUpdatedListener<String, MockPodRef>, EntryRemovedListener<String, MockPodRef>,
            MapClearedListener, MapEvictedListener {

        @Override
        public void entryAdded(EntryEvent<String, MockPodRef> event) {
            notifyPodChange();
        }

        @Override
        public void entryUpdated(EntryEvent<String, MockPodRef> event) {
            notifyPodChange();
        }

        @Override
        public void entryRemoved(EntryEvent<String, MockPodRef> event) {
            notifyPodChange();
        }

        @Override
        public void mapCleared(MapEvent event) {
            notifyPodChange();
        }

        @Override
        public void mapEvicted(MapEvent event) {
            notifyPodChange();
        }
    }

    private final class PodLifecycleMapListener implements EntryAddedListener<String, MockPodLifecycle>,
            EntryUpdatedListener<String, MockPodLifecycle>, EntryRemovedListener<String, MockPodLifecycle>,
            MapClearedListener, MapEvictedListener {

        @Override
        public void entryAdded(EntryEvent<String, MockPodLifecycle> event) {
            notifyPodChange();
        }

        @Override
        public void entryUpdated(EntryEvent<String, MockPodLifecycle> event) {
            notifyPodChange();
        }

        @Override
        public void entryRemoved(EntryEvent<String, MockPodLifecycle> event) {
            notifyPodChange();
        }

        @Override
        public void mapCleared(MapEvent event) {
            notifyPodChange();
        }

        @Override
        public void mapEvicted(MapEvent event) {
            notifyPodChange();
        }
    }

    public record StartClaim(boolean claimed, MockPodLifecycle lifecycle, MockPodRef pod, String previousPodName) {
        public StartClaim(boolean claimed, MockPodLifecycle lifecycle, MockPodRef pod) {
            this(claimed, lifecycle, pod, null);
        }
    }

    public record RestartClaim(boolean claimed, MockPodLifecycle lifecycle, String previousPodName,
                               MockPodRef previousPod, MockPodLifecycle previousLifecycle,
                               Long previousLastAccessEpochMillis) {
        public RestartClaim(boolean claimed, MockPodLifecycle lifecycle, String previousPodName) {
            this(claimed, lifecycle, previousPodName, null, null, null);
        }
    }

    public record StopClaim(MockPodRef pod, String podName) {
    }

}
