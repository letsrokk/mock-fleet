package com.github.letsrokk;

import io.smallrye.config.ConfigMapping;

import java.time.Duration;

@ConfigMapping(prefix = "mock-fleet")
public interface MockFleetConfig {

    Duration inactivityThreshold();

    Duration podCreationTimeout();

    String wiremockImage();

    LocalDebugConfig localDebug();

    interface LocalDebugConfig {
        boolean enabled();

        String bindAddress();

        int startPort();

        int endPort();

        int servicePort();
    }

}
