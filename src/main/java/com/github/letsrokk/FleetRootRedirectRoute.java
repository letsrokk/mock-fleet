package com.github.letsrokk;

import io.quarkus.vertx.web.Route;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.HttpHeaders;

@ApplicationScoped
public class FleetRootRedirectRoute {

    @Inject
    MockFleetConfig config;

    @Inject
    RequestRoutingResolver requestRoutingResolver;

    @Route(path = "/", methods = {Route.HttpMethod.GET, Route.HttpMethod.HEAD}, order = 0)
    void redirectRoot(RoutingContext routingContext) {
        if (!shouldRedirect(routingContext)) {
            routingContext.next();
            return;
        }

        routingContext.response()
                .setStatusCode(302)
                .putHeader(HttpHeaders.LOCATION, "/__fleet/")
                .end();
    }

    private boolean shouldRedirect(RoutingContext routingContext) {
        if (config.routing().mode() == MockFleetConfig.RoutingMode.HOST) {
            return requestRoutingResolver.isFleetHost(routingContext.request().getHeader(HttpHeaders.HOST));
        }

        return true;
    }
}
