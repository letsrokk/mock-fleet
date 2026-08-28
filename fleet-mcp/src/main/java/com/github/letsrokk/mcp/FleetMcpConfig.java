package com.github.letsrokk.mcp;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

@ConfigMapping(prefix = "mock-fleet.mcp")
public interface FleetMcpConfig {

    URI apiBaseUrl();

    URI proxyBaseUrl();

    @WithDefault("PATH")
    RoutingMode routingMode();

    Optional<String> fleetHost();

    @WithDefault("wiremock/wiremock:3.13.2-2")
    String wiremockImage();

    @WithDefault("false")
    boolean storageEnabled();

    @WithDefault("10S")
    Duration timeout();

    @WithDefault("50")
    int defaultPageSize();

    @WithDefault("200")
    int maxPageSize();

    @WithDefault("1048576")
    int maxPayloadBytes();

    @WithDefault("262144")
    int includedBodyBytes();

    @WithDefault("1S")
    Duration dependencyHealthTimeout();

    @WithDefault("67108864")
    long maxCollectionScanBytes();

    @WithDefault("100000")
    int maxCollectionScanItems();

    @WithDefault("Authorization,Proxy-Authorization,Cookie,Set-Cookie,X-API-Key")
    List<String> sensitiveHeaders();

    Optional<List<String>> outboundExceptions();

    Optional<List<String>> outboundAllowedListeners();
}
