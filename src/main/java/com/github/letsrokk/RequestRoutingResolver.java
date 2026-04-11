package com.github.letsrokk;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class RequestRoutingResolver {

    @Inject
    MockFleetConfig config;

    ResolvedRequest resolve(String host, String requestUri) {
        return switch (config.routing().mode()) {
            case HOST -> new ResolvedRequest(MockIdResolver.extractFromHost(host), requestUri);
            case PATH -> MockIdResolver.extractFromPath(requestUri);
        };
    }
}
