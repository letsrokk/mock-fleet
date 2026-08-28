package com.github.letsrokk;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.EntryEvent;
import com.hazelcast.map.IMap;
import com.hazelcast.map.listener.EntryAddedListener;
import com.hazelcast.map.listener.MapListener;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
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
        MockPodLifecycle starting = MockPodLifecycle.starting("attempt-1", "mock-fleet-demo-1", 1_000L);
        when(lifecycleMap.get("demo")).thenReturn(starting);

        PodState.StartClaim claim = podState.claimStart("demo", 1_999L, 1_000L);

        assertEquals(false, claim.claimed());
        assertEquals(starting, claim.lifecycle());
        verify(lifecycleMap, never()).put(eq("demo"), any());
    }

    @Test
    void claimStartAtomicallyReclaimsAStaleAttempt() {
        IMap<String, MockPodRef> podMap = podMap();
        IMap<String, MockPodLifecycle> lifecycleMap = lifecycleMap();
        PodState podState = podStateWithMaps(podMap, lifecycleMap);
        MockPodLifecycle stale = MockPodLifecycle.starting("attempt-1", "mock-fleet-demo-1", 1_000L);
        when(lifecycleMap.get("demo")).thenReturn(stale);

        PodState.StartClaim claim = podState.claimStart("demo", 2_000L, 1_000L);

        assertEquals(true, claim.claimed());
        assertEquals(MockLifecycleStatus.STARTING, claim.lifecycle().status());
        assertEquals(2_000L, claim.lifecycle().startedAtEpochMillis());
        assertEquals("mock-fleet-demo-1", claim.lifecycle().podName());
        assertEquals("mock-fleet-demo-1", claim.previousPodName());
        org.junit.jupiter.api.Assertions.assertNotEquals("attempt-1", claim.lifecycle().attemptId());
        verify(lifecycleMap).put("demo", claim.lifecycle());
        when(lifecycleMap.get("demo")).thenReturn(claim.lifecycle());
        assertEquals(false, podState.completeStart("demo", "attempt-1",
                new MockPodRef("mock-fleet-demo-1", "10.0.0.1")));
    }

    @Test
    void startupPodNameUpdatePreservesLeaseTimestamp() {
        IMap<String, MockPodRef> podMap = podMap();
        IMap<String, MockPodLifecycle> lifecycleMap = lifecycleMap();
        PodState podState = podStateWithMaps(podMap, lifecycleMap);
        MockPodLifecycle starting = MockPodLifecycle.starting("attempt-1", null, 1_000L);
        when(lifecycleMap.get("demo")).thenReturn(starting);

        assertEquals(true, podState.markStartupPodName("demo", "attempt-1", "mock-fleet-demo-1"));

        verify(lifecycleMap).put("demo",
                MockPodLifecycle.starting("attempt-1", "mock-fleet-demo-1", 1_000L));
    }

    @Test
    void failedAttemptWithNamedPodIsClaimedForCleanupBeforeRetry() {
        IMap<String, MockPodRef> podMap = podMap();
        IMap<String, MockPodLifecycle> lifecycleMap = lifecycleMap();
        PodState podState = podStateWithMaps(podMap, lifecycleMap);
        MockPodLifecycle failed = MockPodLifecycle.failed(
                "attempt-1", "mock-fleet-demo-1", "image pull failed");
        when(lifecycleMap.get("demo")).thenReturn(failed);

        PodState.StartClaim claim = podState.claimStart("demo", 1_000L, 1_000L);

        assertEquals(true, claim.claimed());
        assertEquals(MockLifecycleStatus.STARTING, claim.lifecycle().status());
        assertEquals("mock-fleet-demo-1", claim.lifecycle().podName());
        assertEquals("mock-fleet-demo-1", claim.previousPodName());
        verify(lifecycleMap).put("demo", claim.lifecycle());
        when(lifecycleMap.get("demo")).thenReturn(claim.lifecycle());
        assertEquals(false, podState.completeStart("demo", "attempt-1",
                new MockPodRef("mock-fleet-demo-1", "10.0.0.1")));
    }

    @Test
    void stoppedDeletionRetryWithNamedPodIsClaimedForCleanupBeforeStart() {
        IMap<String, MockPodRef> podMap = podMap();
        IMap<String, MockPodLifecycle> lifecycleMap = lifecycleMap();
        PodState podState = podStateWithMaps(podMap, lifecycleMap);
        when(lifecycleMap.get("demo")).thenReturn(MockPodLifecycle.stopped("mock-fleet-demo-1"));

        PodState.StartClaim claim = podState.claimStart("demo", 1_000L, 1_000L);

        assertEquals(true, claim.claimed());
        assertEquals(MockLifecycleStatus.STARTING, claim.lifecycle().status());
        assertEquals("mock-fleet-demo-1", claim.lifecycle().podName());
        assertEquals("mock-fleet-demo-1", claim.previousPodName());
        verify(lifecycleMap).put("demo", claim.lifecycle());
    }

    @Test
    void absentStartDoesNotInventAPredecessorPodName() {
        IMap<String, MockPodRef> podMap = podMap();
        IMap<String, MockPodLifecycle> lifecycleMap = lifecycleMap();
        PodState podState = podStateWithMaps(podMap, lifecycleMap);

        PodState.StartClaim claim = podState.claimStart("demo", 1_000L, 1_000L);

        assertEquals(true, claim.claimed());
        assertEquals(null, claim.lifecycle().podName());
        assertEquals(null, claim.previousPodName());
    }

    @Test
    void namelessFailedStartDoesNotInventAPredecessorPodName() {
        IMap<String, MockPodRef> podMap = podMap();
        IMap<String, MockPodLifecycle> lifecycleMap = lifecycleMap();
        PodState podState = podStateWithMaps(podMap, lifecycleMap);
        when(lifecycleMap.get("demo")).thenReturn(MockPodLifecycle.failed(
                "attempt-1", null, "image pull failed"));

        PodState.StartClaim claim = podState.claimStart("demo", 1_000L, 1_000L);

        assertEquals(true, claim.claimed());
        assertEquals(null, claim.lifecycle().podName());
        assertEquals(null, claim.previousPodName());
    }

    @Test
    void restartClaimAtomicallyInvalidatesThePreviousStartingAttempt() {
        IMap<String, MockPodRef> podMap = podMap();
        IMap<String, MockPodLifecycle> lifecycleMap = lifecycleMap();
        PodState podState = podStateWithMaps(podMap, lifecycleMap);
        MockPodLifecycle previous = MockPodLifecycle.starting("attempt-1", "mock-fleet-demo-1");
        when(lifecycleMap.get("demo")).thenReturn(previous);

        PodState.RestartClaim replacement = podState.claimRestart("demo");

        assertEquals(true, replacement.claimed());
        assertEquals(MockLifecycleStatus.STARTING, replacement.lifecycle().status());
        assertEquals("mock-fleet-demo-1", replacement.lifecycle().podName());
        assertEquals("mock-fleet-demo-1", replacement.previousPodName());
        org.junit.jupiter.api.Assertions.assertNotEquals("attempt-1", replacement.lifecycle().attemptId());
        verify(lifecycleMap).put("demo", replacement.lifecycle());
        when(lifecycleMap.get("demo")).thenReturn(replacement.lifecycle());
        assertEquals(false, podState.completeStart("demo", "attempt-1",
                new MockPodRef("mock-fleet-demo-1", "10.0.0.1")));
    }

    @Test
    void failedRestartRetainsPreviousPodForTheNextStartAttempt() {
        IMap<String, MockPodRef> podMap = podMap();
        IMap<String, MockPodLifecycle> lifecycleMap = lifecycleMap();
        PodState podState = podStateWithMaps(podMap, lifecycleMap);
        MockPodRef previousPod = new MockPodRef("mock-fleet-demo-1", "10.0.0.1");
        when(podMap.get("demo")).thenReturn(previousPod);

        PodState.RestartClaim restart = podState.claimRestart("demo");
        when(lifecycleMap.get("demo")).thenReturn(restart.lifecycle());
        podState.failStart("demo", restart.lifecycle().attemptId(),
                new RuntimeException("deletion timed out"));

        MockPodLifecycle failed = MockPodLifecycle.failed(
                restart.lifecycle().attemptId(), previousPod.podName(), "deletion timed out");
        when(podMap.get("demo")).thenReturn(null);
        when(lifecycleMap.get("demo")).thenReturn(failed);

        PodState.StartClaim retry = podState.claimStart("demo", 2_000L, 1_000L);

        assertEquals(true, retry.claimed());
        assertEquals(previousPod.podName(), retry.previousPodName());
        assertEquals(previousPod.podName(), retry.lifecycle().podName());
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
    void stopOfAbsentMockDoesNotCreateATombstone() {
        IMap<String, MockPodRef> podMap = podMap();
        IMap<String, MockPodLifecycle> lifecycleMap = lifecycleMap();
        PodState podState = podStateWithMaps(podMap, lifecycleMap);
        when(podMap.remove("demo")).thenReturn(null);
        when(lifecycleMap.get("demo")).thenReturn(null);

        PodState.StopClaim result = podState.stop("demo");

        assertEquals(null, result.podName());
        verify(lifecycleMap, never()).put(eq("demo"), any());
    }

    @Test
    void stopWithoutAStartupPodNameRemovesStateAndInvalidatesLateCompletion() {
        IMap<String, MockPodRef> podMap = podMap();
        IMap<String, MockPodLifecycle> lifecycleMap = lifecycleMap();
        PodState podState = podStateWithMaps(podMap, lifecycleMap);
        when(lifecycleMap.get("demo"))
                .thenReturn(MockPodLifecycle.starting("attempt-1", null, 1_000L))
                .thenReturn(null);

        PodState.StopClaim result = podState.stop("demo");
        boolean accepted = podState.completeStart(
                "demo", "attempt-1", new MockPodRef("mock-fleet-demo-1", "10.0.0.1"));

        assertEquals(null, result.podName());
        assertEquals(false, accepted);
        verify(lifecycleMap).remove("demo");
        verify(podMap, never()).put(eq("demo"), any());
    }

    @Test
    void confirmedKubernetesDeletionRemovesStoppedLifecycleState() {
        IMap<String, MockPodRef> podMap = podMap();
        IMap<String, MockPodLifecycle> lifecycleMap = lifecycleMap();
        PodState podState = podStateWithMaps(podMap, lifecycleMap);
        MockPodLifecycle stopped = MockPodLifecycle.stopped("mock-fleet-demo-1");
        when(lifecycleMap.get("demo")).thenReturn(stopped);

        podState.confirmStopped("demo", "mock-fleet-demo-1");

        verify(lifecycleMap).remove("demo");
        verify(lifecycleMap, never()).put("demo", MockPodLifecycle.stopped());
    }

    @Test
    void startupLeaseTimestampSurvivesJavaSerialization() throws Exception {
        MockPodLifecycle lifecycle = MockPodLifecycle.starting(
                "attempt-1", "mock-fleet-demo-1", 1_234L);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(lifecycle);
        }

        MockPodLifecycle restored;
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            restored = (MockPodLifecycle) input.readObject();
        }

        assertEquals(lifecycle, restored);
        assertEquals(1_234L, restored.startedAtEpochMillis());
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
