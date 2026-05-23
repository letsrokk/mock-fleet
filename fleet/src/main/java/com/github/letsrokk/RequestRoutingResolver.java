package com.github.letsrokk;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class RequestRoutingResolver {

    @Inject
    MockFleetConfig config;

    ResolvedRequest resolveHostProxyRequest(String host, String requestUri) {
        return new ResolvedRequest(MockIdResolver.extractFromHost(host, config.routing().host()), requestUri);
    }

    ResolvedRequest resolvePathProxyRequest(String requestUri) {
        return MockIdResolver.extractFromPath(requestUri);
    }

    boolean isFleetHost(String host) {
        try {
            return MockIdResolver.isFleetHost(host, config.routing().host());
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    boolean isFleetSubdomain(String host) {
        try {
            return MockIdResolver.isFleetSubdomain(host, config.routing().host());
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
