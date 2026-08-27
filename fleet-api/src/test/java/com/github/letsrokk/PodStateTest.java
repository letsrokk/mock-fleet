package com.github.letsrokk;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.EntryEvent;
import com.hazelcast.map.IMap;
import com.hazelcast.map.listener.EntryAddedListener;
import com.hazelcast.map.listener.MapListener;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import org.mockito.ArgumentCaptor;

class PodStateTest {

    @Test
    void failedLifecyclePreservesCreatedPodNameAndUsesConciseReason() {
        IMap<String, MockPodLifecycle> lifecycleMap = lifecycleMap();
        PodState podState = podStateWithMaps(podMap(), lifecycleMap);
        when(lifecycleMap.get("demo")).thenReturn(MockPodLifecycle.starting("mock-fleet-demo-1"));

        podState.markStartupFailed("demo", new RuntimeException("image pull failed\nstack detail"));

        verify(lifecycleMap).put(
                "demo",
                MockPodLifecycle.failed("mock-fleet-demo-1", "image pull failed"),
                30,
                TimeUnit.SECONDS);
    }

    @Test
    void claimStartDoesNotCreateADuplicateAttemptWhileStartupIsInProgress() {
        IMap<String, MockPodRef> podMap = podMap();
        IMap<String, MockPodLifecycle> lifecycleMap = lifecycleMap();
        PodState podState = podStateWithMaps(podMap, lifecycleMap);
        MockPodLifecycle starting = MockPodLifecycle.starting("attempt-1", "mock-fleet-demo-1");
        when(lifecycleMap.get("demo")).thenReturn(starting);

        PodState.StartClaim claim = podState.claimStart("demo");

        assertEquals(false, claim.claimed());
        assertEquals(starting, claim.lifecycle());
        verify(lifecycleMap, never()).put(eq("demo"), any());
    }

    @Test
    void failedAttemptCanBeClaimedForRetry() {
        IMap<String, MockPodRef> podMap = podMap();
        IMap<String, MockPodLifecycle> lifecycleMap = lifecycleMap();
        PodState podState = podStateWithMaps(podMap, lifecycleMap);
        when(lifecycleMap.get("demo")).thenReturn(MockPodLifecycle.failed(
                "attempt-1", "mock-fleet-demo-1", "image pull failed"));

        PodState.StartClaim claim = podState.claimStart("demo");

        assertEquals(true, claim.claimed());
        assertEquals(MockLifecycleStatus.STARTING, claim.lifecycle().status());
        verify(lifecycleMap).put("demo", claim.lifecycle());
    }

    @Test
    void stoppedAttemptRejectsLatePodCompletion() {
        IMap<String, MockPodRef> podMap = podMap();
        IMap<String, MockPodLifecycle> lifecycleMap = lifecycleMap();
        PodState podState = podStateWithMaps(podMap, lifecycleMap);
        when(lifecycleMap.get("demo")).thenReturn(MockPodLifecycle.stopped());
        MockPodRef latePod = new MockPodRef("mock-fleet-demo-1", "10.0.0.1");

        boolean accepted = podState.completeStart("demo", "attempt-1", latePod);

        assertEquals(false, accepted);
        verify(podMap, never()).put("demo", latePod);
    }

    @Test
    void stopKeepsPodNameUntilKubernetesDeletionIsConfirmed() {
        IMap<String, MockPodRef> podMap = podMap();
        IMap<String, MockPodLifecycle> lifecycleMap = lifecycleMap();
        PodState podState = podStateWithMaps(podMap, lifecycleMap);
        MockPodRef pod = new MockPodRef("mock-fleet-demo-1", "10.0.0.1");
        when(podMap.remove("demo")).thenReturn(pod);

        PodState.StopClaim result = podState.stop("demo");

        assertEquals("mock-fleet-demo-1", result.podName());
        verify(lifecycleMap).put("demo", new MockPodLifecycle(
                null, "mock-fleet-demo-1", MockLifecycleStatus.STOPPED, null));
    }

    @Test
    void publishesDistributedPodMapChanges() throws Exception {
        IMap<String, MockPodRef> podMap = podMap();
        UUID registration = UUID.randomUUID();
        when(podMap.addEntryListener(any(MapListener.class), eq(false))).thenReturn(registration);
        PodState podState = podStateWithPodMap(podMap);
        var nextChange = podState.podChanges().select().first().toUni().subscribeAsCompletionStage();
        ArgumentCaptor<MapListener> listenerCaptor = ArgumentCaptor.forClass(MapListener.class);
        verify(podMap).addEntryListener(listenerCaptor.capture(), eq(false));

        @SuppressWarnings("unchecked")
        EntryAddedListener<String, MockPodRef> listener =
                (EntryAddedListener<String, MockPodRef>) listenerCaptor.getValue();
        listener.entryAdded(mock(EntryEvent.class));

        assertEquals(1L, nextChange.toCompletableFuture().get(1, TimeUnit.SECONDS));
        podState.removePodListener();
        verify(podMap).removeEntryListener(registration);
    }

    private PodState podStateWithPodMap(IMap<String, MockPodRef> podMap) {
        return podStateWithMaps(podMap, lifecycleMap());
    }

    private PodState podStateWithMaps(IMap<String, MockPodRef> podMap,
                                       IMap<String, MockPodLifecycle> lifecycleMap) {
        HazelcastInstance hazelcastInstance = mock(HazelcastInstance.class);
        @SuppressWarnings("unchecked")
        IMap<String, Long> lastAccessTimeMap = mock(IMap.class);
        when(hazelcastInstance.<String, MockPodRef>getMap("mock-pod-name-map")).thenReturn(podMap);
        when(hazelcastInstance.<String, Long>getMap("last-access-time-map")).thenReturn(lastAccessTimeMap);
        when(hazelcastInstance.<String, MockPodLifecycle>getMap("mock-pod-lifecycle-map")).thenReturn(lifecycleMap);
        return new PodState(hazelcastInstance);
    }

    private IMap<String, MockPodRef> podMap() {
        @SuppressWarnings("unchecked")
        IMap<String, MockPodRef> podMap = mock(IMap.class);
        return podMap;
    }

    private IMap<String, MockPodLifecycle> lifecycleMap() {
        @SuppressWarnings("unchecked")
        IMap<String, MockPodLifecycle> lifecycleMap = mock(IMap.class);
        return lifecycleMap;
    }
}
