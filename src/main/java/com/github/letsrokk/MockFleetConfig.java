package com.github.letsrokk;

import io.smallrye.config.ConfigMapping;

import java.time.Duration;

@ConfigMapping(prefix = "mock-fleet")
public interface MockFleetConfig {

    String namespace();

    Duration inactivityThreshold();

    Duration podCreationTimeout();

    String wiremockImage();

    RoutingConfig routing();

    interface RoutingConfig {
        RoutingMode mode();
    }

    enum RoutingMode {
        HOST,
        PATH
    }

}
