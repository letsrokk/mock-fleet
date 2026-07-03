package com.github.letsrokk;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.PodSpecBuilder;
import io.fabric8.kubernetes.api.model.ResourceRequirements;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

@ApplicationScoped
public class PodFactory {

    public static final String LABEL_MANAGED_BY = "app.kubernetes.io/managed-by";
    public static final String MANAGED_BY_VALUE = "mock-fleet";
    public static final String LABEL_APP_NAME = "app.kubernetes.io/name";
    public static final String APP_NAME_VALUE = "mock-fleet-wiremock";
    public static final String LABEL_MOCK_ID = "mock-fleet/mock-id";
    static final String WIREMOCK_HEALTH_PATH = "/__admin/health";
    static final String WIREMOCK_MAPPINGS_VOLUME = "wiremock-mappings";
    static final String WIREMOCK_ROOT_DIR = "/home/wiremock";
    static final String INIT_MAPPINGS_CONTAINER = "prepare-wiremock-mappings";
    static final String INIT_CONTAINER_IMAGE = "busybox:1.36";
    static final String STORAGE_TYPE_S3 = "s3";

    private final MockFleetConfig config;

    @Inject
    public PodFactory(MockFleetConfig config) {
        this.config = config;
    }

    public Pod createPodSpec(String podName, String mockId) {
        return createPodSpec(podName, mockId, List.of(), null);
    }

    public Pod createPodSpec(String podName, String mockId, List<String> wireMockOptions) {
        return createPodSpec(podName, mockId, wireMockOptions, null);
    }

    public Pod createPodSpec(String podName, String mockId, List<String> wireMockOptions,
                             ResourceRequirements resources) {
        MockFleetConfig.StorageConfig storage = config.storage();

        ContainerBuilder containerBuilder = new ContainerBuilder()
                .withImagePullPolicy(config.wiremockImagePullPolicy())
                .withName(config.wiremockContainerName())
                .withImage(config.wiremockImage())
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
                .endLivenessProbe();

        if (wireMockOptions != null && !wireMockOptions.isEmpty()) {
            containerBuilder.withArgs(wireMockOptions);
        }
        if (resources != null) {
            containerBuilder.withResources(resources);
        }

        if (storage.persistent() && !STORAGE_TYPE_S3.equals(storage.type())) {
            throw new IllegalArgumentException("Unsupported persistent storage type: " + storage.type());
        }

        Container initContainer = null;
        if (storage.persistent()) {
            String storageMountPath = storage.s3().path();
            initContainer = new ContainerBuilder()
                    .withName(INIT_MAPPINGS_CONTAINER)
                    .withImage(INIT_CONTAINER_IMAGE)
                    .withCommand("mkdir", "-p", storageMountPath + "/" + mockId)
                    .addNewVolumeMount()
                        .withName(WIREMOCK_MAPPINGS_VOLUME)
                        .withMountPath(storageMountPath)
                    .endVolumeMount()
                    .build();

            containerBuilder
                    .addNewVolumeMount()
                        .withName(WIREMOCK_MAPPINGS_VOLUME)
                        .withMountPath(WIREMOCK_ROOT_DIR)
                        .withSubPath(mockId)
                    .endVolumeMount();
        }

        Container container = containerBuilder.build();

        PodSpecBuilder podSpecBuilder = new PodSpecBuilder()
                .withContainers(container)
                .withRestartPolicy("Never");

        config.wiremockServiceAccountName()
                .filter(name -> !name.isBlank())
                .ifPresent(podSpecBuilder::withServiceAccountName);

        if (storage.persistent()) {
            podSpecBuilder
                    .withInitContainers(initContainer)
                    .addNewVolume()
                        .withName(WIREMOCK_MAPPINGS_VOLUME)
                        .withNewPersistentVolumeClaim()
                            .withClaimName(storage.pvcName())
                        .endPersistentVolumeClaim()
                    .endVolume();
        }

        return new PodBuilder()
                .withNewMetadata()
                    .withGenerateName(podName)
                    .addToLabels(LABEL_APP_NAME, APP_NAME_VALUE)
                    .addToLabels(LABEL_MANAGED_BY, MANAGED_BY_VALUE)
                    .addToLabels(LABEL_MOCK_ID, mockId)
                .endMetadata()
                .withSpec(podSpecBuilder.build())
                .build();
    }
}
