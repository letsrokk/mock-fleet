package com.github.letsrokk;

import io.quarkus.vertx.web.Route;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.HttpHeaders;

@ApplicationScoped
public class FleetRootRouterCustomizer {

    @Inject
    MockFleetConfig config;

    @Inject
    RequestRoutingResolver requestRoutingResolver;

    @Route(path = "/", order = -100)
    void handleRoot(RoutingContext routingContext) {
        if (!isFleetOwnedRoot(routingContext)) {
            routingContext.next();
            return;
        }

        HttpMethod method = routingContext.request().method();
        if (method == HttpMethod.GET || method == HttpMethod.HEAD) {
            routingContext.response()
                    .setStatusCode(302)
                    .putHeader(HttpHeaders.LOCATION, "/__fleet/")
                    .end();
            return;
        }

        routingContext.response()
                .setStatusCode(405)
                .putHeader(HttpHeaders.ALLOW, "GET, HEAD")
                .end();
    }

    private boolean isFleetOwnedRoot(RoutingContext routingContext) {
        if (config.routing().mode() == MockFleetConfig.RoutingMode.HOST) {
            return requestRoutingResolver.isFleetHost(routingContext.request().getHeader(HttpHeaders.HOST));
        }

        return true;
    }
}
