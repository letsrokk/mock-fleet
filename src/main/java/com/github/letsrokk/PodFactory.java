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

    private final MockFleetConfig config;

    @Inject
    public PodFactory(MockFleetConfig config) {
        this.config = config;
    }

    public Pod createPodSpec(String podName, String mockId) {
        // Define the container
        Container container = new ContainerBuilder()
                .withName("wiremock")
                .withImage(config.wiremockImage())
                .addNewPort()
                    .withContainerPort(8080)
                .endPort()
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
                    .withContainers(container)
                    .withRestartPolicy("Never")
                .endSpec()
                .build();
    }
}
