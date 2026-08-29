package com.github.letsrokk;

import com.github.letsrokk.exceptions.MockIdNotFound;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
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
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

@ApplicationScoped
public class ProxyForwarder {

    private static final Logger LOG = Logger.getLogger(ProxyForwarder.class);
    private static final int MAX_CONNECT_RETRIES = 10;
    private static final long INITIAL_CONNECT_RETRY_DELAY_MS = 100;
    private static final long MAX_CONNECT_RETRY_DELAY_MS = 1_000;
    private static final Set<String> NON_FORWARDABLE_REQUEST_HEADERS = Set.of(
            "host",
            "connection",
            "proxy-connection",
            "keep-alive",
            "content-length",
            "transfer-encoding",
            "te",
            "trailer",
            "upgrade",
            "proxy-authorization");

    @Inject
    Vertx vertx;

    @Inject
    FleetApiClient fleetApiClient;

    private volatile WebClient webClient;

    void forward(RoutingContext routingContext, String host, ResolvedRequest resolvedRequest) {
        OriginFormRequestTarget requestTarget;
        try {
            requestTarget = OriginFormRequestTarget.parse(resolvedRequest.upstreamRequestUri());
        } catch (IllegalArgumentException error) {
            LOG.debugf("Rejecting request target '%s': %s", resolvedRequest.upstreamRequestUri(), error.getMessage());
            routingContext.response()
                    .setStatusCode(400)
                    .putHeader(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_PLAIN)
                    .end(error.getMessage());
            return;
        }

        Buffer requestBody = routingContext.body() == null ? null : routingContext.body().buffer();
        fleetApiClient.resolveUpstreamBaseUrl(resolvedRequest.mockId())
                .map(URI::create)
                .onSuccess(upstream -> {
                    LOG.tracef("Proxy request method=%s uri=%s host=%s mockId=%s upstream=%s upstreamUri=%s.",
                            routingContext.request().method(),
                            routingContext.request().uri(),
                            host,
                            resolvedRequest.mockId(),
                            upstream,
                            requestTarget.rawPathAndQuery());
                    forwardWithRetry(upstream, requestTarget, routingContext, requestBody, 0)
                            .onFailure(error -> handleFailure(routingContext, host, error));
                })
                .onFailure(error -> handleFailure(routingContext, host, error));
    }

    void handleFailure(RoutingContext routingContext, String host, Throwable error) {
        if (routingContext.response().ended()) {
            LOG.debugf("Response already ended while handling failure for host '%s': %s", host, error.getMessage());
            return;
        }

        if (error instanceof MockIdNotFound e) {
            LOG.debugf("Rejecting request: %s", e.getMessage());
            routingContext.response()
                    .setStatusCode(400)
                    .putHeader(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_PLAIN)
                    .end(e.getMessage());
            return;
        }

        LOG.errorf(error, "Failed to proxy request for host '%s'.", host);
        routingContext.response().setStatusCode(500).end();
    }

    private io.vertx.core.Future<Void> forwardWithRetry(URI upstream, OriginFormRequestTarget requestTarget,
                                                        RoutingContext routingContext, Buffer body, int attempt) {
        return forward(upstream, requestTarget, routingContext, body)
                .recover(error -> {
                    if (!shouldRetryConnectFailure(error, attempt)) {
                        return io.vertx.core.Future.failedFuture(error);
                    }

                    long delayMs = retryDelayMs(attempt);
                    LOG.debugf("Retrying upstream connect for %s after transient failure (attempt %d/%d, delay %dms).",
                            upstream, attempt + 1, MAX_CONNECT_RETRIES, delayMs);

                    return vertx.timer(delayMs)
                            .flatMap(ignored -> forwardWithRetry(upstream, requestTarget, routingContext, body, attempt + 1));
                });
    }

    private io.vertx.core.Future<Void> forward(URI upstream, OriginFormRequestTarget requestTarget,
                                               RoutingContext routingContext, Buffer body) {
        String targetUri = requestTarget.appendTo(upstream).toString();
        HttpRequest<Buffer> request = client()
                .requestAbs(routingContext.request().method(), targetUri)
                .followRedirects(false);

        copyForwardableRequestHeaders(routingContext.request().headers(), request.headers());

        if (body == null || body.length() == 0) {
            return request.send().compose(response -> writeResponse(routingContext, response));
        }
        return request.sendBuffer(body).compose(response -> writeResponse(routingContext, response));
    }

    static void copyForwardableRequestHeaders(MultiMap inboundHeaders, MultiMap outboundHeaders) {
        Set<String> excludedHeaders = excludedRequestHeaders(inboundHeaders);
        inboundHeaders.forEach(header -> {
            if (!excludedHeaders.contains(header.getKey().toLowerCase(Locale.ROOT))) {
                outboundHeaders.add(header.getKey(), header.getValue());
            }
        });
    }

    private static Set<String> excludedRequestHeaders(MultiMap inboundHeaders) {
        Set<String> excludedHeaders = new HashSet<>(NON_FORWARDABLE_REQUEST_HEADERS);
        inboundHeaders.getAll("Connection").forEach(value -> {
            for (String headerName : value.split(",")) {
                String normalizedName = headerName.trim().toLowerCase(Locale.ROOT);
                if (!normalizedName.isEmpty()) {
                    excludedHeaders.add(normalizedName);
                }
            }
        });
        return excludedHeaders;
    }

    private io.vertx.core.Future<Void> writeResponse(RoutingContext routingContext, HttpResponse<Buffer> response) {
        LOG.tracef("Proxy response method=%s uri=%s status=%d.",
                routingContext.request().method(),
                routingContext.request().uri(),
                response.statusCode());
        routingContext.response().setStatusCode(response.statusCode());
        response.headers().forEach(header -> routingContext.response().headers().add(header.getKey(), header.getValue()));
        Buffer responseBody = response.body();
        if (responseBody == null || responseBody.length() == 0) {
            return routingContext.response().end();
        }
        return routingContext.response().end(responseBody);
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

    @PreDestroy
    void destroy() {
        WebClient local = webClient;
        if (local != null) {
            local.close();
        }
    }
}
