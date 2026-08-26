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
import java.util.function.Function;

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

    public MockPodRef getPod(String mockId, Function<String, MockPodRef> mappingFunction) {
        this.podMap.lock(mockId);
        try {
            MockPodRef pod = this.podMap.get(mockId);
            if (pod != null) {
                return pod;
            }

            podLifecycleMap.put(mockId, MockPodLifecycle.starting(null));
            try {
                pod = mappingFunction.apply(mockId);
                this.podMap.put(mockId, pod);
                podLifecycleMap.remove(mockId);
                return pod;
            } catch (RuntimeException exception) {
                markStartupFailed(mockId, exception);
                throw exception;
            }
        } finally {
            this.podMap.unlock(mockId);
        }
    }

    public IMap<String, MockPodRef> getPods() {
        return this.podMap;
    }

    public IMap<String, MockPodLifecycle> getPodLifecycles() {
        return podLifecycleMap;
    }

    public void markStartupPodName(String mockId, String podName) {
        podLifecycleMap.put(mockId, MockPodLifecycle.starting(podName));
    }

    void markStartupFailed(String mockId, RuntimeException exception) {
        MockPodLifecycle current = podLifecycleMap.get(mockId);
        String podName = current == null ? null : current.podName();
        podLifecycleMap.put(
                mockId,
                MockPodLifecycle.failed(podName, conciseFailureReason(exception)),
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
        MockPodRef pod = this.podMap.remove(mockId);
        if (pod != null) {
            this.lastAccessTimeMap.remove(pod.podName());
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

}
