package com.github.letsrokk;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@ApplicationScoped
public class FleetApiClient {

    private static final Logger LOG = Logger.getLogger(FleetApiClient.class);

    @Inject
    Vertx vertx;

    @Inject
    MockFleetConfig config;

    private volatile WebClient webClient;

    public Future<String> resolveUpstreamBaseUrl(String mockId) {
        String targetUri = normalizedApiBaseUrl() + "/internal/mocks/" + encodePathSegment(mockId) + "/upstream";
        return client()
                .postAbs(targetUri)
                .send()
                .compose(response -> {
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        LOG.warnf("fleet-api returned %d while resolving upstream for mock id '%s'.",
                                response.statusCode(), mockId);
                        return Future.failedFuture("fleet-api returned " + response.statusCode());
                    }

                    Buffer body = response.body();
                    if (body == null) {
                        return Future.failedFuture("fleet-api returned an empty upstream response");
                    }

                    String baseUrl = body.toJsonObject().getString("baseUrl");
                    if (baseUrl == null || baseUrl.isBlank()) {
                        return Future.failedFuture("fleet-api upstream response is missing baseUrl");
                    }
                    return Future.succeededFuture(baseUrl);
                });
    }

    private String normalizedApiBaseUrl() {
        String baseUrl = config.api().baseUrl();
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    private String encodePathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
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
