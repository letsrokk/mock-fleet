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
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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
    void deleteMockReturnsNotFoundWhenMockIsMissing() {
        PodState podState = mock(PodState.class);
        PodManager podManager = new PodManager();
        podManager.podState = podState;

        when(podState.getPod("demo")).thenReturn(null);

        assertEquals(PodManager.DeleteMockResult.NOT_FOUND, podManager.deleteMock("demo"));
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

        when(podState.getPod("demo")).thenReturn(pod);
        when(kubernetesClient.getNamespace()).thenReturn("test");
        when(kubernetesClient.pods()).thenReturn(podOperations);
        when(podOperations.inNamespace("test")).thenReturn(namespacedPods);
        when(namespacedPods.withName("mock-fleet-demo-1")).thenReturn(podResource);
        when(podResource.delete()).thenReturn(List.of(mock(io.fabric8.kubernetes.api.model.StatusDetails.class)));

        assertEquals(PodManager.DeleteMockResult.DELETED, podManager.deleteMock("demo"));
        verify(podState).removePod("demo");
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
        when(podState.getPod("demo")).thenReturn(pod);
        when(kubernetesClient.getNamespace()).thenReturn("test");
        when(kubernetesClient.pods()).thenReturn(podOperations);
        when(podOperations.inNamespace("test")).thenReturn(namespacedPods);
        when(namespacedPods.withName("mock-fleet-demo-1")).thenReturn(podResource);
        when(podResource.delete()).thenReturn(List.of());

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

        when(podState.getPod(eq("demo"), any())).thenReturn(new MockPodRef("mock-fleet-demo-1", "10.0.0.1"));

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
        MixedOperation<Pod, PodList, PodResource> podOperations = mock(MixedOperation.class);
        @SuppressWarnings("unchecked")
        NonNamespaceOperation<Pod, PodList, PodResource> namespacedPods = mock(NonNamespaceOperation.class);
        MockFleetConfig config = mock(MockFleetConfig.class);
        PodManager podManager = new PodManager();
        podManager.kubernetesClient = kubernetesClient;
        podManager.podState = podState;
        podManager.config = config;

        Pod ownedPod = pod("owned-pod", "Running");
        Pod orphanPod = pod("orphan-pod", "Running");
        PodList podList = new PodList();
        podList.setItems(List.of(ownedPod, orphanPod));

        when(kubernetesClient.getNamespace()).thenReturn("test");
        when(kubernetesClient.pods()).thenReturn(podOperations);
        when(podOperations.inNamespace("test")).thenReturn(namespacedPods);
        when(namespacedPods.withLabel(PodFactory.LABEL_MANAGED_BY, PodFactory.MANAGED_BY_VALUE)).thenReturn(namespacedPods);
        when(namespacedPods.list()).thenReturn(podList);
        when(podState.getPods()).thenReturn(pods);
        when(pods.values()).thenReturn(List.of(new MockPodRef("owned-pod", "10.0.0.1")));
        when(kubernetesClient.resource(orphanPod).delete()).thenReturn(List.of(mock(io.fabric8.kubernetes.api.model.StatusDetails.class)));

        podManager.cleanUpOrphanedPods();

        verify(namespacedPods).withLabel(PodFactory.LABEL_MANAGED_BY, PodFactory.MANAGED_BY_VALUE);
        verify(kubernetesClient.resource(orphanPod)).delete();
        verify(kubernetesClient.resource(ownedPod), never()).delete();
        verify(kubernetesClient, never()).services();
    }

    @Test
    void spawnPodCreatesPodWaitsForRunningStateAndReturnsPodRef() {
        KubernetesClient kubernetesClient = mock(KubernetesClient.class, RETURNS_DEEP_STUBS);
        PodFactory podFactory = mock(PodFactory.class);
        WireMockOptions wireMockOptions = mock(WireMockOptions.class);
        MockFleetConfig config = mock(MockFleetConfig.class);
        @SuppressWarnings("unchecked")
        NamespaceableResource<Pod> podHandle = mock(NamespaceableResource.class);
        PodManager podManager = new PodManager();
        podManager.kubernetesClient = kubernetesClient;
        podManager.podFactory = podFactory;
        podManager.wireMockOptions = wireMockOptions;
        podManager.config = config;
        podManager.podCreationTimeout = Duration.ofSeconds(1);

        Pod podSpec = podWithGenerateName("mock-fleet-demo-");
        Pod createdPod = pod("mock-fleet-demo-1", "Pending", false);
        Pod runningPod = pod("mock-fleet-demo-1", "Running", true);
        ResourceRequirements resources = resources("0.5", "512Mi", "1", "1Gi");
        when(config.namespace()).thenReturn("mock-fleet");
        when(wireMockOptions.optionsFor("demo")).thenReturn(List.of("--verbose"));
        when(wireMockOptions.resourcesFor("demo")).thenReturn(resources);
        when(kubernetesClient.getNamespace()).thenReturn("test");
        when(podFactory.createPodSpec("mock-fleet-demo-", "demo", List.of("--verbose"), resources)).thenReturn(podSpec);
        when(kubernetesClient.resource(podSpec)).thenReturn(podHandle);
        when(podHandle.inNamespace("test")).thenReturn(podHandle);
        when(podHandle.create()).thenReturn(createdPod);
        when(kubernetesClient.resource(createdPod)).thenReturn(podHandle);
        when(podHandle.get()).thenReturn(runningPod);

        MockPodRef spawnedPod = podManager.spawnPod("demo");

        assertEquals(new MockPodRef("mock-fleet-demo-1", "10.0.0.1"), spawnedPod);
        verify(podFactory).createPodSpec("mock-fleet-demo-", "demo", List.of("--verbose"), resources);
        verify(podHandle).create();
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
        podManager.wireMockOptions = wireMockOptions;
        podManager.config = config;
        podManager.podCreationTimeout = Duration.ofSeconds(1);

        Pod podSpec = podWithGenerateName("mock-fleet-demo-");
        Pod createdPod = pod("mock-fleet-demo-1", "Pending", false);
        Pod runningPod = pod("mock-fleet-demo-1", "Running", true);
        when(config.namespace()).thenReturn("mock-fleet");
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
        podManager.wireMockOptions = wireMockOptions;
        podManager.config = config;
        podManager.podCreationTimeout = Duration.ofSeconds(1);

        Pod podSpec = podWithGenerateName("mock-fleet-demo-");
        Pod createdPod = pod("mock-fleet-demo-1", "Pending", false);
        Pod runningPodWithoutIp = pod("mock-fleet-demo-1", "Running", true, "");
        when(config.namespace()).thenReturn("mock-fleet");
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
            public MockPodRef spawnPod(String mockId) {
                return new MockPodRef("mock-fleet-demo-1", "10.0.0.1");
            }
        };
        podManager.kubernetesClient = kubernetesClient;
        podManager.podState = podState;
        podManager.config = config;

        when(podState.getPod(eq("demo"), any())).thenAnswer(invocation -> {
            java.util.function.Function<String, MockPodRef> mappingFunction = invocation.getArgument(1);
            return mappingFunction.apply("demo");
        });

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
    void podFactoryAddsWireMockResourcesToContainer() {
        MockFleetConfig config = mock(MockFleetConfig.class);
        MockFleetConfig.StorageConfig storageConfig = mock(MockFleetConfig.StorageConfig.class);
        when(config.wiremockContainerName()).thenReturn("wiremock");
        when(config.wiremockImage()).thenReturn("wiremock/wiremock:latest");
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
        when(config.storage()).thenReturn(storageConfig);
        when(storageConfig.persistent()).thenReturn(false);
        when(storageConfig.type()).thenReturn("emptyDir");
        PodFactory podFactory = new PodFactory(config);

        Pod pod = podFactory.createPodSpec("mock-fleet-demo-", "demo");

        assertEquals("custom-wiremock", pod.getSpec().getContainers().getFirst().getName());
        assertEquals("example.com/wiremock:test", pod.getSpec().getContainers().getFirst().getImage());
        assertTrue(pod.getSpec().getVolumes() == null || pod.getSpec().getVolumes().isEmpty());
    }

    @Test
    void podFactoryMountsPersistentS3Storage() {
        MockFleetConfig config = mock(MockFleetConfig.class);
        MockFleetConfig.StorageConfig storageConfig = mock(MockFleetConfig.StorageConfig.class);
        MockFleetConfig.S3Config s3Config = mock(MockFleetConfig.S3Config.class);
        when(config.wiremockContainerName()).thenReturn("wiremock");
        when(config.wiremockImage()).thenReturn("wiremock/wiremock:latest");
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
        assertEquals(PodFactory.WIREMOCK_MAPPINGS_PATH,
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
