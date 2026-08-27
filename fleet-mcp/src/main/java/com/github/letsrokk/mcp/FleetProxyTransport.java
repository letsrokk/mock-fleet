package com.github.letsrokk.mcp;

@FunctionalInterface
public interface FleetProxyTransport {
    TransportResponse execute(String mockId, TransportRequest request);
}
