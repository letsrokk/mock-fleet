package com.github.letsrokk;

import com.github.letsrokk.exceptions.PodCreationException;
import com.hazelcast.map.IMap;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.PodConditionBuilder;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.PodStatusBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirements;
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NamespaceableResource;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.PodResource;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PodManagerTest {

    @Test
    void waitForPodToBeRunningTimesOutCleanly() {
        KubernetesClient kubernetesClient = mock(KubernetesClient.class, RETURNS_DEEP_STUBS);
        PodManager podManager = new PodManager();
        podManager.kubernetesClient = kubernetesClient;

        Pod pod = pod("mock-fleet-test-1", "Pending", false);
        when(kubernetesClient.resource(pod).get()).thenReturn(pod);

        assertThrows(PodCreationException.class, () -> podManager.waitForPodToBeRunning(pod, Duration.ofMillis(1)));
    }

    @Test
    void waitForPodToBeRunningRequiresReadyCondition() {
        KubernetesClient kubernetesClient = mock(KubernetesClient.class, RETURNS_DEEP_STUBS);
        PodManager podManager = new PodManager();
        podManager.kubernetesClient = kubernetesClient;

        Pod createdPod = pod("mock-fleet-test-1", "Pending", false);
        Pod runningButNotReadyPod = pod("mock-fleet-test-1", "Running", false);
        Pod readyPod = pod("mock-fleet-test-1", "Running", true);
        when(kubernetesClient.resource(createdPod).get())
                .thenReturn(runningButNotReadyPod)
                .thenReturn(readyPod);

        Pod result = podManager.waitForPodToBeRunning(createdPod, Duration.ofSeconds(1));

        assertEquals(readyPod, result);
    }

    @Test
    void waitForPodToBeRunningReportsTerminalContainerFailureImmediately() {
        KubernetesClient kubernetesClient = mock(KubernetesClient.class, RETURNS_DEEP_STUBS);
        PodManager podManager = new PodManager();
        podManager.kubernetesClient = kubernetesClient;

        Pod createdPod = pod("mock-fleet-test-1", "Pending", false);
        Pod failedPod = new PodBuilder(createdPod)
                .editStatus()
                .withPhase("Failed")
                .withReason("Evicted")
                .withMessage("node had disk pressure")
                .endStatus()
                .build();
        when(kubernetesClient.resource(createdPod).get()).thenReturn(failedPod);

        PodCreationException exception = assertThrows(PodCreationException.class,
                () -> podManager.waitForPodToBeRunning(createdPod, Duration.ofSeconds(5)));

        assertEquals("Pod 'mock-fleet-test-1' failed: Evicted: node had disk pressure", exception.getMessage());
    }

    @Test
    void listActiveMocksReturnsSortedRows() {
        PodState podState = mock(PodState.class);
        @SuppressWarnings("unchecked")
        IMap<String, MockPodRef> pods = mock(IMap.class);
        PodManager podManager = new PodManager();
        podManager.podState = podState;

        when(podState.getPods()).thenReturn(pods);
        when(pods.entrySet()).thenReturn(Map.of(
                "zeta", new MockPodRef("mock-fleet-zeta-1", "10.0.0.2"),
                "alpha", new MockPodRef("mock-fleet-alpha-1", "10.0.0.1")).entrySet());

        List<PodManager.ActiveMockPod> activeMocks = podManager.listActiveMocks();

        assertEquals(List.of(
                new PodManager.ActiveMockPod("alpha", "mock-fleet-alpha-1"),
                new PodManager.ActiveMockPod("zeta", "mock-fleet-zeta-1")), activeMocks);
    }

    @Test
    void listMocksIncludesStartingFailedAndRunningRows() {
        PodState podState = mock(PodState.class);
        @SuppressWarnings("unchecked")
        IMap<String, MockPodRef> pods = mock(IMap.class);
        @SuppressWarnings("unchecked")
        IMap<String, MockPodLifecycle> lifecycles = mock(IMap.class);
        PodManager podManager = new PodManager();
        podManager.podState = podState;

        when(podState.getPods()).thenReturn(pods);
        when(podState.getPodLifecycles()).thenReturn(lifecycles);
        when(pods.entrySet()).thenReturn(Map.of(
                "running", new MockPodRef("mock-fleet-running-1", "10.0.0.1")).entrySet());
        when(lifecycles.entrySet()).thenReturn(Map.of(
                "starting", MockPodLifecycle.starting("mock-fleet-starting-1"),
                "failed", MockPodLifecycle.failed("mock-fleet-failed-1", "image pull failed")).entrySet());

        assertEquals(List.of(
                new PodManager.MockPodStatus("failed", "mock-fleet-failed-1", MockLifecycleStatus.FAILED,
                        "image pull failed"),
                new PodManager.MockPodStatus("running", "mock-fleet-running-1", MockLifecycleStatus.RUNNING, null),
                new PodManager.MockPodStatus("starting", "mock-fleet-starting-1", MockLifecycleStatus.STARTING, null)
        ), podManager.listMocks());
    }

    @Test
    void listMocksOmitsStoppedDeletionRetryState() {
        PodState podState = mock(PodState.class);
        @SuppressWarnings("unchecked")
        IMap<String, MockPodRef> pods = mock(IMap.class);
        @SuppressWarnings("unchecked")
        IMap<String, MockPodLifecycle> lifecycles = mock(IMap.class);
        PodManager podManager = new PodManager();
        podManager.podState = podState;
        when(podState.getPods()).thenReturn(pods);
        when(podState.getPodLifecycles()).thenReturn(lifecycles);
        when(pods.entrySet()).thenReturn(Map.<String, MockPodRef>of().entrySet());
        when(lifecycles.entrySet()).thenReturn(Map.of(
                "deleting", MockPodLifecycle.stopped("mock-fleet-deleting-1")).entrySet());

        assertEquals(List.of(), podManager.listMocks());
    }

    @Test
    void restartActiveDoesNotStartAReplacementForFailedMock() {
        PodState podState = mock(PodState.class);
        PodManager podManager = new PodManager();
        podManager.podState = podState;
        MockPodLifecycle failed = MockPodLifecycle.failed(
                "attempt-1", "mock-fleet-demo-1", "image pull failed");
        when(podState.claimRestart("demo")).thenReturn(new PodState.RestartClaim(false, failed, null));

        PodManager.MockPodStatus result = podManager.restartActive("demo");

        assertEquals(MockLifecycleStatus.FAILED, result.status());
        verify(podState, never()).stop("demo");
    }

    @Test
    void restartActiveQueuesPodDeletionInsteadOfBlockingTheCaller() {
        KubernetesClient kubernetesClient = mock(KubernetesClient.class);
        PodState podState = mock(PodState.class);
        @SuppressWarnings("unchecked")
        MixedOperation<Pod, PodList, PodResource> podOperations = mock(MixedOperation.class);
        @SuppressWarnings("unchecked")
        NonNamespaceOperation<Pod, PodList, PodResource> namespacedPods = mock(NonNamespaceOperation.class);
        PodResource podResource = mock(PodResource.class);
        MockFleetConfig config = mock(MockFleetConfig.class);
        Queue<Runnable> queued = new ArrayDeque<>();
        AtomicBoolean deletionCalled = new AtomicBoolean();
        PodManager podManager = new PodManager();
        podManager.kubernetesClient = kubernetesClient;
        podManager.podState = podState;
        podManager.config = config;
        podManager.startExecutor = queued::add;
        MockPodRef oldPod = new MockPodRef("mock-fleet-demo-1", "10.0.0.1");
        MockPodLifecycle starting = MockPodLifecycle.starting("attempt-2", null);
        when(podState.claimRestart("demo")).thenReturn(new PodState.RestartClaim(
                true, starting, oldPod.podName()));
        when(kubernetesClient.getNamespace()).thenReturn("test");
        when(kubernetesClient.pods()).thenReturn(podOperations);
        when(podOperations.inNamespace("test")).thenReturn(namespacedPods);
        when(namespacedPods.withName(oldPod.podName())).thenReturn(podResource);
        when(podResource.delete()).thenAnswer(invocation -> {
            deletionCalled.set(true);
            return List.of(mock(io.fabric8.kubernetes.api.model.StatusDetails.class));
        });

        PodManager.MockPodStatus result = podManager.restartActive("demo");

        assertEquals(MockLifecycleStatus.STARTING, result.status());
        assertFalse(deletionCalled.get(), "Kubernetes deletion ran on the request thread");
        assertEquals(1, queued.size());
    }

    @Test
    void startMockReturnsTerminalFailureWhenBackgroundAttemptAlreadyFailed() {
        PodState podState = mock(PodState.class);
        PodCreationException failure = new PodCreationException("ImagePullBackOff: denied");
        PodManager podManager = new PodManager() {
            @Override
            MockPodRef spawnPod(String mockId, String attemptId) {
                throw failure;
            }
        };
        podManager.podState = podState;
        podManager.podCreationTimeout = Duration.ofSeconds(1);
        podManager.startExecutor = task -> {
            Thread thread = new Thread(task);
            thread.setUncaughtExceptionHandler((ignored, error) -> { });
            thread.start();
            try {
                thread.join();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(error);
            }
        };
        MockPodLifecycle starting = MockPodLifecycle.starting("attempt-1", null);
        MockPodLifecycle failed = MockPodLifecycle.failed("attempt-1", null, failure.getMessage());
        when(podState.claimStart(eq("demo"), anyLong(), eq(2_000L)))
                .thenReturn(new PodState.StartClaim(true, starting, null));
        when(podState.lifecycle("demo")).thenReturn(failed);

        PodManager.MockPodStatus result = podManager.startMock("demo");

        assertEquals(MockLifecycleStatus.FAILED, result.status());
        assertEquals("ImagePullBackOff: denied", result.message());
        verify(podState).failStart("demo", "attempt-1", failure);
    }

    @Test
    void supersededStartupWorkerDeletesItsLatePod() {
        PodState podState = mock(PodState.class);
        AtomicBoolean latePodDeleted = new AtomicBoolean();
        MockPodRef latePod = new MockPodRef("mock-fleet-demo-old", "10.0.0.1");
        PodManager podManager = new PodManager() {
            @Override
            MockPodRef spawnPod(String mockId, String attemptId) {
                return latePod;
            }

            @Override
            boolean deletePod(MockPodRef pod) {
                latePodDeleted.set(true);
                return true;
            }
        };
        podManager.podState = podState;
        podManager.podCreationTimeout = Duration.ofSeconds(1);
        podManager.startExecutor = task -> {
            try {
                task.run();
            } catch (PodCreationException ignored) {
                // The request already observes the replacement lifecycle below.
            }
        };
        MockPodLifecycle oldAttempt = MockPodLifecycle.starting("attempt-old", null, 1_000L);
        MockPodLifecycle replacement = MockPodLifecycle.starting("attempt-new", null, 2_000L);
        when(podState.claimStart(eq("demo"), anyLong(), eq(2_000L)))
                .thenReturn(new PodState.StartClaim(true, oldAttempt, null));
        when(podState.completeStart("demo", "attempt-old", latePod)).thenReturn(false);
        when(podState.lifecycle("demo")).thenReturn(replacement);

        PodManager.MockPodStatus result = podManager.startMock("demo");

        assertTrue(latePodDeleted.get());
        assertEquals("attempt-new", replacement.attemptId());
        assertEquals(MockLifecycleStatus.STARTING, result.status());
    }

    @Test
    void deleteMockReturnsNotFoundWhenMockIsMissing() {
        PodState podState = mock(PodState.class);
        PodManager podManager = new PodManager();
        podManager.podState = podState;

        when(podState.stop("demo")).thenReturn(new PodState.StopClaim(null, null));

        assertEquals(PodManager.DeleteMockResult.STOPPED, podManager.deleteMock("demo"));
    }

    @Test
    void deleteMockDeletesPodAndState() {
        KubernetesClient kubernetesClient = mock(KubernetesClient.class);
        PodState podState = mock(PodState.class);
        @SuppressWarnings("unchecked")
        MixedOperation<Pod, PodList, PodResource> podOperations = mock(MixedOperation.class);
        @SuppressWarnings("unchecked")
        NonNamespaceOperation<Pod, PodList, PodResource> namespacedPods = mock(NonNamespaceOperation.class);
        PodResource podResource = mock(PodResource.class);
        PodManager podManager = new PodManager();
        podManager.kubernetesClient = kubernetesClient;
        podManager.podState = podState;

        MockPodRef pod = new MockPodRef("mock-fleet-demo-1", "10.0.0.1");

        when(podState.stop("demo")).thenReturn(new PodState.StopClaim(pod, pod.podName()));
        when(kubernetesClient.getNamespace()).thenReturn("test");
        when(kubernetesClient.pods()).thenReturn(podOperations);
        when(podOperations.inNamespace("test")).thenReturn(namespacedPods);
        when(namespacedPods.withName("mock-fleet-demo-1")).thenReturn(podResource);
        when(podResource.delete()).thenReturn(List.of(mock(io.fabric8.kubernetes.api.model.StatusDetails.class)));

        assertEquals(PodManager.DeleteMockResult.DELETED, podManager.deleteMock("demo"));
        verify(podState).stop("demo");
        verify(kubernetesClient, never()).services();
    }

    @Test
    void deleteMockReturnsFailedWhenPodDeletionFails() {
        KubernetesClient kubernetesClient = mock(KubernetesClient.class);
        PodState podState = mock(PodState.class);
        @SuppressWarnings("unchecked")
        MixedOperation<Pod, PodList, PodResource> podOperations = mock(MixedOperation.class);
        @SuppressWarnings("unchecked")
        NonNamespaceOperation<Pod, PodList, PodResource> namespacedPods = mock(NonNamespaceOperation.class);
        PodResource podResource = mock(PodResource.class);
        PodManager podManager = new PodManager();
        podManager.kubernetesClient = kubernetesClient;
        podManager.podState = podState;

        MockPodRef pod = new MockPodRef("mock-fleet-demo-1", "10.0.0.1");
        when(podState.stop("demo")).thenReturn(new PodState.StopClaim(pod, pod.podName()));
        when(kubernetesClient.getNamespace()).thenReturn("test");
        when(kubernetesClient.pods()).thenReturn(podOperations);
        when(podOperations.inNamespace("test")).thenReturn(namespacedPods);
        when(namespacedPods.withName("mock-fleet-demo-1")).thenReturn(podResource);
        when(podResource.delete()).thenReturn(List.of());
        when(podResource.get()).thenReturn(new PodBuilder()
                .withNewMetadata().withName("mock-fleet-demo-1").endMetadata().build());

        assertEquals(PodManager.DeleteMockResult.FAILED, podManager.deleteMock("demo"));
        verify(podState, never()).removePod("demo");
    }

    @Test
    void getUpstreamBaseUrlUsesCachedPodIpWithoutKubernetesLookup() {
        KubernetesClient kubernetesClient = mock(KubernetesClient.class, RETURNS_DEEP_STUBS);
        PodState podState = mock(PodState.class);
        MockFleetConfig config = mock(MockFleetConfig.class);
        PodManager podManager = new PodManager();
        podManager.kubernetesClient = kubernetesClient;
        podManager.podState = podState;
        podManager.config = config;
        podManager.podCreationTimeout = Duration.ofSeconds(1);

        when(podState.getPod("demo")).thenReturn(new MockPodRef("mock-fleet-demo-1", "10.0.0.1"));

        String upstreamBaseUrl = podManager.getUpstreamBaseUrl("demo");

        assertEquals("http://10.0.0.1:8080", upstreamBaseUrl);
        verify(podState).setLastAccessTime(eq("mock-fleet-demo-1"), any());
        verify(kubernetesClient, never()).pods();
        verify(kubernetesClient, never()).services();
    }

    @Test
    void cleanUpIdlePodsDeletesOnlyStalePodsWithRecordedAccessTime() {
        KubernetesClient kubernetesClient = mock(KubernetesClient.class);
        PodState podState = mock(PodState.class);
        @SuppressWarnings("unchecked")
        IMap<String, MockPodRef> pods = mock(IMap.class);
        @SuppressWarnings("unchecked")
        MixedOperation<Pod, PodList, PodResource> podOperations = mock(MixedOperation.class);
        @SuppressWarnings("unchecked")
        NonNamespaceOperation<Pod, PodList, PodResource> namespacedPods = mock(NonNamespaceOperation.class);
        PodResource stalePodResource = mock(PodResource.class);
        MockFleetConfig config = mock(MockFleetConfig.class);
        PodManager podManager = new PodManager();
        podManager.kubernetesClient = kubernetesClient;
        podManager.podState = podState;
        podManager.config = config;
        podManager.inactivityThreshold = Duration.ofSeconds(30);

        MockPodRef stalePod = new MockPodRef("stale-pod", "10.0.0.1");
        MockPodRef currentPod = new MockPodRef("current-pod", "10.0.0.2");
        MockPodRef unknownPod = new MockPodRef("unknown-pod", "10.0.0.3");

        when(kubernetesClient.getNamespace()).thenReturn("test");
        when(kubernetesClient.pods()).thenReturn(podOperations);
        when(podOperations.inNamespace("test")).thenReturn(namespacedPods);
        when(namespacedPods.withName("stale-pod")).thenReturn(stalePodResource);
        when(podState.getPods()).thenReturn(pods);
        when(podState.getLastAccessTime("stale-pod")).thenReturn(System.currentTimeMillis() - 60_000);
        when(podState.getLastAccessTime("current-pod")).thenReturn(System.currentTimeMillis());
        when(podState.getLastAccessTime("unknown-pod")).thenReturn(null);
        when(stalePodResource.delete()).thenReturn(List.of(mock(io.fabric8.kubernetes.api.model.StatusDetails.class)));
        org.mockito.Mockito.doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            BiConsumer<String, MockPodRef> consumer = invocation.getArgument(0);
            consumer.accept("stale", stalePod);
            consumer.accept("current", currentPod);
            consumer.accept("unknown", unknownPod);
            return null;
        }).when(pods).forEach(org.mockito.ArgumentMatchers.<BiConsumer<String, MockPodRef>>any());

        podManager.cleanUpIdlePods();

        verify(podState).removePod("stale");
        verify(namespacedPods).withName("stale-pod");
        verify(namespacedPods, never()).withName("current-pod");
        verify(namespacedPods, never()).withName("unknown-pod");
        verify(kubernetesClient, never()).services();
    }

    @Test
    void cleanUpOrphanedPodsUseManagedByLabelAndDeleteOnlyOrphans() {
        KubernetesClient kubernetesClient = mock(KubernetesClient.class, RETURNS_DEEP_STUBS);
        PodState podState = mock(PodState.class);
        @SuppressWarnings("unchecked")
        IMap<String, MockPodRef> pods = mock(IMap.class);
        @SuppressWarnings("unchecked")
        IMap<String, MockPodLifecycle> lifecycles = mock(IMap.class);
        @SuppressWarnings("unchecked")
        MixedOperation<Pod, PodList, PodResource> podOperations = mock(MixedOperation.class);
        @SuppressWarnings("unchecked")
        NonNamespaceOperation<Pod, PodList, PodResource> namespacedPods = mock(NonNamespaceOperation.class);
        MockFleetConfig config = mock(MockFleetConfig.class);
        PodManager podManager = new PodManager();
        podManager.kubernetesClient = kubernetesClient;
        podManager.podState = podState;
        podManager.config = config;

        Pod ownedPod = pod("owned-pod", "Running");
        Pod startingPod = pod("starting-pod", "Pending", false);
        Pod orphanPod = pod("orphan-pod", "Running");
        PodList podList = new PodList();
        podList.setItems(List.of(ownedPod, startingPod, orphanPod));

        when(kubernetesClient.getNamespace()).thenReturn("test");
        when(kubernetesClient.pods()).thenReturn(podOperations);
        when(podOperations.inNamespace("test")).thenReturn(namespacedPods);
        when(namespacedPods.withLabel(PodFactory.LABEL_MANAGED_BY, PodFactory.MANAGED_BY_VALUE)).thenReturn(namespacedPods);
        when(namespacedPods.list()).thenReturn(podList);
        when(podState.getPods()).thenReturn(pods);
        when(podState.getPodLifecycles()).thenReturn(lifecycles);
        when(pods.values()).thenReturn(List.of(new MockPodRef("owned-pod", "10.0.0.1")));
        when(lifecycles.values()).thenReturn(List.of(
                MockPodLifecycle.starting("attempt-1", "starting-pod", 1_000L)));
        when(kubernetesClient.resource(orphanPod).delete()).thenReturn(List.of(mock(io.fabric8.kubernetes.api.model.StatusDetails.class)));

        podManager.cleanUpOrphanedPods();

        verify(namespacedPods).withLabel(PodFactory.LABEL_MANAGED_BY, PodFactory.MANAGED_BY_VALUE);
        verify(kubernetesClient.resource(orphanPod)).delete();
        verify(kubernetesClient.resource(ownedPod), never()).delete();
        verify(kubernetesClient.resource(startingPod), never()).delete();
        verify(kubernetesClient, never()).services();
    }

    @Test
    void spawnPodCreatesPodWaitsForRunningStateAndReturnsPodRef() {
        KubernetesClient kubernetesClient = mock(KubernetesClient.class, RETURNS_DEEP_STUBS);
        PodFactory podFactory = mock(PodFactory.class);
        PodState podState = mock(PodState.class);
        WireMockOptions wireMockOptions = mock(WireMockOptions.class);
        MockFleetConfig config = mock(MockFleetConfig.class);
        @SuppressWarnings("unchecked")
        NamespaceableResource<Pod> podHandle = mock(NamespaceableResource.class);
        PodManager podManager = new PodManager();
        podManager.kubernetesClient = kubernetesClient;
        podManager.podFactory = podFactory;
        podManager.podState = podState;
        podManager.wireMockOptions = wireMockOptions;
        podManager.config = config;
        podManager.podCreationTimeout = Duration.ofSeconds(1);

        Pod podSpec = podWithGenerateName("custom-demo-");
        Pod createdPod = pod("custom-demo-1", "Pending", false);
        Pod runningPod = pod("custom-demo-1", "Running", true);
        ResourceRequirements resources = resources("0.5", "512Mi", "1", "1Gi");
        when(config.namespace()).thenReturn("mock-fleet");
        when(config.wiremockPodNamePrefix()).thenReturn("custom");
        when(wireMockOptions.optionsFor("demo")).thenReturn(List.of("--verbose"));
        when(wireMockOptions.resourcesFor("demo")).thenReturn(resources);
        when(kubernetesClient.getNamespace()).thenReturn("test");
        when(podFactory.createPodSpec("custom-demo-", "demo", List.of("--verbose"), resources)).thenReturn(podSpec);
        when(kubernetesClient.resource(podSpec)).thenReturn(podHandle);
        when(podHandle.inNamespace("test")).thenReturn(podHandle);
        when(podHandle.create()).thenReturn(createdPod);
        when(kubernetesClient.resource(createdPod)).thenReturn(podHandle);
        when(podHandle.get()).thenReturn(runningPod);

        MockPodRef spawnedPod = podManager.spawnPod("demo");

        assertEquals(new MockPodRef("custom-demo-1", "10.0.0.1"), spawnedPod);
        verify(podFactory).createPodSpec("custom-demo-", "demo", List.of("--verbose"), resources);
        verify(podHandle).create();
        verify(podState).markStartupPodName("demo", "custom-demo-1");
        verify(podHandle).get();
        verify(kubernetesClient, never()).services();
    }

    @Test
    void spawnPodUsesConfiguredNamespaceWhenClientNamespaceIsMissing() {
        KubernetesClient kubernetesClient = mock(KubernetesClient.class, RETURNS_DEEP_STUBS);
        PodFactory podFactory = mock(PodFactory.class);
        WireMockOptions wireMockOptions = mock(WireMockOptions.class);
        MockFleetConfig config = mock(MockFleetConfig.class);
        @SuppressWarnings("unchecked")
        NamespaceableResource<Pod> podHandle = mock(NamespaceableResource.class);
        PodManager podManager = new PodManager();
        podManager.kubernetesClient = kubernetesClient;
        podManager.podFactory = podFactory;
        podManager.podState = mock(PodState.class);
        podManager.wireMockOptions = wireMockOptions;
        podManager.config = config;
        podManager.podCreationTimeout = Duration.ofSeconds(1);

        Pod podSpec = podWithGenerateName("mock-fleet-demo-");
        Pod createdPod = pod("mock-fleet-demo-1", "Pending", false);
        Pod runningPod = pod("mock-fleet-demo-1", "Running", true);
        when(config.namespace()).thenReturn("mock-fleet");
        when(config.wiremockPodNamePrefix()).thenReturn("mock-fleet");
        when(wireMockOptions.optionsFor("demo")).thenReturn(List.of());
        when(wireMockOptions.resourcesFor("demo")).thenReturn(null);
        when(kubernetesClient.getNamespace()).thenReturn(null);
        when(podFactory.createPodSpec("mock-fleet-demo-", "demo", List.of(), null)).thenReturn(podSpec);
        when(kubernetesClient.resource(podSpec)).thenReturn(podHandle);
        when(podHandle.inNamespace("mock-fleet")).thenReturn(podHandle);
        when(podHandle.create()).thenReturn(createdPod);
        when(kubernetesClient.resource(createdPod)).thenReturn(podHandle);
        when(podHandle.get()).thenReturn(runningPod);

        MockPodRef spawnedPod = podManager.spawnPod("demo");

        assertEquals(new MockPodRef("mock-fleet-demo-1", "10.0.0.1"), spawnedPod);
        verify(podHandle).inNamespace("mock-fleet");
        verify(kubernetesClient, never()).services();
    }

    @Test
    void spawnPodFailsWhenReadyPodHasNoPodIp() {
        KubernetesClient kubernetesClient = mock(KubernetesClient.class, RETURNS_DEEP_STUBS);
        PodFactory podFactory = mock(PodFactory.class);
        WireMockOptions wireMockOptions = mock(WireMockOptions.class);
        MockFleetConfig config = mock(MockFleetConfig.class);
        @SuppressWarnings("unchecked")
        NamespaceableResource<Pod> podHandle = mock(NamespaceableResource.class);
        PodManager podManager = new PodManager();
        podManager.kubernetesClient = kubernetesClient;
        podManager.podFactory = podFactory;
        podManager.podState = mock(PodState.class);
        podManager.wireMockOptions = wireMockOptions;
        podManager.config = config;
        podManager.podCreationTimeout = Duration.ofSeconds(1);

        Pod podSpec = podWithGenerateName("mock-fleet-demo-");
        Pod createdPod = pod("mock-fleet-demo-1", "Pending", false);
        Pod runningPodWithoutIp = pod("mock-fleet-demo-1", "Running", true, "");
        when(config.namespace()).thenReturn("mock-fleet");
        when(config.wiremockPodNamePrefix()).thenReturn("mock-fleet");
        when(wireMockOptions.optionsFor("demo")).thenReturn(List.of());
        when(wireMockOptions.resourcesFor("demo")).thenReturn(null);
        when(kubernetesClient.getNamespace()).thenReturn("test");
        when(podFactory.createPodSpec("mock-fleet-demo-", "demo", List.of(), null)).thenReturn(podSpec);
        when(kubernetesClient.resource(podSpec)).thenReturn(podHandle);
        when(podHandle.inNamespace("test")).thenReturn(podHandle);
        when(podHandle.create()).thenReturn(createdPod);
        when(kubernetesClient.resource(createdPod)).thenReturn(podHandle);
        when(podHandle.get()).thenReturn(runningPodWithoutIp);

        PodCreationException exception = assertThrows(PodCreationException.class, () -> podManager.spawnPod("demo"));
        assertEquals("Pod 'mock-fleet-demo-1' did not receive a pod IP.", exception.getMessage());
    }

    @Test
    void getUpstreamBaseUrlCachesNewlySpawnedPodRef() {
        KubernetesClient kubernetesClient = mock(KubernetesClient.class);
        PodState podState = mock(PodState.class);
        MockFleetConfig config = mock(MockFleetConfig.class);
        PodManager podManager = new PodManager() {
            @Override
            MockPodRef spawnPod(String mockId, String attemptId) {
                return new MockPodRef("mock-fleet-demo-1", "10.0.0.1");
            }
        };
        podManager.kubernetesClient = kubernetesClient;
        podManager.podState = podState;
        podManager.config = config;
        podManager.podCreationTimeout = Duration.ofSeconds(1);

        when(podState.getPod("demo")).thenReturn(null);
        when(podState.claimStart(eq("demo"), anyLong(), eq(2_000L))).thenReturn(new PodState.StartClaim(true,
                MockPodLifecycle.starting("attempt-1", null, 1_000L), null));
        when(podState.completeStart(eq("demo"), eq("attempt-1"), any())).thenReturn(true);

        String upstreamBaseUrl = podManager.getUpstreamBaseUrl("demo");

        assertEquals("http://10.0.0.1:8080", upstreamBaseUrl);
        verify(podState).setLastAccessTime(eq("mock-fleet-demo-1"), any());
        verify(kubernetesClient, never()).services();
        verify(kubernetesClient, never()).pods();
    }

    @Test
    void currentNamespaceFallsBackToConfiguredNamespaceWhenClientHasNoNamespace() {
        KubernetesClient kubernetesClient = mock(KubernetesClient.class);
        MockFleetConfig config = mock(MockFleetConfig.class);
        PodManager podManager = new PodManager();
        podManager.kubernetesClient = kubernetesClient;
        podManager.config = config;

        when(kubernetesClient.getNamespace()).thenReturn(null);
        when(config.namespace()).thenReturn("mock-fleet");

        assertEquals("mock-fleet", podManager.currentNamespace());
    }

    @Test
    void currentNamespaceFallsBackToHardcodedMockFleetWhenClientAndConfigAreMissing() {
        KubernetesClient kubernetesClient = mock(KubernetesClient.class);
        MockFleetConfig config = mock(MockFleetConfig.class);
        PodManager podManager = new PodManager();
        podManager.kubernetesClient = kubernetesClient;
        podManager.config = config;

        when(kubernetesClient.getNamespace()).thenReturn(null);
        when(config.namespace()).thenReturn("");

        assertEquals("mock-fleet", podManager.currentNamespace());
    }

    @Test
    void wasDeleteSuccessfulMatchesReturnedDeleteDetails() {
        PodManager podManager = new PodManager();

        assertTrue(podManager.wasDeleteSuccessful(List.of(mock(io.fabric8.kubernetes.api.model.StatusDetails.class))));
        assertFalse(podManager.wasDeleteSuccessful(List.of()));
        assertFalse(podManager.wasDeleteSuccessful(null));
    }

    @Test
    void podFactoryAddsStableLabelsAndConfiguredWireMockContainer() {
        MockFleetConfig config = mock(MockFleetConfig.class);
        MockFleetConfig.StorageConfig storageConfig = mock(MockFleetConfig.StorageConfig.class);
        when(config.wiremockContainerName()).thenReturn("wiremock");
        when(config.wiremockImage()).thenReturn("wiremock/wiremock:latest");
        when(config.wiremockImagePullPolicy()).thenReturn("Always");
        when(config.storage()).thenReturn(storageConfig);
        when(storageConfig.persistent()).thenReturn(false);
        when(storageConfig.type()).thenReturn(PodFactory.STORAGE_TYPE_S3);
        PodFactory podFactory = new PodFactory(config);

        Pod pod = podFactory.createPodSpec("mock-fleet-demo-", "demo");

        assertEquals(PodFactory.APP_NAME_VALUE, pod.getMetadata().getLabels().get(PodFactory.LABEL_APP_NAME));
        assertEquals(PodFactory.MANAGED_BY_VALUE, pod.getMetadata().getLabels().get(PodFactory.LABEL_MANAGED_BY));
        assertEquals("demo", pod.getMetadata().getLabels().get(PodFactory.LABEL_MOCK_ID));
        assertEquals("wiremock", pod.getSpec().getContainers().getFirst().getName());
        assertEquals("wiremock/wiremock:latest", pod.getSpec().getContainers().getFirst().getImage());
        assertEquals("Always", pod.getSpec().getContainers().getFirst().getImagePullPolicy());
        assertTrue(pod.getSpec().getContainers().getFirst().getArgs() == null
                || pod.getSpec().getContainers().getFirst().getArgs().isEmpty());
        assertEquals(PodFactory.WIREMOCK_HEALTH_PATH, pod.getSpec().getContainers().getFirst().getStartupProbe().getHttpGet().getPath());
        assertEquals(8080, pod.getSpec().getContainers().getFirst().getStartupProbe().getHttpGet().getPort().getIntVal());
        assertEquals(PodFactory.WIREMOCK_HEALTH_PATH, pod.getSpec().getContainers().getFirst().getReadinessProbe().getHttpGet().getPath());
        assertEquals(8080, pod.getSpec().getContainers().getFirst().getReadinessProbe().getHttpGet().getPort().getIntVal());
        assertEquals(PodFactory.WIREMOCK_HEALTH_PATH, pod.getSpec().getContainers().getFirst().getLivenessProbe().getHttpGet().getPath());
        assertTrue(pod.getSpec().getVolumes() == null || pod.getSpec().getVolumes().isEmpty());
        assertTrue(pod.getSpec().getInitContainers() == null || pod.getSpec().getInitContainers().isEmpty());
        assertTrue(pod.getSpec().getContainers().getFirst().getVolumeMounts() == null
                || pod.getSpec().getContainers().getFirst().getVolumeMounts().isEmpty());
    }

    @Test
    void podFactoryAddsWireMockOptionsAsContainerArgs() {
        MockFleetConfig config = mock(MockFleetConfig.class);
        MockFleetConfig.StorageConfig storageConfig = mock(MockFleetConfig.StorageConfig.class);
        when(config.wiremockContainerName()).thenReturn("wiremock");
        when(config.wiremockImage()).thenReturn("wiremock/wiremock:latest");
        when(config.wiremockImagePullPolicy()).thenReturn("IfNotPresent");
        when(config.storage()).thenReturn(storageConfig);
        when(storageConfig.persistent()).thenReturn(false);
        when(storageConfig.type()).thenReturn(PodFactory.STORAGE_TYPE_S3);
        PodFactory podFactory = new PodFactory(config);

        Pod pod = podFactory.createPodSpec("mock-fleet-demo-", "demo",
                List.of("--global-response-templating", "--verbose"));

        assertEquals(List.of("--global-response-templating", "--verbose"),
                pod.getSpec().getContainers().getFirst().getArgs());
    }

    @Test
    void podFactoryAddsConfiguredWireMockServiceAccount() {
        MockFleetConfig config = mock(MockFleetConfig.class);
        MockFleetConfig.StorageConfig storageConfig = mock(MockFleetConfig.StorageConfig.class);
        when(config.wiremockContainerName()).thenReturn("wiremock");
        when(config.wiremockImage()).thenReturn("wiremock/wiremock:latest");
        when(config.wiremockImagePullPolicy()).thenReturn("IfNotPresent");
        when(config.wiremockServiceAccountName()).thenReturn(java.util.Optional.of("wiremock-workload"));
        when(config.storage()).thenReturn(storageConfig);
        when(storageConfig.persistent()).thenReturn(false);
        PodFactory podFactory = new PodFactory(config);

        Pod pod = podFactory.createPodSpec("mock-fleet-demo-", "demo");

        assertEquals("wiremock-workload", pod.getSpec().getServiceAccountName());
    }

    @Test
    void podFactoryOmitsWireMockServiceAccountWhenNotConfigured() {
        MockFleetConfig config = mock(MockFleetConfig.class);
        MockFleetConfig.StorageConfig storageConfig = mock(MockFleetConfig.StorageConfig.class);
        when(config.wiremockContainerName()).thenReturn("wiremock");
        when(config.wiremockImage()).thenReturn("wiremock/wiremock:latest");
        when(config.wiremockImagePullPolicy()).thenReturn("IfNotPresent");
        when(config.wiremockServiceAccountName()).thenReturn(java.util.Optional.empty());
        when(config.storage()).thenReturn(storageConfig);
        when(storageConfig.persistent()).thenReturn(false);
        PodFactory podFactory = new PodFactory(config);

        Pod pod = podFactory.createPodSpec("mock-fleet-demo-", "demo");

        assertTrue(pod.getSpec().getServiceAccountName() == null
                || pod.getSpec().getServiceAccountName().isBlank());
    }

    @Test
    void podFactoryAddsWireMockResourcesToContainer() {
        MockFleetConfig config = mock(MockFleetConfig.class);
        MockFleetConfig.StorageConfig storageConfig = mock(MockFleetConfig.StorageConfig.class);
        when(config.wiremockContainerName()).thenReturn("wiremock");
        when(config.wiremockImage()).thenReturn("wiremock/wiremock:latest");
        when(config.wiremockImagePullPolicy()).thenReturn("IfNotPresent");
        when(config.storage()).thenReturn(storageConfig);
        when(storageConfig.persistent()).thenReturn(false);
        when(storageConfig.type()).thenReturn(PodFactory.STORAGE_TYPE_S3);
        PodFactory podFactory = new PodFactory(config);

        ResourceRequirements resources = resources("0.5", "512Mi", "1", "1Gi");
        Pod pod = podFactory.createPodSpec("mock-fleet-demo-", "demo", List.of(), resources);

        assertEquals(resources, pod.getSpec().getContainers().getFirst().getResources());
    }

    @Test
    void podFactoryAllowsUnsupportedStorageTypeWhenStorageIsNotPersistent() {
        MockFleetConfig config = mock(MockFleetConfig.class);
        MockFleetConfig.StorageConfig storageConfig = mock(MockFleetConfig.StorageConfig.class);
        when(config.wiremockContainerName()).thenReturn("custom-wiremock");
        when(config.wiremockImage()).thenReturn("example.com/wiremock:test");
        when(config.wiremockImagePullPolicy()).thenReturn("Never");
        when(config.storage()).thenReturn(storageConfig);
        when(storageConfig.persistent()).thenReturn(false);
        when(storageConfig.type()).thenReturn("emptyDir");
        PodFactory podFactory = new PodFactory(config);

        Pod pod = podFactory.createPodSpec("mock-fleet-demo-", "demo");

        assertEquals("custom-wiremock", pod.getSpec().getContainers().getFirst().getName());
        assertEquals("example.com/wiremock:test", pod.getSpec().getContainers().getFirst().getImage());
        assertEquals("Never", pod.getSpec().getContainers().getFirst().getImagePullPolicy());
        assertTrue(pod.getSpec().getVolumes() == null || pod.getSpec().getVolumes().isEmpty());
    }

    @Test
    void podFactoryMountsPersistentS3Storage() {
        MockFleetConfig config = mock(MockFleetConfig.class);
        MockFleetConfig.StorageConfig storageConfig = mock(MockFleetConfig.StorageConfig.class);
        MockFleetConfig.S3Config s3Config = mock(MockFleetConfig.S3Config.class);
        when(config.wiremockContainerName()).thenReturn("wiremock");
        when(config.wiremockImage()).thenReturn("wiremock/wiremock:latest");
        when(config.wiremockImagePullPolicy()).thenReturn("IfNotPresent");
        when(config.storage()).thenReturn(storageConfig);
        when(storageConfig.persistent()).thenReturn(true);
        when(storageConfig.type()).thenReturn(PodFactory.STORAGE_TYPE_S3);
        when(storageConfig.pvcName()).thenReturn("mock-fleet-pvc");
        when(storageConfig.s3()).thenReturn(s3Config);
        when(s3Config.path()).thenReturn("/mock-fleet");
        PodFactory podFactory = new PodFactory(config);

        Pod pod = podFactory.createPodSpec("mock-fleet-demo-", "demo");

        assertEquals("mock-fleet-pvc",
                pod.getSpec().getVolumes().getFirst().getPersistentVolumeClaim().getClaimName());
        assertEquals(PodFactory.WIREMOCK_MAPPINGS_VOLUME,
                pod.getSpec().getContainers().getFirst().getVolumeMounts().getFirst().getName());
        assertEquals(PodFactory.WIREMOCK_ROOT_DIR,
                pod.getSpec().getContainers().getFirst().getVolumeMounts().getFirst().getMountPath());
        assertEquals("demo", pod.getSpec().getContainers().getFirst().getVolumeMounts().getFirst().getSubPath());
        assertEquals(PodFactory.INIT_MAPPINGS_CONTAINER, pod.getSpec().getInitContainers().getFirst().getName());
        assertEquals(List.of("mkdir", "-p", "/mock-fleet/demo"),
                pod.getSpec().getInitContainers().getFirst().getCommand());
        assertEquals("/mock-fleet", pod.getSpec().getInitContainers().getFirst().getVolumeMounts().getFirst().getMountPath());
    }

    @Test
    void podFactoryRejectsUnsupportedPersistentStorageType() {
        MockFleetConfig config = mock(MockFleetConfig.class);
        MockFleetConfig.StorageConfig storageConfig = mock(MockFleetConfig.StorageConfig.class);
        when(config.wiremockContainerName()).thenReturn("wiremock");
        when(config.wiremockImage()).thenReturn("wiremock/wiremock:latest");
        when(config.wiremockImagePullPolicy()).thenReturn("IfNotPresent");
        when(config.storage()).thenReturn(storageConfig);
        when(storageConfig.persistent()).thenReturn(true);
        when(storageConfig.type()).thenReturn("emptyDir");
        PodFactory podFactory = new PodFactory(config);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> podFactory.createPodSpec("mock-fleet-demo-", "demo"));
        assertEquals("Unsupported persistent storage type: emptyDir", exception.getMessage());
    }

    private Pod pod(String name, String phase) {
        return pod(name, phase, "Running".equalsIgnoreCase(phase));
    }

    private Pod pod(String name, String phase, boolean ready) {
        return pod(name, phase, ready, "10.0.0.1");
    }

    private Pod pod(String name, String phase, boolean ready, String podIp) {
        PodStatusBuilder statusBuilder = new PodStatusBuilder()
                .withPhase(phase)
                .withPodIP(podIp);
        if ("Running".equalsIgnoreCase(phase)) {
            statusBuilder.withConditions(new PodConditionBuilder()
                    .withType("Ready")
                    .withStatus(ready ? "True" : "False")
                    .build());
        }

        return new PodBuilder()
                .withMetadata(new ObjectMetaBuilder().withName(name).build())
                .withStatus(statusBuilder.build())
                .build();
    }

    private Pod podWithGenerateName(String generateName) {
        return new PodBuilder()
                .withMetadata(new ObjectMetaBuilder().withGenerateName(generateName).build())
                .build();
    }

    private ResourceRequirements resources(String requestCpu, String requestMemory, String limitCpu, String limitMemory) {
        return new ResourceRequirementsBuilder()
                .addToRequests("cpu", new Quantity(requestCpu))
                .addToRequests("memory", new Quantity(requestMemory))
                .addToLimits("cpu", new Quantity(limitCpu))
                .addToLimits("memory", new Quantity(limitMemory))
                .build();
    }
}
