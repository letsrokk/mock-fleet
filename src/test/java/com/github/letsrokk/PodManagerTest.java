package com.github.letsrokk;

import com.github.letsrokk.exceptions.MockIdNotFound;
import com.github.letsrokk.exceptions.PodCreationException;
import com.hazelcast.map.IMap;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.PodStatusBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
    void cleanUpIdlePodsDeletesOnlyStalePodsWithRecordedAccessTime() {
        KubernetesClient kubernetesClient = mock(KubernetesClient.class, RETURNS_DEEP_STUBS);
        PodState podState = mock(PodState.class);
        @SuppressWarnings("unchecked")
        IMap<String, Pod> pods = mock(IMap.class);
        PodManager podManager = new PodManager();
        podManager.kubernetesClient = kubernetesClient;
        podManager.podState = podState;
        podManager.inactivityThreshold = Duration.ofSeconds(30);

        Pod stalePod = pod("stale-pod", "Running");
        Pod currentPod = pod("current-pod", "Running");
        Pod unknownPod = pod("unknown-pod", "Running");

        when(podState.getPods()).thenReturn(pods);
        when(podState.getLastAccessTime("stale-pod")).thenReturn(System.currentTimeMillis() - 60_000);
        when(podState.getLastAccessTime("current-pod")).thenReturn(System.currentTimeMillis());
        when(podState.getLastAccessTime("unknown-pod")).thenReturn(null);
        when(kubernetesClient.resource(stalePod).delete()).thenReturn(List.of(mock(io.fabric8.kubernetes.api.model.StatusDetails.class)));
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
        verify(kubernetesClient.resource(currentPod), never()).delete();
        verify(kubernetesClient.resource(unknownPod), never()).delete();
    }

    @Test
    void cleanUpOrphanedPodsUsesManagedByLabelAndDeletesOnlyOrphans() {
        KubernetesClient kubernetesClient = mock(KubernetesClient.class, RETURNS_DEEP_STUBS);
        PodState podState = mock(PodState.class);
        @SuppressWarnings("unchecked")
        IMap<String, Pod> pods = mock(IMap.class);
        @SuppressWarnings("rawtypes")
        io.fabric8.kubernetes.client.dsl.MixedOperation podOperations = mock(io.fabric8.kubernetes.client.dsl.MixedOperation.class);
        @SuppressWarnings("rawtypes")
        io.fabric8.kubernetes.client.dsl.NonNamespaceOperation namespacedPods = mock(io.fabric8.kubernetes.client.dsl.NonNamespaceOperation.class);
        PodManager podManager = new PodManager();
        podManager.kubernetesClient = kubernetesClient;
        podManager.podState = podState;

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
        when(pods.values()).thenReturn(List.of(ownedPod));
        when(kubernetesClient.resource(orphanPod).delete()).thenReturn(List.of(mock(io.fabric8.kubernetes.api.model.StatusDetails.class)));

        podManager.cleanUpOrphanedPods();

        verify(namespacedPods).withLabel(PodFactory.LABEL_MANAGED_BY, PodFactory.MANAGED_BY_VALUE);
        verify(kubernetesClient.resource(orphanPod)).delete();
        verify(kubernetesClient.resource(ownedPod), never()).delete();
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
}
