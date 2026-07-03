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
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

@ApplicationScoped
public class PodState {

    private final IMap<String, MockPodRef> podMap;
    private final IMap<String, Long> lastAccessTimeMap;
    private final BroadcastProcessor<Long> podChanges = BroadcastProcessor.create();
    private final AtomicLong podChangeSequence = new AtomicLong();
    private final UUID podListenerRegistration;

    @Inject
    public PodState(HazelcastInstance hazelcastInstance) {
        this.podMap = hazelcastInstance.getMap(HazelcastMemberConfig.POD_MAP_NAME);
        this.lastAccessTimeMap = hazelcastInstance.getMap(HazelcastMemberConfig.LAST_ACCESS_MAP_NAME);
        this.podListenerRegistration = podMap.addEntryListener(new PodMapListener(), false);
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

            pod = mappingFunction.apply(mockId);
            this.podMap.put(mockId, pod);
            return pod;
        } finally {
            this.podMap.unlock(mockId);
        }
    }

    public IMap<String, MockPodRef> getPods() {
        return this.podMap;
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

}
