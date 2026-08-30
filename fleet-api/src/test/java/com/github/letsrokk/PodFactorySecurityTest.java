package com.github.letsrokk;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.ResourceRequirements;
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PodFactorySecurityTest {

    @Test
    void generatedWireMockPodUsesDedicatedTokenlessRestrictedIdentity() {
        MockFleetConfig config = config(false);
        Pod pod = new PodFactory(config).createPodSpec(
                "mock-fleet-demo-", "demo", List.of(), resources());

        assertEquals("wiremock-workload", pod.getSpec().getServiceAccountName());
        assertFalse(pod.getSpec().getAutomountServiceAccountToken());
        assertEquals(PodFactory.APP_NAME_VALUE,
                pod.getMetadata().getLabels().get(PodFactory.LABEL_APP_NAME));
        assertEquals(PodFactory.MANAGED_BY_VALUE,
                pod.getMetadata().getLabels().get(PodFactory.LABEL_MANAGED_BY));
        assertEquals("demo", pod.getMetadata().getLabels().get(PodFactory.LABEL_MOCK_ID));
        assertTrue(pod.getSpec().getSecurityContext().getRunAsNonRoot());
        assertEquals("RuntimeDefault", pod.getSpec().getSecurityContext().getSeccompProfile().getType());

        assertRestricted(pod.getSpec().getContainers().getFirst());
        assertTrue(pod.getSpec().getVolumes() == null || pod.getSpec().getVolumes().isEmpty(),
                "disabling the default token must not add a projected API token volume");
    }

    @Test
    void resolvedCatalogImageIsTheOnlyImageUsedForTheWireMockContainer() {
        MockFleetConfig config = config(false);
        WireMockVersion version = WireMockVersion.parse("3.12.1");

        Pod pod = new PodFactory(config).createPodSpec("mock-fleet-demo-", "demo",
                new WireMockResolvedConfig(version, "registry.example/wiremock:3.12.1-9",
                        List.of("--verbose"), resources()));

        assertEquals("registry.example/wiremock:3.12.1-9",
                pod.getSpec().getContainers().getFirst().getImage());
        assertEquals(List.of("--verbose"), pod.getSpec().getContainers().getFirst().getArgs());
    }

    @Test
    void persistentMappingsInitContainerIsRestrictedWithoutChangingStorageOrResources() {
        MockFleetConfig config = config(true);
        ResourceRequirements resources = resources();
        Pod pod = new PodFactory(config).createPodSpec(
                "mock-fleet-demo-", "demo", List.of(), resources);

        Container wireMock = pod.getSpec().getContainers().getFirst();
        Container mappingsInit = pod.getSpec().getInitContainers().getFirst();
        assertRestricted(wireMock);
        assertRestricted(mappingsInit);
        assertEquals(1000L, mappingsInit.getSecurityContext().getRunAsUser(),
                "the admission policy must mirror the exact init-container UID");
        assertEquals(resources, wireMock.getResources());
        assertEquals("mock-fleet-pvc",
                pod.getSpec().getVolumes().getFirst().getPersistentVolumeClaim().getClaimName());
        assertEquals(PodFactory.WIREMOCK_MAPPINGS_VOLUME,
                mappingsInit.getVolumeMounts().getFirst().getName());
        assertEquals("/mock-fleet", mappingsInit.getVolumeMounts().getFirst().getMountPath());
        assertEquals(new Quantity("10m"), mappingsInit.getResources().getRequests().get("cpu"));
        assertEquals(new Quantity("16Mi"), mappingsInit.getResources().getRequests().get("memory"));
        assertEquals(new Quantity("100m"), mappingsInit.getResources().getLimits().get("cpu"));
        assertEquals(new Quantity("64Mi"), mappingsInit.getResources().getLimits().get("memory"));
    }

    private void assertRestricted(Container container) {
        assertTrue(container.getSecurityContext().getRunAsNonRoot());
        assertEquals(1000L, container.getSecurityContext().getRunAsUser());
        assertFalse(container.getSecurityContext().getAllowPrivilegeEscalation());
        assertEquals(List.of("ALL"), container.getSecurityContext().getCapabilities().getDrop());
        assertEquals("RuntimeDefault", container.getSecurityContext().getSeccompProfile().getType());
    }

    private MockFleetConfig config(boolean persistent) {
        MockFleetConfig config = mock(MockFleetConfig.class);
        MockFleetConfig.StorageConfig storage = mock(MockFleetConfig.StorageConfig.class);
        when(config.wiremockContainerName()).thenReturn("wiremock");
        when(config.wiremockImage()).thenReturn("wiremock/wiremock:3.13.2-2");
        when(config.wiremockImagePullPolicy()).thenReturn("IfNotPresent");
        when(config.wiremockTerminationGracePeriodSeconds()).thenReturn(5L);
        when(config.wiremockServiceAccountName()).thenReturn(Optional.of("wiremock-workload"));
        when(config.storage()).thenReturn(storage);
        when(storage.persistent()).thenReturn(persistent);
        when(storage.type()).thenReturn(PodFactory.STORAGE_TYPE_S3);
        if (persistent) {
            MockFleetConfig.S3Config s3 = mock(MockFleetConfig.S3Config.class);
            when(storage.pvcName()).thenReturn("mock-fleet-pvc");
            when(storage.s3()).thenReturn(s3);
            when(s3.path()).thenReturn("/mock-fleet");
        }
        return config;
    }

    private ResourceRequirements resources() {
        return new ResourceRequirementsBuilder()
                .addToRequests("cpu", new Quantity("0.5"))
                .addToRequests("memory", new Quantity("512Mi"))
                .addToLimits("cpu", new Quantity("1"))
                .addToLimits("memory", new Quantity("1Gi"))
                .build();
    }
}
