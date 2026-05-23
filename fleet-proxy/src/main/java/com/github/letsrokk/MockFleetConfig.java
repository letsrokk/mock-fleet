package com.github.letsrokk;

import io.smallrye.config.ConfigMapping;

@ConfigMapping(prefix = "mock-fleet")
public interface MockFleetConfig {

    ApiConfig api();

    RoutingConfig routing();

    interface ApiConfig {
        String baseUrl();
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
