package com.github.letsrokk;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.function.Function;

@ApplicationScoped
public class PodState {

    private final IMap<String, MockPodRef> podMap;
    private final IMap<String, Long> lastAccessTimeMap;

    @Inject
    public PodState(HazelcastInstance hazelcastInstance) {
        this.podMap = hazelcastInstance.getMap("mock-pod-name-map");
        this.lastAccessTimeMap = hazelcastInstance.getMap("last-access-time-map");
    }

    public MockPodRef getPod(String mockId) {
        return this.podMap.get(mockId);
    }

    public MockPodRef getPod(String mockId, Function<String, MockPodRef> mappingFunction) {
        return this.podMap.computeIfAbsent(mockId, mappingFunction);
    }

    public IMap<String, MockPodRef> getPods() {
        return this.podMap;
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

}
