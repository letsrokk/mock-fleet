package com.github.letsrokk;

import com.hazelcast.config.Config;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.PodListBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.PodResource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MockRecoveryTest {
    static HazelcastInstance hazelcast;
    MockRecovery recovery;
    PodState state;
    KubernetesClient client;
    NonNamespaceOperation<Pod, PodList, PodResource> namespaced;
    Map<String, PodResource> resources;

    @BeforeAll
    static void startHazelcast() {
        Config config = new Config();
        config.setClusterName("recovery-test-" + System.nanoTime());
        config.setProperty("hazelcast.logging.type", "none");
        config.setProperty("hazelcast.phone.home.enabled", "false");
        config.getNetworkConfig().setPort(0);
        config.getNetworkConfig().getJoin().getAutoDetectionConfig().setEnabled(false);
        config.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false);
        hazelcast = Hazelcast.newHazelcastInstance(config);
    }

    @AfterAll
    static void stopHazelcast() {
        hazelcast.getLifecycleService().terminate();
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void fixture() {
        for (String map : new String[] {HazelcastMemberConfig.RECOVERY_MAP_NAME, HazelcastMemberConfig.POD_MAP_NAME,
                HazelcastMemberConfig.POD_LIFECYCLE_MAP_NAME, HazelcastMemberConfig.LAST_ACCESS_MAP_NAME,
                MockCapacity.RESERVATION_MAP_NAME, MockCapacity.RESERVATION_LIVENESS_MAP_NAME}) {
            hazelcast.getMap(map).clear();
        }
        state = new PodState(hazelcast);
        client = mock(KubernetesClient.class, RETURNS_DEEP_STUBS);
        when(client.getNamespace()).thenReturn("testing");
        MixedOperation<Pod, PodList, PodResource> pods = mock(MixedOperation.class);
        namespaced = mock(NonNamespaceOperation.class, RETURNS_SELF);
        when(namespaced.withLabel(PodFactory.LABEL_APP_NAME, PodFactory.APP_NAME_VALUE)).thenReturn(namespaced);
        when(namespaced.withLabel(PodFactory.LABEL_MANAGED_BY, PodFactory.MANAGED_BY_VALUE)).thenReturn(namespaced);
        resources = new java.util.HashMap<>();
        when(client.pods()).thenReturn(pods);
        when(pods.inNamespace("testing")).thenReturn(namespaced);
        recovery = coordinator();
        list();
    }

    @AfterEach
    void removeListeners() {
        state.removePodListener();
    }

    MockRecovery coordinator() {
        MockRecovery result = new MockRecovery();
        result.hazelcast = hazelcast;
        result.podState = state;
        result.kubernetesClient = client;
        result.transitions = new PodTransitionCoordinator(hazelcast);
        result.config = mock(MockFleetConfig.class);
        when(result.config.wiremockContainerName()).thenReturn("wiremock");
        when(result.config.podCreationTimeout()).thenReturn(Duration.ofMillis(200));
        result.podManager = new PodManager();
        result.podManager.kubernetesClient = client;
        result.podManager.config = result.config;
        result.podManager.podState = state;
        result.podManager.recovery = result;
        return result;
    }

    @Test
    void coldRecoveryRestoresActualPodsAndCountsThemEvenAboveCapacity() {
        Pod first = pod("demo", "pod-a", true);
        Pod second = pod("other", "pod-b", true);
        list(first, second);
        resource(first);
        resource(second);
        long before = System.currentTimeMillis();
        recovery.recover();
        long after = System.currentTimeMillis();
        assertTrue(recovery.isReady());
        assertEquals(new MockPodRef("pod-a", "10.0.0.1", "3.12.1"), state.getPod("demo"));
        assertEquals(MockLifecycleStatus.RUNNING, state.lifecycle("demo").status());
        assertTrue(state.getLastAccessTime("pod-a") >= before && state.getLastAccessTime("pod-a") <= after);
        assertEquals(state.getLastAccessTime("pod-a"), state.getLastAccessTime("pod-b"));
        when(recovery.config.maxActiveMocks()).thenReturn(1);
        when(recovery.config.maxConcurrentStarts()).thenReturn(1);
        when(recovery.config.queuedStartCapacity()).thenReturn(1);
        MockCapacity capacity = new MockCapacity(hazelcast, recovery.config);
        capacity.metrics = mock(FleetMetrics.class);
        assertEquals(2, capacity.activeCount());
        assertThrows(MockCapacity.CapacityExceededException.class, () -> capacity.reserve("new", "attempt"));
        verify(client, never()).resource(any(Pod.class));
        verify(client, never()).raw(anyString(), anyString(), any());
    }

    @Test
    void initialSafetyGateDoesNotWithdrawExistingEndpointsDuringLaterMigrations() {
        recovery.recover();
        HazelcastInstance view = mock(HazelcastInstance.class, RETURNS_DEEP_STUBS);
        recovery.hazelcast = view;
        when(view.getPartitionService().isClusterSafe()).thenReturn(false, true, false);
        assertFalse(recovery.isReady());
        assertTrue(recovery.isReady());
        assertTrue(recovery.isReady());
        verify(view.getPartitionService(), times(2)).isClusterSafe();
    }

    @Test
    void firstFeatureUpgradeAndInterruptedPublicationPreserveExistingPodReferences() {
        Pod existing = pod("existing", "existing-pod", true);
        Pod missing = pod("missing", "missing-pod", true);
        list(existing, missing);
        resource(missing);
        state.getPods().put("existing", new MockPodRef("existing-pod", "10.0.0.2", "3.12.1"));
        state.setLastAccessTime("existing-pod", 123L);
        recovery.recover();
        assertEquals(123L, state.getLastAccessTime("existing-pod"));
        assertEquals(MockLifecycleStatus.RUNNING, state.lifecycle("existing").status());
        assertEquals("10.0.0.2", state.getPod("existing").podIp());
        assertNotNull(state.getPod("missing"));
    }

    @Test
    void warmJoinPreservesExistingStateAndDoesNotRelistEvenWhenNoMocksRemain() {
        Pod pod = pod("demo", "pod-a", true);
        list(pod);
        resource(pod);
        recovery.recover();
        state.setLastAccessTime("pod-a", 123L);
        clearInvocations(client);
        coordinator().recover();
        assertEquals(123L, state.getLastAccessTime("pod-a"));
        verifyNoInteractions(client);
        state.removePod("demo");
        coordinator().recover();
        assertNull(state.getPod("demo"));
        verifyNoInteractions(client);
    }

    @Test
    void resumesUnreadyPodsWithoutCreatingReplacementsAndReportsTimeouts() {
        Pod pending = pod("pending", "pending-pod", false);
        Pod timeout = pod("timeout", "timeout-pod", false);
        list(pending, timeout);
        when(resource(pending).get()).thenReturn(pending, pod("pending", "pending-pod", true));
        resource(timeout);
        recovery.recover();
        assertEquals("pending-pod", state.getPod("pending").podName());
        assertNull(state.getPod("timeout"));
        assertEquals(MockLifecycleStatus.FAILED, state.lifecycle("timeout").status());
        assertTrue(state.lifecycle("timeout").message().contains("timeout"));
        verify(client, never()).resource(any(Pod.class));
    }

    @Test
    void filtersCandidatesAndRechecksIdentityAndConcurrentLifecycleBeforePublishing() {
        Pod older = pod("demo", "old", true);
        Pod newer = new PodBuilder(pod("demo", "new", true)).editMetadata()
                .withCreationTimestamp("2026-09-18T12:00:01Z").endMetadata().build();
        Pod terminating = new PodBuilder(pod("terminating", "terminating", true)).editMetadata()
                .withDeletionTimestamp("2026-09-18T12:00:01Z").endMetadata().build();
        Pod stopped = pod("stopped", "stopped", true);
        Pod starting = pod("starting", "starting", true);
        Pod replaced = pod("replaced", "replaced", true);
        Pod failed = new PodBuilder(pod("failed", "failed", false)).editStatus().withPhase("Failed").endStatus().build();
        Pod unrelated = new PodBuilder(pod("foreign", "foreign", true)).editMetadata().withLabels(Map.of()).endMetadata().build();
        list(older, newer, terminating, stopped, starting, replaced, failed, unrelated, pod("Bad ID", "bad", true));
        resource(newer);
        when(resource(stopped).get()).thenAnswer(invocation -> {
            state.getPodLifecycles().put("stopped", MockPodLifecycle.stopped("stopped"));
            return stopped;
        });
        state.getPodLifecycles().put("starting", MockPodLifecycle.starting("attempt", "starting", 1));
        when(resource(replaced).get()).thenReturn(replaced,
                new PodBuilder(replaced).editMetadata().withUid("different").endMetadata().build());
        recovery.recover();
        assertEquals(1, state.getPods().size());
        assertEquals("new", state.getPod("demo").podName());
        assertEquals(MockLifecycleStatus.STOPPED, state.lifecycle("stopped").status());
        assertEquals("attempt", state.lifecycle("starting").attemptId());
    }

    @Test
    void failedAndInterruptedRecoveryBlockCleanupAndCanRetry() {
        var listing = client.pods().inNamespace("testing")
                .withLabel(PodFactory.LABEL_APP_NAME, PodFactory.APP_NAME_VALUE)
                .withLabel(PodFactory.LABEL_MANAGED_BY, PodFactory.MANAGED_BY_VALUE);
        when(listing.list()).thenThrow(new IllegalStateException("unavailable"));
        assertThrows(IllegalStateException.class, recovery::recover);
        assertFalse(recovery.isReady());
        clearInvocations(client);
        recovery.podManager.cleanUpOrphanedPods();
        recovery.podManager.cleanUpIdlePods();
        verifyNoInteractions(client);
        Pod pending = pod("pending", "pending", false);
        doReturn(new PodListBuilder().withItems(pending).build()).when(listing).list();
        resource(pending);
        Thread.currentThread().interrupt();
        try {
            assertThrows(IllegalStateException.class, recovery::recover);
            assertFalse(recovery.isReady());
        } finally {
            Thread.interrupted();
        }
        when(resource(pending).get()).thenReturn(pod("pending", "pending", true));
        recovery.recover();
        assertTrue(recovery.isReady());
        assertNotNull(state.getPod("pending"));
    }

    @Test
    void concurrentStartersPerformOneRecovery() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var listing = client.pods().inNamespace("testing")
                .withLabel(PodFactory.LABEL_APP_NAME, PodFactory.APP_NAME_VALUE)
                .withLabel(PodFactory.LABEL_MANAGED_BY, PodFactory.MANAGED_BY_VALUE);
        when(listing.list()).thenAnswer(invocation -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return new PodListBuilder().build();
        });
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(recovery::recover);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            MockRecovery joining = coordinator();
            var second = workers.submit(joining::recover);
            assertFalse(recovery.isReady());
            assertFalse(joining.isReady());
            release.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
            verify(listing).list();
            assertTrue(joining.isReady());
        } finally {
            release.countDown();
        }
    }

    private void list(Pod... pods) {
        when(client.pods().inNamespace("testing")
                .withLabel(PodFactory.LABEL_APP_NAME, PodFactory.APP_NAME_VALUE)
                .withLabel(PodFactory.LABEL_MANAGED_BY, PodFactory.MANAGED_BY_VALUE).list())
                .thenReturn(new PodListBuilder().withItems(pods).build());
    }

    private PodResource resource(Pod pod) {
        PodResource resource = resources.computeIfAbsent(pod.getMetadata().getName(), name -> {
            PodResource created = mock(PodResource.class);
            when(namespaced.withName(name)).thenReturn(created);
            return created;
        });
        when(resource.get()).thenReturn(pod);
        return resource;
    }

    private Pod pod(String mockId, String name, boolean ready) {
        return new PodBuilder().withNewMetadata().withName(name).withUid(name + "-uid")
                .withCreationTimestamp("2026-09-18T12:00:00Z")
                .withLabels(Map.of(PodFactory.LABEL_MOCK_ID, mockId,
                        PodFactory.LABEL_MANAGED_BY, PodFactory.MANAGED_BY_VALUE,
                        PodFactory.LABEL_APP_NAME, PodFactory.APP_NAME_VALUE)).endMetadata()
                .withNewSpec().addNewContainer().withName("wiremock").withImage("wiremock/wiremock:3.12.1-2")
                .endContainer().endSpec()
                .withNewStatus().withPhase("Running").withPodIP("10.0.0.1")
                .addNewCondition().withType("Ready").withStatus(ready ? "True" : "False").endCondition()
                .endStatus().build();
    }
}
