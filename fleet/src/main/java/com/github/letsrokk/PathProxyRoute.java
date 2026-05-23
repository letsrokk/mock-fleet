package com.github.letsrokk;

import com.github.letsrokk.exceptions.MockIdNotFound;
import io.quarkus.vertx.web.Route;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.HttpHeaders;

@ApplicationScoped
public class PathProxyRoute {

    @Inject
    MockFleetConfig config;

    @Inject
    RequestRoutingResolver requestRoutingResolver;

    @Inject
    ProxyForwarder proxyForwarder;

    @Inject
    FleetLocalRoute fleetLocalRoute;

    @Route(path = "/", order = 2)
    void proxyRoot(RoutingContext routingContext) {
        handle(routingContext);
    }

    @Route(path = "/*", order = 2)
    void proxy(RoutingContext routingContext) {
        handle(routingContext);
    }

    private void handle(RoutingContext routingContext) {
        if (config.routing().mode() != MockFleetConfig.RoutingMode.PATH) {
            routingContext.next();
            return;
        }

        String host = routingContext.request().getHeader(HttpHeaders.HOST);
        String requestPath = FleetLocalRoute.requestPath(routingContext.request().uri());
        try {
            if ("/".equals(requestPath)) {
                respondForPathModeRoot(routingContext);
                return;
            }

            if (fleetLocalRoute.isFleetDashboardEntry(host, requestPath)) {
                FleetLocalRoute.respondForFleetDashboardEntry(routingContext);
                return;
            }

            if (FleetLocalRoute.isMarkedLocal(routingContext)
                    || fleetLocalRoute.isAlwaysLocal(host, requestPath)
                    || ("/favicon.ico".equals(requestPath) && requestRoutingResolver.isFleetHost(host))) {
                routingContext.next();
                return;
            }

            if (requestRoutingResolver.isFleetSubdomain(host)) {
                throw new MockIdNotFound(String.format("Path routing does not accept fleet subdomain host '%s'.", host));
            }

            proxyForwarder.forward(routingContext, host, requestRoutingResolver.resolvePathProxyRequest(routingContext.request().uri()));
        } catch (MockIdNotFound error) {
            proxyForwarder.handleFailure(routingContext, host, error);
        } catch (Throwable error) {
            proxyForwarder.handleFailure(routingContext, host, error);
        }
    }

    private void respondForPathModeRoot(RoutingContext routingContext) {
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
}
