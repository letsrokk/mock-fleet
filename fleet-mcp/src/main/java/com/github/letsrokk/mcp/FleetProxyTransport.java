package com.github.letsrokk.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;

@FunctionalInterface
public interface FleetProxyTransport {
    TransportResponse execute(String mockId, TransportRequest request);

    default CollectionScan scanCollection(String mockId, TransportRequest request, ObjectMapper mapper,
            String collectionField, long position, int limit, long maxBytes, int maxItems) {
        TransportResponse response = execute(mockId, request);
        if (response.status() < 200 || response.status() >= 300) {
            throw new McpOperationException("WIREMOCK_ADMIN_ERROR", "WireMock returned HTTP " + response.status(),
                    response.status() >= 500,
                    java.util.Map.of("status", response.status(), "body", response.bodyAsString()));
        }
        return CollectionScanner.scan(mapper, response.body(), collectionField, position, limit, maxBytes, maxItems);
    }
}
