package com.github.letsrokk.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import java.util.Set;
import java.util.stream.Collectors;

@Singleton
public final class McpClientProducer {

    @Produces
    @Singleton
    WireMockAdminClient wireMockAdminClient(FleetProxyClient proxyClient, ObjectMapper mapper, FleetMcpConfig config,
            McpMetrics metrics) {
        Set<String> sensitiveHeaders = config.sensitiveHeaders().stream().collect(Collectors.toUnmodifiableSet());
        return new WireMockAdminClient(proxyClient, mapper, config.maxPayloadBytes(), sensitiveHeaders, metrics, null,
                config.maxCollectionScanBytes(),
                config.maxCollectionScanItems());
    }

    @Produces
    @Singleton
    TargetUrlPolicy targetUrlPolicy(FleetMcpConfig config, McpMetrics metrics) {
        return new TargetUrlPolicy(Set.copyOf(config.outboundExceptions().orElseGet(java.util.List::of)),
                java.net.InetAddress::getAllByName, metrics);
    }

    @Produces
    @Singleton
    OutboundTargetValidator outboundTargetValidator(TargetUrlPolicy policy, FleetMcpConfig config, McpMetrics metrics) {
        return new OutboundTargetValidator(policy,
                Set.copyOf(config.outboundAllowedListeners().orElseGet(java.util.List::of)), metrics);
    }
}
