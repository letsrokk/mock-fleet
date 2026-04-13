package com.github.letsrokk;

import com.github.letsrokk.exceptions.MockIdNotFound;
import io.quarkus.vertx.web.Route;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.Vertx;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import org.jboss.logging.Logger;

import java.net.ConnectException;
import java.net.URI;
import java.net.URISyntaxException;

@ApplicationScoped
public class ProxyRoute {

    private static final Logger LOG = Logger.getLogger(ProxyRoute.class);
    private static final int MAX_CONNECT_RETRIES = 10;
    private static final long INITIAL_CONNECT_RETRY_DELAY_MS = 100;
    private static final long MAX_CONNECT_RETRY_DELAY_MS = 1_000;
    private static final String[] GLOBAL_LOCAL_PATHS = {
            "/favicon.ico"
    };
    private static final String[] LOCAL_UI_PREFIXES = {
            "/__fleet/",
            "/src/",
            "/node_modules/",
            "/@vite/",
            "/@fs/",
            "/@id/",
            "/@react-refresh"
    };
    private static final String[] LOCAL_UI_PATHS = {
            "/__fleet",
            "/favicon.ico"
    };

    @Inject
    Vertx vertx;

    @Inject
    PodManager podManager;

    @Inject
    RequestRoutingResolver requestRoutingResolver;

    @Inject
    MockFleetConfig config;

    private volatile WebClient webClient;

    @Route(path = "/*", order = 1)
    void proxy(RoutingContext routingContext) {
        handle(routingContext);
    }

    private void handle(RoutingContext routingContext) {
        String host = routingContext.request().getHeader(HttpHeaders.HOST);
        String requestPath = requestPath(routingContext.request().uri());
        if (shouldHandleLocally(host, requestPath)) {
            routingContext.next();
            return;
        }

        ResolvedRequest resolvedRequest;
        try {
            resolvedRequest = requestRoutingResolver.resolve(host, routingContext.request().uri());
        } catch (Throwable error) {
            handleFailure(routingContext, host, error);
            return;
        }

        ResolvedRequest finalResolvedRequest = resolvedRequest;
        vertx.<URI>executeBlocking(() -> URI.create(podManager.getUpstreamBaseUrl(finalResolvedRequest.mockId())))
                .onSuccess(upstream -> {
                    LOG.debugf("Proxying %s %s for host '%s' and mock id '%s' to upstream %s as %s.",
                            routingContext.request().method(),
                            routingContext.request().uri(),
                            host,
                            finalResolvedRequest.mockId(),
                            upstream,
                            finalResolvedRequest.upstreamRequestUri());
                    forwardWithRetry(upstream, finalResolvedRequest.upstreamRequestUri(), routingContext,
                            routingContext.body() == null ? null : routingContext.body().buffer(),
                            0)
                            .onFailure(error -> handleFailure(routingContext, host, error));
                })
                .onFailure(error -> handleFailure(routingContext, host, error));
    }

    private io.vertx.core.Future<Void> forwardWithRetry(URI upstream, String upstreamRequestUri,
                                                        RoutingContext routingContext, Buffer body, int attempt) {
        return forward(upstream, upstreamRequestUri, routingContext, body)
                .recover(error -> {
                    if (!shouldRetryConnectFailure(error, attempt)) {
                        return io.vertx.core.Future.failedFuture(error);
                    }

                    long delayMs = retryDelayMs(attempt);
                    LOG.debugf("Retrying upstream connect for %s after transient failure (attempt %d/%d, delay %dms).",
                            upstream, attempt + 1, MAX_CONNECT_RETRIES, delayMs);

                    return vertx.timer(delayMs)
                            .flatMap(ignored -> forwardWithRetry(upstream, upstreamRequestUri, routingContext, body, attempt + 1));
                });
    }

    private long retryDelayMs(int attempt) {
        long delayMs = INITIAL_CONNECT_RETRY_DELAY_MS * (1L << attempt);
        return Math.min(delayMs, MAX_CONNECT_RETRY_DELAY_MS);
    }

    private boolean shouldRetryConnectFailure(Throwable error, int attempt) {
        return attempt < MAX_CONNECT_RETRIES && isConnectFailure(error);
    }

    private boolean isConnectFailure(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof ConnectException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private io.vertx.core.Future<Void> forward(URI upstream, String upstreamRequestUri, RoutingContext routingContext, Buffer body) {
        String targetUri = upstream.resolve(upstreamRequestUri).toString();
        HttpRequest<Buffer> request = client()
                .requestAbs(routingContext.request().method(), targetUri)
                .followRedirects(false);

        routingContext.request().headers().forEach(header -> request.putHeader(header.getKey(), header.getValue()));

        if (body == null || body.length() == 0) {
            return request.send().compose(response -> writeResponse(routingContext, response));
        }
        return request.sendBuffer(body).compose(response -> writeResponse(routingContext, response));
    }

    private io.vertx.core.Future<Void> writeResponse(RoutingContext routingContext, HttpResponse<Buffer> response) {
        routingContext.response().setStatusCode(response.statusCode());
        response.headers().forEach(header -> routingContext.response().putHeader(header.getKey(), header.getValue()));
        Buffer responseBody = response.body();
        if (responseBody == null || responseBody.length() == 0) {
            return routingContext.response().end();
        }
        return routingContext.response().end(responseBody);
    }

    private boolean shouldHandleLocally(String host, String requestPath) {
        if (matchesAny(requestPath, GLOBAL_LOCAL_PATHS)) {
            return true;
        }

        if (config.routing().mode() == MockFleetConfig.RoutingMode.HOST) {
            return requestRoutingResolver.isFleetHost(host);
        }

        return matchesAny(requestPath, LOCAL_UI_PATHS)
                || startsWithAny(requestPath, LOCAL_UI_PREFIXES);
    }

    private boolean matchesAny(String requestPath, String[] paths) {
        for (String path : paths) {
            if (requestPath.equals(path)) {
                return true;
            }
        }
        return false;
    }

    private boolean startsWithAny(String requestPath, String[] prefixes) {
        for (String prefix : prefixes) {
            if (requestPath.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private String requestPath(String requestUri) {
        try {
            String path = new URI(requestUri).getPath();
            return path == null || path.isEmpty() ? "/" : path;
        } catch (URISyntaxException ignored) {
            int queryStart = requestUri.indexOf('?');
            String path = queryStart >= 0 ? requestUri.substring(0, queryStart) : requestUri;
            return path == null || path.isEmpty() ? "/" : path;
        }
    }

    private WebClient client() {
        WebClient local = webClient;
        if (local == null) {
            synchronized (this) {
                local = webClient;
                if (local == null) {
                    local = WebClient.create(vertx, new WebClientOptions().setFollowRedirects(false));
                    webClient = local;
                }
            }
        }
        return local;
    }

    private void handleFailure(RoutingContext routingContext, String host, Throwable error) {
        if (error instanceof MockIdNotFound e) {
            LOG.debugf("Rejecting request: %s", e.getMessage());
            routingContext.response()
                    .setStatusCode(400)
                    .putHeader(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_PLAIN)
                    .end(e.getMessage());
            return;
        }

        LOG.errorf(error, "Failed to proxy request for host '%s'.", host);
        if (!routingContext.response().ended()) {
            routingContext.response().setStatusCode(500).end();
        }
    }

    @PreDestroy
    void close() {
        WebClient local = webClient;
        if (local != null) {
            local.close();
        }
    }
}
