package com.github.letsrokk;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.EntryEvent;
import com.hazelcast.map.IMap;
import com.hazelcast.map.listener.EntryAddedListener;
import com.hazelcast.map.listener.MapListener;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import org.mockito.ArgumentCaptor;

class PodStateTest {

    @Test
    void getPodWithMappingFunctionReturnsExistingPodWithoutCreating() {
        PodState podState = podStateWithPodMap(podMap());
        MockPodRef existingPod = new MockPodRef("mock-fleet-demo-1", "10.0.0.1");
        @SuppressWarnings("unchecked")
        Function<String, MockPodRef> mappingFunction = mock(Function.class);

        when(podState.getPods().get("demo")).thenReturn(existingPod);

        MockPodRef pod = podState.getPod("demo", mappingFunction);

        assertEquals(existingPod, pod);
        verify(mappingFunction, never()).apply("demo");
        verify(podState.getPods(), never()).put("demo", existingPod);
        verify(podState.getPods()).unlock("demo");
    }

    @Test
    void getPodWithMappingFunctionCreatesAndStoresPodWhenMissing() {
        PodState podState = podStateWithPodMap(podMap());
        MockPodRef createdPod = new MockPodRef("mock-fleet-demo-1", "10.0.0.1");

        when(podState.getPods().get("demo")).thenReturn(null);

        MockPodRef pod = podState.getPod("demo", mockId -> createdPod);

        assertEquals(createdPod, pod);
        verify(podState.getPods()).put("demo", createdPod);
        verify(podState.getPods()).unlock("demo");
    }

    @Test
    void getPodWithMappingFunctionUnlocksWhenCreationFails() {
        PodState podState = podStateWithPodMap(podMap());
        RuntimeException failure = new RuntimeException("creation failed");

        when(podState.getPods().get("demo")).thenReturn(null);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> podState.getPod("demo", mockId -> {
                    throw failure;
                }));

        assertSame(failure, thrown);
        verify(podState.getPods(), never()).put("demo", new MockPodRef("mock-fleet-demo-1", "10.0.0.1"));
        verify(podState.getPods()).unlock("demo");
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
        HazelcastInstance hazelcastInstance = mock(HazelcastInstance.class);
        @SuppressWarnings("unchecked")
        IMap<String, Long> lastAccessTimeMap = mock(IMap.class);
        when(hazelcastInstance.<String, MockPodRef>getMap("mock-pod-name-map")).thenReturn(podMap);
        when(hazelcastInstance.<String, Long>getMap("last-access-time-map")).thenReturn(lastAccessTimeMap);
        return new PodState(hazelcastInstance);
    }

    private IMap<String, MockPodRef> podMap() {
        @SuppressWarnings("unchecked")
        IMap<String, MockPodRef> podMap = mock(IMap.class);
        return podMap;
    }
}
