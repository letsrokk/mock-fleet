package com.github.letsrokk;

import io.smallrye.config.ConfigMapping;

import java.time.Duration;

@ConfigMapping(prefix = "mock-fleet")
public interface MockFleetConfig {

    String namespace();

    Duration inactivityThreshold();

    Duration podCreationTimeout();

    String wiremockImage();

    StorageConfig storage();

    RoutingConfig routing();

    interface StorageConfig {
        String pvcName();
        String containerMappingsPath();
        String initContainerStoragePath();
    }

    interface RoutingConfig {
        RoutingMode mode();
        String host();
    }

    enum RoutingMode {
        HOST,
        PATH
    }

}
