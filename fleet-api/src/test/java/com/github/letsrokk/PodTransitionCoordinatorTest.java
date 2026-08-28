package com.github.letsrokk;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PodTransitionCoordinatorTest {

    @Test
    void holdsDistributedPerMockLockAroundTransition() {
        HazelcastInstance hazelcast = mock(HazelcastInstance.class);
        @SuppressWarnings("unchecked")
        IMap<String, Boolean> locks = mock(IMap.class);
        when(hazelcast.<String, Boolean>getMap(HazelcastMemberConfig.POD_TRANSITION_LOCK_MAP_NAME))
                .thenReturn(locks);
        PodTransitionCoordinator coordinator = new PodTransitionCoordinator(hazelcast);

        assertEquals("done", coordinator.serialized("orders", () -> "done"));

        InOrder ordered = inOrder(locks);
        ordered.verify(locks).lock("orders");
        ordered.verify(locks).unlock("orders");
    }

    @Test
    void releasesDistributedLockWhenTransitionFails() {
        HazelcastInstance hazelcast = mock(HazelcastInstance.class);
        @SuppressWarnings("unchecked")
        IMap<String, Boolean> locks = mock(IMap.class);
        when(hazelcast.<String, Boolean>getMap(HazelcastMemberConfig.POD_TRANSITION_LOCK_MAP_NAME))
                .thenReturn(locks);
        PodTransitionCoordinator coordinator = new PodTransitionCoordinator(hazelcast);

        assertThrows(IllegalStateException.class,
                () -> coordinator.serialized("orders", () -> { throw new IllegalStateException("failed"); }));

        verify(locks).unlock("orders");
    }
}
