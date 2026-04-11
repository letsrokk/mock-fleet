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

import java.net.URI;

@ApplicationScoped
public class ProxyRoute {

    private static final Logger LOG = Logger.getLogger(ProxyRoute.class);

    @Inject
    Vertx vertx;

    @Inject
    PodManager podManager;

    private volatile WebClient webClient;

    @Route(path = "/*", order = 100)
    void proxy(RoutingContext routingContext) {
        String host = routingContext.request().getHeader(HttpHeaders.HOST);
        vertx.<URI>executeBlocking(() -> URI.create(podManager.getUpstreamBaseUrl(host)))
                .onSuccess(upstream -> {
                    LOG.debugf("Proxying %s %s for host '%s' to upstream %s.",
                            routingContext.request().method(),
                            routingContext.request().uri(),
                            host,
                            upstream);
                    forward(upstream, routingContext, routingContext.body() == null ? null : routingContext.body().buffer())
                            .onFailure(error -> handleFailure(routingContext, host, error));
                })
                .onFailure(error -> handleFailure(routingContext, host, error));
    }

    private io.vertx.core.Future<Void> forward(URI upstream, RoutingContext routingContext, Buffer body) {
        String targetUri = upstream.resolve(routingContext.request().uri()).toString();
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

    private int resolvePort(URI upstream) {
        if (upstream.getPort() != -1) {
            return upstream.getPort();
        }
        return "https".equalsIgnoreCase(upstream.getScheme()) ? 443 : 80;
    }

    private void handleFailure(RoutingContext routingContext, String host, Throwable error) {
        if (error instanceof MockIdNotFound e) {
            LOG.debugf("Rejecting request with invalid host header: %s", e.getMessage());
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
