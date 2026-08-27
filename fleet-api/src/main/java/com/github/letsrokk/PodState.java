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

    public StartClaim claimStart(String mockId, long nowEpochMillis, long startupLeaseMillis) {
        podLifecycleMap.lock(mockId);
        try {
            MockPodRef pod = podMap.get(mockId);
            if (pod != null) {
                return new StartClaim(false, MockPodLifecycle.running(null, pod.podName()), pod);
            }
            MockPodLifecycle current = podLifecycleMap.get(mockId);
            if (isFreshStartingAttempt(current, nowEpochMillis, startupLeaseMillis)) {
                return new StartClaim(false, current, null, null);
            }
            String previousPodName = current == null || current.podName() == null || current.podName().isBlank()
                    ? null
                    : current.podName();
            String attemptId = UUID.randomUUID().toString();
            MockPodLifecycle starting = MockPodLifecycle.starting(attemptId, previousPodName, nowEpochMillis);
            podLifecycleMap.put(mockId, starting);
            return new StartClaim(true, starting, null, previousPodName);
        } finally {
            podLifecycleMap.unlock(mockId);
        }
    }

    public RestartClaim claimRestart(String mockId) {
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
            if (pod != null) {
                podMap.remove(mockId);
                lastAccessTimeMap.remove(pod.podName());
            }
            String attemptId = UUID.randomUUID().toString();
            MockPodLifecycle replacement = MockPodLifecycle.starting(attemptId, null, System.currentTimeMillis());
            podLifecycleMap.put(mockId, replacement);
            return new RestartClaim(true, replacement, previousPodName);
        } finally {
            podLifecycleMap.unlock(mockId);
        }
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

    public boolean completeStart(String mockId, String attemptId, MockPodRef pod) {
        podLifecycleMap.lock(mockId);
        try {
            MockPodLifecycle current = podLifecycleMap.get(mockId);
            if (!isCurrentStartingAttempt(current, attemptId)) {
                return false;
            }
            podMap.put(mockId, pod);
            podLifecycleMap.put(mockId, MockPodLifecycle.running(attemptId, pod.podName()));
            return true;
        } finally {
            podLifecycleMap.unlock(mockId);
        }
    }

    public void failStart(String mockId, String attemptId, RuntimeException exception) {
        podLifecycleMap.lock(mockId);
        try {
            MockPodLifecycle current = podLifecycleMap.get(mockId);
            if (!isCurrentStartingAttempt(current, attemptId)) {
                return;
            }
            podLifecycleMap.put(mockId,
                    MockPodLifecycle.failed(attemptId, current.podName(), conciseFailureReason(exception)),
                    FAILED_STARTUP_RETENTION_SECONDS,
                    TimeUnit.SECONDS);
        } finally {
            podLifecycleMap.unlock(mockId);
        }
    }

    public StopClaim stop(String mockId) {
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
            return new StopClaim(pod, podName);
        } finally {
            podLifecycleMap.unlock(mockId);
        }
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

    private boolean isFreshStartingAttempt(MockPodLifecycle current, long nowEpochMillis, long startupLeaseMillis) {
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
        podLifecycleMap.put(
                mockId,
                MockPodLifecycle.failed(current == null ? null : current.attemptId(), podName,
                        conciseFailureReason(exception)),
                FAILED_STARTUP_RETENTION_SECONDS,
                TimeUnit.SECONDS);
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

    public record RestartClaim(boolean claimed, MockPodLifecycle lifecycle, String previousPodName) {
    }

    public record StopClaim(MockPodRef pod, String podName) {
    }

}
