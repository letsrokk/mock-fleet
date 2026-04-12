package com.github.letsrokk;

import com.github.letsrokk.exceptions.MockIdNotFound;
import com.github.letsrokk.exceptions.PodCreationException;
import com.hazelcast.map.IMap;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.PodStatusBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.ServiceList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NamespaceableResource;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.PodResource;
import io.fabric8.kubernetes.client.dsl.ServiceResource;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PodManagerTest {

    @Test
    void waitForPodToBeRunningTimesOutCleanly() {
        KubernetesClient kubernetesClient = mock(KubernetesClient.class, RETURNS_DEEP_STUBS);
        PodManager podManager = new PodManager();
        podManager.kubernetesClient = kubernetesClient;

        Pod pod = pod("mock-fleet-test-1", "Pending");
        when(kubernetesClient.resource(pod).get()).thenReturn(pod);

        assertThrows(PodCreationException.class, () -> podManager.waitForPodToBeRunning(pod, Duration.ofMillis(1)));
    }

    @Test
    void listActiveMocksReturnsSortedRows() {
        PodState podState = mock(PodState.class);
        @SuppressWarnings("unchecked")
        IMap<String, Pod> pods = mock(IMap.class);
        PodManager podManager = new PodManager();
        podManager.podState = podState;

        when(podState.getPods()).thenReturn(pods);
        when(pods.entrySet()).thenReturn(Map.of(
                "zeta", pod("mock-fleet-zeta-1", "Running"),
                "alpha", pod("mock-fleet-alpha-1", "Running")).entrySet());

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
    void deleteMockDeletesPodServiceAndState() {
        KubernetesClient kubernetesClient = mock(KubernetesClient.class, RETURNS_DEEP_STUBS);
        PodState podState = mock(PodState.class);
        ServiceFactory serviceFactory = new ServiceFactory();
        @SuppressWarnings("unchecked")
        MixedOperation<Service, ServiceList, ServiceResource<Service>> serviceOperations = mock(MixedOperation.class);
        @SuppressWarnings("unchecked")
        NonNamespaceOperation<Service, ServiceList, ServiceResource<Service>> namespacedServices = mock(NonNamespaceOperation.class);
        @SuppressWarnings("unchecked")
        ServiceResource<Service> serviceResource = mock(ServiceResource.class);
        LocalServicePortForwardManager localServicePortForwardManager = mock(LocalServicePortForwardManager.class);
        PodManager podManager = new PodManager();
        podManager.kubernetesClient = kubernetesClient;
        podManager.podState = podState;
        podManager.serviceFactory = serviceFactory;
        podManager.localServicePortForwardManager = localServicePortForwardManager;

        Pod pod = pod("mock-fleet-demo-1", "Running");
        Service service = service("mock-fleet-demo");

        when(podState.getPod("demo")).thenReturn(pod);
        when(kubernetesClient.resource(pod).delete()).thenReturn(List.of(mock(io.fabric8.kubernetes.api.model.StatusDetails.class)));
        when(kubernetesClient.getNamespace()).thenReturn("test");
        when(kubernetesClient.services()).thenReturn(serviceOperations);
        when(serviceOperations.inNamespace("test")).thenReturn(namespacedServices);
        when(namespacedServices.withName("mock-fleet-demo")).thenReturn(serviceResource);
        when(serviceResource.get()).thenReturn(service);
        when(kubernetesClient.resource(service).delete()).thenReturn(List.of(mock(io.fabric8.kubernetes.api.model.StatusDetails.class)));

        assertEquals(PodManager.DeleteMockResult.DELETED, podManager.deleteMock("demo"));
        verify(podState).removePod("demo");
        verify(localServicePortForwardManager).closeForMock("demo");
    }

    @Test
    void deleteMockReturnsFailedWhenPodDeletionFails() {
        KubernetesClient kubernetesClient = mock(KubernetesClient.class, RETURNS_DEEP_STUBS);
        PodState podState = mock(PodState.class);
        PodManager podManager = new PodManager();
        podManager.kubernetesClient = kubernetesClient;
        podManager.podState = podState;

        Pod pod = pod("mock-fleet-demo-1", "Running");
        when(podState.getPod("demo")).thenReturn(pod);
        when(kubernetesClient.resource(pod).delete()).thenReturn(List.of());

        assertEquals(PodManager.DeleteMockResult.FAILED, podManager.deleteMock("demo"));
        verify(podState, never()).removePod("demo");
    }

    @Test
    void getUpstreamBaseUrlEnsuresServiceForExistingPodAndReturnsClusterDnsName() {
        KubernetesClient kubernetesClient = mock(KubernetesClient.class, RETURNS_DEEP_STUBS);
        PodState podState = mock(PodState.class);
        ServiceFactory serviceFactory = new ServiceFactory();
        @SuppressWarnings("unchecked")
        MixedOperation<Service, ServiceList, ServiceResource<Service>> serviceOperations = mock(MixedOperation.class);
        @SuppressWarnings("unchecked")
        NonNamespaceOperation<Service, ServiceList, ServiceResource<Service>> namespacedServices = mock(NonNamespaceOperation.class);
        @SuppressWarnings("unchecked")
        ServiceResource<Service> serviceResource = mock(ServiceResource.class);
        LocalServicePortForwardManager localServicePortForwardManager = mock(LocalServicePortForwardManager.class);
        MockFleetConfig config = mock(MockFleetConfig.class);
        MockFleetConfig.LocalDebugConfig localDebugConfig = mock(MockFleetConfig.LocalDebugConfig.class);
        PodManager podManager = new PodManager();
        podManager.kubernetesClient = kubernetesClient;
        podManager.podState = podState;
        podManager.serviceFactory = serviceFactory;
        podManager.localServicePortForwardManager = localServicePortForwardManager;
        podManager.config = config;

        Pod pod = pod("mock-fleet-demo-1", "Running");

        when(config.localDebug()).thenReturn(localDebugConfig);
        when(localDebugConfig.enabled()).thenReturn(false);
        when(kubernetesClient.getNamespace()).thenReturn("test");
        when(kubernetesClient.services()).thenReturn(serviceOperations);
        when(serviceOperations.inNamespace("test")).thenReturn(namespacedServices);
        when(namespacedServices.withName("mock-fleet-demo")).thenReturn(serviceResource);
        when(serviceResource.get()).thenReturn(null);
        when(podState.getPod("demo")).thenReturn(pod);

        String upstreamBaseUrl = podManager.getUpstreamBaseUrl("demo");

        assertEquals("http://mock-fleet-demo.test.svc.cluster.local:8080", upstreamBaseUrl);
        verify(namespacedServices, times(1)).withName("mock-fleet-demo");
    }

    @Test
    void getUpstreamBaseUrlUsesLocalPortForwardWhenLocalDebugEnabled() throws Exception {
        KubernetesClient kubernetesClient = mock(KubernetesClient.class, RETURNS_DEEP_STUBS);
        PodState podState = mock(PodState.class);
        ServiceFactory serviceFactory = new ServiceFactory();
        LocalServicePortForwardManager localServicePortForwardManager = mock(LocalServicePortForwardManager.class);
        MockFleetConfig config = mock(MockFleetConfig.class);
        MockFleetConfig.LocalDebugConfig localDebugConfig = mock(MockFleetConfig.LocalDebugConfig.class);
        @SuppressWarnings("unchecked")
        MixedOperation<Service, ServiceList, ServiceResource<Service>> serviceOperations = mock(MixedOperation.class);
        @SuppressWarnings("unchecked")
        NonNamespaceOperation<Service, ServiceList, ServiceResource<Service>> namespacedServices = mock(NonNamespaceOperation.class);
        @SuppressWarnings("unchecked")
        ServiceResource<Service> serviceResource = mock(ServiceResource.class);
        PodManager podManager = new PodManager();
        podManager.kubernetesClient = kubernetesClient;
        podManager.podState = podState;
        podManager.serviceFactory = serviceFactory;
        podManager.localServicePortForwardManager = localServicePortForwardManager;
        podManager.config = config;

        Pod pod = pod("mock-fleet-demo-1", "Running");

        when(config.localDebug()).thenReturn(localDebugConfig);
        when(localDebugConfig.enabled()).thenReturn(true);
        when(kubernetesClient.getNamespace()).thenReturn("test");
        when(kubernetesClient.services()).thenReturn(serviceOperations);
        when(serviceOperations.inNamespace("test")).thenReturn(namespacedServices);
        when(namespacedServices.withName("mock-fleet-demo")).thenReturn(serviceResource);
        when(serviceResource.get()).thenReturn(service("mock-fleet-demo"));
        when(podState.getPod(eq("demo"), any())).thenReturn(pod);
        when(localServicePortForwardManager.getOrCreateForwardBaseUrl("demo", "test")).thenReturn("http://127.0.0.1:18080");

        String upstreamBaseUrl = podManager.getUpstreamBaseUrl("demo");

        assertEquals("http://127.0.0.1:18080", upstreamBaseUrl);
        verify(localServicePortForwardManager).getOrCreateForwardBaseUrl("demo", "test");
    }

    @Test
    void cleanUpIdlePodsDeletesOnlyStalePodsWithRecordedAccessTime() {
        KubernetesClient kubernetesClient = mock(KubernetesClient.class, RETURNS_DEEP_STUBS);
        PodState podState = mock(PodState.class);
        @SuppressWarnings("unchecked")
        IMap<String, Pod> pods = mock(IMap.class);
        @SuppressWarnings("unchecked")
        MixedOperation<Service, ServiceList, ServiceResource<Service>> serviceOperations = mock(MixedOperation.class);
        @SuppressWarnings("unchecked")
        NonNamespaceOperation<Service, ServiceList, ServiceResource<Service>> namespacedServices = mock(NonNamespaceOperation.class);
        @SuppressWarnings("unchecked")
        ServiceResource<Service> serviceResource = mock(ServiceResource.class);
        LocalServicePortForwardManager localServicePortForwardManager = mock(LocalServicePortForwardManager.class);
        MockFleetConfig config = mock(MockFleetConfig.class);
        MockFleetConfig.LocalDebugConfig localDebugConfig = mock(MockFleetConfig.LocalDebugConfig.class);
        PodManager podManager = new PodManager();
        podManager.kubernetesClient = kubernetesClient;
        podManager.podState = podState;
        podManager.serviceFactory = new ServiceFactory();
        podManager.localServicePortForwardManager = localServicePortForwardManager;
        podManager.config = config;
        podManager.inactivityThreshold = Duration.ofSeconds(30);

        Pod stalePod = pod("stale-pod", "Running");
        Pod currentPod = pod("current-pod", "Running");
        Pod unknownPod = pod("unknown-pod", "Running");
        Service staleService = service("mock-fleet-stale");

        when(config.localDebug()).thenReturn(localDebugConfig);
        when(localDebugConfig.enabled()).thenReturn(false);
        when(kubernetesClient.getNamespace()).thenReturn("test");
        when(kubernetesClient.services()).thenReturn(serviceOperations);
        when(serviceOperations.inNamespace("test")).thenReturn(namespacedServices);
        when(namespacedServices.withName("mock-fleet-stale")).thenReturn(serviceResource);
        when(serviceResource.get()).thenReturn(staleService);
        when(podState.getPods()).thenReturn(pods);
        when(podState.getLastAccessTime("stale-pod")).thenReturn(System.currentTimeMillis() - 60_000);
        when(podState.getLastAccessTime("current-pod")).thenReturn(System.currentTimeMillis());
        when(podState.getLastAccessTime("unknown-pod")).thenReturn(null);
        when(kubernetesClient.resource(stalePod).delete()).thenReturn(List.of(mock(io.fabric8.kubernetes.api.model.StatusDetails.class)));
        when(kubernetesClient.resource(staleService).delete()).thenReturn(List.of(mock(io.fabric8.kubernetes.api.model.StatusDetails.class)));
        org.mockito.Mockito.doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            BiConsumer<String, Pod> consumer = invocation.getArgument(0);
            consumer.accept("stale", stalePod);
            consumer.accept("current", currentPod);
            consumer.accept("unknown", unknownPod);
            return null;
        }).when(pods).forEach(org.mockito.ArgumentMatchers.<BiConsumer<String, Pod>>any());

        podManager.cleanUpIdlePods();

        verify(podState).removePod("stale");
        verify(kubernetesClient.resource(stalePod)).delete();
        verify(kubernetesClient.resource(staleService)).delete();
        verify(localServicePortForwardManager).closeForMock("stale");
        verify(kubernetesClient.resource(currentPod), never()).delete();
        verify(kubernetesClient.resource(unknownPod), never()).delete();
    }

    @Test
    void cleanUpOrphanedResourcesUseManagedByLabelAndDeleteOnlyOrphans() {
        KubernetesClient kubernetesClient = mock(KubernetesClient.class, RETURNS_DEEP_STUBS);
        PodState podState = mock(PodState.class);
        @SuppressWarnings("unchecked")
        IMap<String, Pod> pods = mock(IMap.class);
        @SuppressWarnings("unchecked")
        MixedOperation<Pod, PodList, PodResource> podOperations = mock(MixedOperation.class);
        @SuppressWarnings("unchecked")
        NonNamespaceOperation<Pod, PodList, PodResource> namespacedPods = mock(NonNamespaceOperation.class);
        @SuppressWarnings("unchecked")
        MixedOperation<Service, ServiceList, ServiceResource<Service>> serviceOperations = mock(MixedOperation.class);
        @SuppressWarnings("unchecked")
        NonNamespaceOperation<Service, ServiceList, ServiceResource<Service>> namespacedServices = mock(NonNamespaceOperation.class);
        LocalServicePortForwardManager localServicePortForwardManager = mock(LocalServicePortForwardManager.class);
        MockFleetConfig config = mock(MockFleetConfig.class);
        MockFleetConfig.LocalDebugConfig localDebugConfig = mock(MockFleetConfig.LocalDebugConfig.class);
        PodManager podManager = new PodManager();
        podManager.kubernetesClient = kubernetesClient;
        podManager.podState = podState;
        podManager.serviceFactory = new ServiceFactory();
        podManager.localServicePortForwardManager = localServicePortForwardManager;
        podManager.config = config;

        Pod ownedPod = pod("owned-pod", "Running");
        Pod orphanPod = pod("orphan-pod", "Running");
        PodList podList = new PodList();
        podList.setItems(List.of(ownedPod, orphanPod));
        Service ownedService = service("mock-fleet-owned");
        Service orphanService = service("mock-fleet-orphan");
        ServiceList serviceList = new ServiceList();
        serviceList.setItems(List.of(ownedService, orphanService));

        when(config.localDebug()).thenReturn(localDebugConfig);
        when(localDebugConfig.enabled()).thenReturn(false);
        when(kubernetesClient.getNamespace()).thenReturn("test");
        when(kubernetesClient.pods()).thenReturn(podOperations);
        when(podOperations.inNamespace("test")).thenReturn(namespacedPods);
        when(namespacedPods.withLabel(PodFactory.LABEL_MANAGED_BY, PodFactory.MANAGED_BY_VALUE)).thenReturn(namespacedPods);
        when(namespacedPods.list()).thenReturn(podList);
        when(kubernetesClient.services()).thenReturn(serviceOperations);
        when(serviceOperations.inNamespace("test")).thenReturn(namespacedServices);
        when(namespacedServices.withLabel(PodFactory.LABEL_MANAGED_BY, PodFactory.MANAGED_BY_VALUE)).thenReturn(namespacedServices);
        when(namespacedServices.list()).thenReturn(serviceList);
        when(podState.getPods()).thenReturn(pods);
        when(pods.values()).thenReturn(List.of(ownedPod));
        when(pods.keySet()).thenReturn(java.util.Set.of("owned"));
        when(kubernetesClient.resource(orphanPod).delete()).thenReturn(List.of(mock(io.fabric8.kubernetes.api.model.StatusDetails.class)));
        when(kubernetesClient.resource(orphanService).delete()).thenReturn(List.of(mock(io.fabric8.kubernetes.api.model.StatusDetails.class)));

        podManager.cleanUpOrphanedPods();

        verify(namespacedPods).withLabel(PodFactory.LABEL_MANAGED_BY, PodFactory.MANAGED_BY_VALUE);
        verify(namespacedServices).withLabel(PodFactory.LABEL_MANAGED_BY, PodFactory.MANAGED_BY_VALUE);
        verify(kubernetesClient.resource(orphanPod)).delete();
        verify(kubernetesClient.resource(orphanService)).delete();
        verify(localServicePortForwardManager).close("mock-fleet-orphan");
        verify(kubernetesClient.resource(ownedPod), never()).delete();
        verify(kubernetesClient.resource(ownedService), never()).delete();
    }

    @Test
    void spawnPodCreatesPodWaitsForRunningStateAndCreatesService() {
        KubernetesClient kubernetesClient = mock(KubernetesClient.class);
        PodFactory podFactory = mock(PodFactory.class);
        ServiceFactory serviceFactory = new ServiceFactory();
        @SuppressWarnings("unchecked")
        NamespaceableResource<Pod> podHandle = mock(NamespaceableResource.class);
        @SuppressWarnings("unchecked")
        NamespaceableResource<Service> serviceHandle = mock(NamespaceableResource.class);
        @SuppressWarnings("unchecked")
        MixedOperation<Service, ServiceList, ServiceResource<Service>> serviceOperations = mock(MixedOperation.class);
        @SuppressWarnings("unchecked")
        NonNamespaceOperation<Service, ServiceList, ServiceResource<Service>> namespacedServices = mock(NonNamespaceOperation.class);
        @SuppressWarnings("unchecked")
        ServiceResource<Service> serviceResource = mock(ServiceResource.class);
        PodManager podManager = new PodManager();
        podManager.kubernetesClient = kubernetesClient;
        podManager.podFactory = podFactory;
        podManager.serviceFactory = serviceFactory;
        podManager.podCreationTimeout = Duration.ofSeconds(1);

        Pod podSpec = podWithGenerateName("mock-fleet-demo-");
        Pod createdPod = pod("mock-fleet-demo-1", "Pending");
        Pod runningPod = pod("mock-fleet-demo-1", "Running");
        when(kubernetesClient.getNamespace()).thenReturn("test");
        when(podFactory.createPodSpec("mock-fleet-demo-", "demo")).thenReturn(podSpec);
        when(kubernetesClient.resource(podSpec)).thenReturn(podHandle);
        when(podHandle.inNamespace("test")).thenReturn(podHandle);
        when(podHandle.create()).thenReturn(createdPod);
        when(kubernetesClient.resource(createdPod)).thenReturn(podHandle);
        when(podHandle.get()).thenReturn(runningPod);
        when(kubernetesClient.services()).thenReturn(serviceOperations);
        when(serviceOperations.inNamespace("test")).thenReturn(namespacedServices);
        when(namespacedServices.withName("mock-fleet-demo")).thenReturn(serviceResource);
        when(serviceResource.get()).thenReturn(null);
        when(kubernetesClient.resource(any(Service.class))).thenReturn(serviceHandle);
        when(serviceHandle.inNamespace("test")).thenReturn(serviceHandle);
        when(serviceHandle.create()).thenReturn(service("mock-fleet-demo"));

        Pod spawnedPod = podManager.spawnPod("demo");

        assertEquals(runningPod, spawnedPod);
        verify(podFactory).createPodSpec("mock-fleet-demo-", "demo");
        verify(podHandle).create();
        verify(serviceHandle).create();
    }

    @Test
    void ensureServiceExistsTreatsAlreadyExistsConflictAsSuccess() {
        KubernetesClient kubernetesClient = mock(KubernetesClient.class);
        ServiceFactory serviceFactory = new ServiceFactory();
        @SuppressWarnings("unchecked")
        NamespaceableResource<Service> serviceHandle = mock(NamespaceableResource.class);
        @SuppressWarnings("unchecked")
        MixedOperation<Service, ServiceList, ServiceResource<Service>> serviceOperations = mock(MixedOperation.class);
        @SuppressWarnings("unchecked")
        NonNamespaceOperation<Service, ServiceList, ServiceResource<Service>> namespacedServices = mock(NonNamespaceOperation.class);
        @SuppressWarnings("unchecked")
        ServiceResource<Service> serviceResource = mock(ServiceResource.class);
        PodManager podManager = new PodManager();
        podManager.kubernetesClient = kubernetesClient;
        podManager.serviceFactory = serviceFactory;

        when(kubernetesClient.getNamespace()).thenReturn("test");
        when(kubernetesClient.services()).thenReturn(serviceOperations);
        when(serviceOperations.inNamespace("test")).thenReturn(namespacedServices);
        when(namespacedServices.withName("mock-fleet-demo")).thenReturn(serviceResource);
        when(serviceResource.get()).thenReturn(null);
        when(kubernetesClient.resource(any(Service.class))).thenReturn(serviceHandle);
        when(serviceHandle.inNamespace("test")).thenReturn(serviceHandle);
        when(serviceHandle.create()).thenThrow(new KubernetesClientException("already exists", 409, null));

        podManager.ensureServiceExists("demo");

        verify(serviceHandle).create();
    }

    @Test
    void getUpstreamBaseUrlDoesNotRecreateServiceForNewlySpawnedPod() {
        KubernetesClient kubernetesClient = mock(KubernetesClient.class);
        PodState podState = mock(PodState.class);
        LocalServicePortForwardManager localServicePortForwardManager = mock(LocalServicePortForwardManager.class);
        MockFleetConfig config = mock(MockFleetConfig.class);
        MockFleetConfig.LocalDebugConfig localDebugConfig = mock(MockFleetConfig.LocalDebugConfig.class);
        PodManager podManager = new PodManager() {
            @Override
            public Pod spawnPod(String mockId) {
                return pod("mock-fleet-demo-1", "Running");
            }
        };
        podManager.kubernetesClient = kubernetesClient;
        podManager.podState = podState;
        podManager.serviceFactory = new ServiceFactory();
        podManager.localServicePortForwardManager = localServicePortForwardManager;
        podManager.config = config;

        when(config.localDebug()).thenReturn(localDebugConfig);
        when(localDebugConfig.enabled()).thenReturn(false);
        when(kubernetesClient.getNamespace()).thenReturn("test");
        when(podState.getPod("demo")).thenReturn(null);
        when(podState.getPod(eq("demo"), any())).thenAnswer(invocation -> {
            java.util.function.Function<String, Pod> mappingFunction = invocation.getArgument(1);
            return mappingFunction.apply("demo");
        });

        String upstreamBaseUrl = podManager.getUpstreamBaseUrl("demo");

        assertEquals("http://mock-fleet-demo.test.svc.cluster.local:8080", upstreamBaseUrl);
        verify(kubernetesClient, never()).services();
    }

    @Test
    void deleteServiceReturnsTrueWhenServiceIsAlreadyMissing() {
        KubernetesClient kubernetesClient = mock(KubernetesClient.class);
        ServiceFactory serviceFactory = new ServiceFactory();
        @SuppressWarnings("unchecked")
        MixedOperation<Service, ServiceList, ServiceResource<Service>> serviceOperations = mock(MixedOperation.class);
        @SuppressWarnings("unchecked")
        NonNamespaceOperation<Service, ServiceList, ServiceResource<Service>> namespacedServices = mock(NonNamespaceOperation.class);
        @SuppressWarnings("unchecked")
        ServiceResource<Service> serviceResource = mock(ServiceResource.class);
        LocalServicePortForwardManager localServicePortForwardManager = mock(LocalServicePortForwardManager.class);
        PodManager podManager = new PodManager();
        podManager.kubernetesClient = kubernetesClient;
        podManager.serviceFactory = serviceFactory;
        podManager.localServicePortForwardManager = localServicePortForwardManager;

        when(kubernetesClient.getNamespace()).thenReturn("test");
        when(kubernetesClient.services()).thenReturn(serviceOperations);
        when(serviceOperations.inNamespace("test")).thenReturn(namespacedServices);
        when(namespacedServices.withName("mock-fleet-demo")).thenReturn(serviceResource);
        when(serviceResource.get()).thenReturn(null);

        assertTrue(podManager.deleteService("demo"));
        verify(localServicePortForwardManager).closeForMock("demo");
        verify(kubernetesClient, never()).resource(any(Service.class));
    }

    @Test
    void currentNamespaceFallsBackToDefaultWhenClientHasNoNamespace() {
        KubernetesClient kubernetesClient = mock(KubernetesClient.class);
        PodManager podManager = new PodManager();
        podManager.kubernetesClient = kubernetesClient;

        when(kubernetesClient.getNamespace()).thenReturn(null);

        assertEquals("default", podManager.currentNamespace());
    }

    @Test
    void wasDeleteSuccessfulMatchesReturnedDeleteDetails() {
        PodManager podManager = new PodManager();

        assertTrue(podManager.wasDeleteSuccessful(List.of(mock(io.fabric8.kubernetes.api.model.StatusDetails.class))));
        assertFalse(podManager.wasDeleteSuccessful(List.of()));
        assertFalse(podManager.wasDeleteSuccessful(null));
    }

    @Test
    void podFactoryAddsStableLabelsAndPinnedImage() {
        MockFleetConfig config = mock(MockFleetConfig.class);
        when(config.wiremockImage()).thenReturn("wiremock/wiremock:3.9.2");
        PodFactory podFactory = new PodFactory(config);

        Pod pod = podFactory.createPodSpec("mock-fleet-demo-", "demo");

        assertEquals(PodFactory.APP_NAME_VALUE, pod.getMetadata().getLabels().get(PodFactory.LABEL_APP_NAME));
        assertEquals(PodFactory.MANAGED_BY_VALUE, pod.getMetadata().getLabels().get(PodFactory.LABEL_MANAGED_BY));
        assertEquals("demo", pod.getMetadata().getLabels().get(PodFactory.LABEL_MOCK_ID));
        assertEquals("wiremock/wiremock:3.9.2", pod.getSpec().getContainers().getFirst().getImage());
    }

    private Pod pod(String name, String phase) {
        return new PodBuilder()
                .withMetadata(new ObjectMetaBuilder().withName(name).build())
                .withStatus(new PodStatusBuilder().withPhase(phase).withPodIP("10.0.0.1").build())
                .build();
    }

    private Pod podWithGenerateName(String generateName) {
        return new PodBuilder()
                .withMetadata(new ObjectMetaBuilder().withGenerateName(generateName).build())
                .build();
    }

    private Service service(String name) {
        return new ServiceBuilder()
                .withMetadata(new ObjectMetaBuilder().withName(name).build())
                .build();
    }
}
