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

    public StartClaim claimStart(String mockId) {
        podLifecycleMap.lock(mockId);
        try {
            MockPodRef pod = podMap.get(mockId);
            if (pod != null) {
                return new StartClaim(false, MockPodLifecycle.running(null, pod.podName()), pod);
            }
            MockPodLifecycle current = podLifecycleMap.get(mockId);
            if (current != null && current.status() == MockLifecycleStatus.STARTING) {
                return new StartClaim(false, current, null);
            }
            String attemptId = UUID.randomUUID().toString();
            MockPodLifecycle starting = MockPodLifecycle.starting(attemptId, null);
            podLifecycleMap.put(mockId, starting);
            return new StartClaim(true, starting, null);
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
            podLifecycleMap.put(mockId, MockPodLifecycle.starting(attemptId, podName));
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
            podLifecycleMap.put(mockId, MockPodLifecycle.stopped(podName));
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
                podLifecycleMap.put(mockId, MockPodLifecycle.stopped());
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

    public IMap<String, MockPodRef> getPods() {
        return this.podMap;
    }

    public IMap<String, MockPodLifecycle> getPodLifecycles() {
        return podLifecycleMap;
    }

    public void markStartupPodName(String mockId, String podName) {
        MockPodLifecycle current = podLifecycleMap.get(mockId);
        podLifecycleMap.put(mockId, MockPodLifecycle.starting(current == null ? null : current.attemptId(), podName));
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

    public record StartClaim(boolean claimed, MockPodLifecycle lifecycle, MockPodRef pod) {
    }

    public record StopClaim(MockPodRef pod, String podName) {
    }

}
