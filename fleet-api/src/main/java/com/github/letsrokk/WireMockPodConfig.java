package com.github.letsrokk;

import io.fabric8.kubernetes.api.model.ResourceRequirements;

import java.util.List;

public record WireMockPodConfig(List<String> options, ResourceRequirements resources) {
}
