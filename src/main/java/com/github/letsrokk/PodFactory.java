package com.github.letsrokk;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class PodFactory {

    public static final String LABEL_MANAGED_BY = "app.kubernetes.io/managed-by";
    public static final String MANAGED_BY_VALUE = "mock-fleet";
    public static final String LABEL_APP_NAME = "app.kubernetes.io/name";
    public static final String APP_NAME_VALUE = "mock-fleet-wiremock";
    public static final String LABEL_MOCK_ID = "mock-fleet/mock-id";
    static final String WIREMOCK_HEALTH_PATH = "/__admin/health";
    static final String WIREMOCK_MAPPINGS_VOLUME = "wiremock-mappings";
    static final String INIT_MAPPINGS_CONTAINER = "prepare-wiremock-mappings";
    static final String INIT_CONTAINER_IMAGE = "busybox:1.36";

    private final MockFleetConfig config;

    @Inject
    public PodFactory(MockFleetConfig config) {
        this.config = config;
    }

    public Pod createPodSpec(String podName, String mockId) {
        MockFleetConfig.StorageConfig storage = config.storage();
        String storageMountPath = storage.initContainerStoragePath();

        Container initContainer = new ContainerBuilder()
                .withName(INIT_MAPPINGS_CONTAINER)
                .withImage(INIT_CONTAINER_IMAGE)
                .withCommand("mkdir", "-p", storageMountPath + "/" + mockId)
                .addNewVolumeMount()
                    .withName(WIREMOCK_MAPPINGS_VOLUME)
                    .withMountPath(storageMountPath)
                .endVolumeMount()
                .build();

        // Define the container
        Container container = new ContainerBuilder()
                .withName("wiremock")
                .withImage(config.wiremockImage())
                .addNewVolumeMount()
                    .withName(WIREMOCK_MAPPINGS_VOLUME)
                    .withMountPath(storage.containerMappingsPath())
                    .withSubPath(mockId)
                .endVolumeMount()
                .addNewPort()
                    .withContainerPort(8080)
                .endPort()
                .withNewStartupProbe()
                    .withNewHttpGet()
                        .withPath(WIREMOCK_HEALTH_PATH)
                        .withNewPort(8080)
                    .endHttpGet()
                    .withInitialDelaySeconds(1)
                    .withPeriodSeconds(1)
                    .withTimeoutSeconds(1)
                    .withFailureThreshold(60)
                .endStartupProbe()
                .withNewReadinessProbe()
                    .withNewHttpGet()
                        .withPath(WIREMOCK_HEALTH_PATH)
                        .withNewPort(8080)
                    .endHttpGet()
                    .withInitialDelaySeconds(1)
                    .withPeriodSeconds(1)
                    .withTimeoutSeconds(1)
                    .withFailureThreshold(30)
                .endReadinessProbe()
                .withNewLivenessProbe()
                    .withNewHttpGet()
                        .withPath(WIREMOCK_HEALTH_PATH)
                        .withNewPort(8080)
                    .endHttpGet()
                    .withInitialDelaySeconds(10)
                    .withPeriodSeconds(10)
                    .withTimeoutSeconds(1)
                    .withFailureThreshold(3)
                .endLivenessProbe()
                .build();

        // Build the Pod
        return new PodBuilder()
                .withNewMetadata()
                    .withGenerateName(podName)
                    .addToLabels(LABEL_APP_NAME, APP_NAME_VALUE)
                    .addToLabels(LABEL_MANAGED_BY, MANAGED_BY_VALUE)
                    .addToLabels(LABEL_MOCK_ID, mockId)
                .endMetadata()
                .withNewSpec()
                    .withInitContainers(initContainer)
                    .withContainers(container)
                    .addNewVolume()
                        .withName(WIREMOCK_MAPPINGS_VOLUME)
                        .withNewPersistentVolumeClaim()
                            .withClaimName(storage.pvcName())
                        .endPersistentVolumeClaim()
                    .endVolume()
                    .withRestartPolicy("Never")
                .endSpec()
                .build();
    }
}
