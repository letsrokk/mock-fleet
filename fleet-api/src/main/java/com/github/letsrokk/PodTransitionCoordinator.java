package com.github.letsrokk;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.function.Supplier;

@ApplicationScoped
public class PodTransitionCoordinator {

    private final IMap<String, Boolean> transitionLocks;

    @Inject
    public PodTransitionCoordinator(HazelcastInstance hazelcastInstance) {
        this.transitionLocks = hazelcastInstance.getMap(HazelcastMemberConfig.POD_TRANSITION_LOCK_MAP_NAME);
    }

    public <T> T serialized(String mockId, Supplier<T> action) {
        transitionLocks.lock(mockId);
        try {
            return action.get();
        } finally {
            transitionLocks.unlock(mockId);
        }
    }
}
