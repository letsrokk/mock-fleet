package com.github.letsrokk;

import io.smallrye.config.ConfigMapping;

@ConfigMapping(prefix = "mock-fleet")
public interface MockFleetConfig {

    ApiConfig api();

    RoutingConfig routing();

    ConnectionPoolsConfig connectionPools();

    interface ConnectionPoolsConfig {
        int apiMaxSize();
        int mockMaxSize();
    }

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
