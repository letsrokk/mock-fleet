package com.github.letsrokk.mcp;

import java.net.URI;
import java.util.Objects;

public final class FleetProxyRequestFactory {

    private final URI proxyBaseUrl;
    private final RoutingMode routingMode;
    private final String fleetHost;

    public FleetProxyRequestFactory(URI proxyBaseUrl, RoutingMode routingMode, String fleetHost) {
        this.proxyBaseUrl = normalizeBaseUrl(Objects.requireNonNull(proxyBaseUrl, "proxyBaseUrl"));
        this.routingMode = Objects.requireNonNull(routingMode, "routingMode");
        this.fleetHost = normalizeFleetHost(fleetHost, routingMode);
    }

    public ProxyRequest create(String mockId, String requestTarget) {
        MockIdValidator.requireValid(mockId);
        String normalizedTarget = normalizeRequestTarget(requestTarget);
        String path = routingMode == RoutingMode.PATH ? "/" + mockId + normalizedTarget : normalizedTarget;
        String hostHeader = routingMode == RoutingMode.HOST ? mockId + "." + fleetHost : null;
        return new ProxyRequest(URI.create(proxyBaseUrl + path), hostHeader);
    }

    private static URI normalizeBaseUrl(URI value) {
        if (!"http".equalsIgnoreCase(value.getScheme()) && !"https".equalsIgnoreCase(value.getScheme())) {
            throw new IllegalArgumentException("proxyBaseUrl must use HTTP or HTTPS");
        }
        if (value.getHost() == null || value.getQuery() != null || value.getFragment() != null) {
            throw new IllegalArgumentException("proxyBaseUrl must be an absolute URL without a query or fragment");
        }
        String normalized = value.toString();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return URI.create(normalized);
    }

    private static String normalizeRequestTarget(String requestTarget) {
        if (requestTarget == null || requestTarget.isBlank()) {
            return "/";
        }
        String normalized = requestTarget.startsWith("/") ? requestTarget : "/" + requestTarget;
        if (normalized.startsWith("//") || normalized.contains("#")) {
            throw new IllegalArgumentException("request target must be a relative HTTP path");
        }
        return normalized;
    }

    private static String normalizeFleetHost(String value, RoutingMode mode) {
        if (mode != RoutingMode.HOST) {
            return value;
        }
        if (value == null || value.isBlank() || value.contains("/") || value.contains(":")) {
            throw new IllegalArgumentException("fleetHost is required for HOST routing");
        }
        String normalized = value.endsWith(".") ? value.substring(0, value.length() - 1) : value;
        return normalized.toLowerCase();
    }

    public record ProxyRequest(URI uri, String hostHeader) {
    }
}
