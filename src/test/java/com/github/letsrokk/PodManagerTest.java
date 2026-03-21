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
import io.fabric8.kubernetes.client.LocalPortForward;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.PodResource;
import io.fabric8.kubernetes.client.dsl.ServiceResource;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.time.Duration;
import java.util.List;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PodManagerTest {

    @Test
    void extractMockIdSupportsPortSuffixedHosts() {
        PodManager podManager = new PodManager();

        assertEquals("demo", podManager.extractMockId("demo.example.test:8080"));
    }

    @Test
    void extractMockIdNormalizesEdgeCaseValues() {
        PodManager podManager = new PodManager();

        assertEquals("mixedname", podManager.extractMockId("MiXeD_Name.example.test"));
    }

    @Test
    void extractMockIdRejectsMissingHost() {
        PodManager podManager = new PodManager();

        assertThrows(MockIdNotFound.class, () -> podManager.extractMockId("   "));
    }

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
    void getUpstreamBaseUrlCreatesServiceAndReturnsClusterDnsName() {
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
        when(podState.getPod(eq("demo"), any())).thenReturn(pod);

        String upstreamBaseUrl = podManager.getUpstreamBaseUrl("demo.example.test");

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

        String upstreamBaseUrl = podManager.getUpstreamBaseUrl("demo.example.test");

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

    private Service service(String name) {
        return new ServiceBuilder()
                .withMetadata(new ObjectMetaBuilder().withName(name).build())
                .build();
    }
}
