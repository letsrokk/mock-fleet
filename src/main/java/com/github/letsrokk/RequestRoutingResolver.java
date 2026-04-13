package com.github.letsrokk;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import com.github.letsrokk.exceptions.MockIdNotFound;

@ApplicationScoped
public class RequestRoutingResolver {

    @Inject
    MockFleetConfig config;

    ResolvedRequest resolve(String host, String requestUri) {
        return switch (config.routing().mode()) {
            case HOST -> new ResolvedRequest(MockIdResolver.extractFromHost(host, config.routing().host()), requestUri);
            case PATH -> MockIdResolver.extractFromPath(requestUri);
        };
    }

    boolean isFleetHost(String host) {
        if (config.routing().mode() != MockFleetConfig.RoutingMode.HOST) {
            return false;
        }

        try {
            return MockIdResolver.isFleetHost(host, config.routing().host());
        } catch (MockIdNotFound ignored) {
            return false;
        }
    }
}
