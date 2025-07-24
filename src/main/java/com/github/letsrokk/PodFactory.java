package com.github.letsrokk;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;

public class PodFactory {

    public static Pod createMyPodSpec(String podName, String mockId) {
        // Define the container
        Container container = new ContainerBuilder()
                .withName("wiremock")
                .withImage("wiremock/wiremock:latest")
                .build();

        // Build the Pod
        return new PodBuilder()
                .withNewMetadata()
                    .withGenerateName(podName)
                    .addToLabels("app", String.format("mock-fleet-%s", mockId))
                .endMetadata()
                .withNewSpec()
                    .withContainers(container)
                .endSpec()
                .build();
    }
}
