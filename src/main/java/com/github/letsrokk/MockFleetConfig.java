package com.github.letsrokk;

import io.smallrye.config.ConfigMapping;

import java.time.Duration;

@ConfigMapping(prefix = "mock-fleet")
public interface MockFleetConfig {

    Duration inactivityThreshold();

    Duration podCreationTimeout();

    String wiremockImage();

}
