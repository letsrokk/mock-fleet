package com.github.letsrokk;

import com.github.letsrokk.exceptions.MockIdNotFound;
import io.quarkus.vertx.web.Route;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.HttpHeaders;

@ApplicationScoped
public class HostProxyRoute {

    @Inject
    MockFleetConfig config;

    @Inject
    RequestRoutingResolver requestRoutingResolver;

    @Inject
    ProxyForwarder proxyForwarder;

    @Inject
    FleetLocalRoute fleetLocalRoute;

    @Route(path = "/", order = 1)
    void proxyRoot(RoutingContext routingContext) {
        handle(routingContext);
    }

    @Route(path = "/*", order = 1)
    void proxy(RoutingContext routingContext) {
        handle(routingContext);
    }

    private void handle(RoutingContext routingContext) {
        if (config.routing().mode() != MockFleetConfig.RoutingMode.HOST) {
            routingContext.next();
            return;
        }

        if (FleetLocalRoute.isMarkedLocal(routingContext)) {
            routingContext.next();
            return;
        }

        String host = routingContext.request().getHeader(HttpHeaders.HOST);
        String requestPath = FleetLocalRoute.requestPath(routingContext.request().uri());
        if (fleetLocalRoute.isFleetDashboardEntry(host, requestPath)) {
            FleetLocalRoute.respondForFleetDashboardEntry(routingContext);
            return;
        }

        if (fleetLocalRoute.isAlwaysLocal(host, requestPath)) {
            routingContext.next();
            return;
        }

        if (requestRoutingResolver.isFleetHost(host)) {
            routingContext.next();
            return;
        }

        try {
            proxyForwarder.forward(routingContext, host, requestRoutingResolver.resolveHostProxyRequest(host, routingContext.request().uri()));
        } catch (MockIdNotFound error) {
            proxyForwarder.handleFailure(routingContext, host, error);
        } catch (Throwable error) {
            proxyForwarder.handleFailure(routingContext, host, error);
        }
    }
}
