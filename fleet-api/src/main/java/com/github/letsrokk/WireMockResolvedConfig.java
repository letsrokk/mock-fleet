package com.github.letsrokk;

import io.fabric8.kubernetes.api.model.ResourceRequirements;
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder;

import java.util.List;

public record WireMockResolvedConfig(WireMockVersion version, String image, List<String> options,
                                     ResourceRequirements resources) {
    public WireMockResolvedConfig {
        options = List.copyOf(options);
        resources = resources == null ? null : new ResourceRequirementsBuilder(resources).build();
    }
}
