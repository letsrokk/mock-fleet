package com.github.letsrokk;

import io.quarkus.vertx.web.Route;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.HttpHeaders;

import java.net.URI;
import java.net.URISyntaxException;

@ApplicationScoped
public class FleetLocalRoute {

    static final String LOCAL_REQUEST_CONTEXT_KEY = "mock-fleet.local-request";
    private static final String FLEET_PREFIX = "/__fleet/";

    @Inject
    RequestRoutingResolver requestRoutingResolver;

    @Inject
    MockFleetConfig config;

    @Route(path = "/", order = -100)
    void handleRoot(RoutingContext routingContext) {
        handle(routingContext);
    }

    @Route(path = "/*", order = -100)
    void handleAll(RoutingContext routingContext) {
        handle(routingContext);
    }

    private void handle(RoutingContext routingContext) {
        String host = routingContext.request().getHeader(HttpHeaders.HOST);
        String requestPath = requestPath(routingContext.request().uri());

        if (isFleetRootRequest(host, requestPath)) {
            respondForFleetRoot(routingContext);
            return;
        }

        if (isFleetDashboardEntry(host, requestPath)) {
            respondForFleetDashboardEntry(routingContext);
            return;
        }

        if (isAlwaysLocal(host, requestPath) || isFleetFavicon(host, requestPath)) {
            routingContext.put(LOCAL_REQUEST_CONTEXT_KEY, true);
        }

        routingContext.next();
    }

    static boolean isMarkedLocal(RoutingContext routingContext) {
        return Boolean.TRUE.equals(routingContext.get(LOCAL_REQUEST_CONTEXT_KEY));
    }

    private boolean isFleetRootRequest(String host, String requestPath) {
        if (!"/".equals(requestPath)) {
            return false;
        }

        if (config.routing().mode() == MockFleetConfig.RoutingMode.PATH) {
            return true;
        }

        return requestRoutingResolver.isFleetHost(host);
    }

    boolean isFleetDashboardEntry(String host, String requestPath) {
        if (!"/__fleet".equals(requestPath)) {
            return false;
        }

        return config.routing().mode() == MockFleetConfig.RoutingMode.PATH
                || !requestRoutingResolver.isFleetSubdomain(host);
    }

    private void respondForFleetRoot(RoutingContext routingContext) {
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

    static void respondForFleetDashboardEntry(RoutingContext routingContext) {
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

    boolean isAlwaysLocal(String host, String requestPath) {
        if (!"/__fleet/".equals(requestPath) && !requestPath.startsWith(FLEET_PREFIX)) {
            return false;
        }

        return config.routing().mode() == MockFleetConfig.RoutingMode.PATH
                || !requestRoutingResolver.isFleetSubdomain(host);
    }

    private boolean isFleetFavicon(String host, String requestPath) {
        return "/favicon.ico".equals(requestPath) && requestRoutingResolver.isFleetHost(host);
    }

    static String requestPath(String requestUri) {
        try {
            String path = new URI(requestUri).getPath();
            return path == null || path.isEmpty() ? "/" : path;
        } catch (URISyntaxException ignored) {
            int queryStart = requestUri.indexOf('?');
            String path = queryStart >= 0 ? requestUri.substring(0, queryStart) : requestUri;
            return path == null || path.isEmpty() ? "/" : path;
        }
    }
}
