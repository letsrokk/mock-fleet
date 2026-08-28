package com.github.letsrokk.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.eclipse.microprofile.health.HealthCheckResponse;

@Singleton
final class DependencyHealthProbe implements AutoCloseable {

    private static final int MAX_HEALTH_RESPONSE_BYTES = 65_536;

    private final WebClient client;
    private final long timeoutMillis;
    private final ObjectMapper mapper;

    @Inject
    DependencyHealthProbe(Vertx vertx, FleetMcpConfig config, ObjectMapper mapper) {
        this(vertx, config.dependencyHealthTimeout(), mapper);
    }

    DependencyHealthProbe(Vertx vertx, Duration timeout, ObjectMapper mapper) {
        timeoutMillis = timeout.toMillis();
        this.mapper = mapper;
        client = WebClient.create(vertx, new WebClientOptions()
                .setFollowRedirects(false)
                .setConnectTimeout((int) Math.min(Integer.MAX_VALUE, timeoutMillis))
                .setIdleTimeout((int) Math.max(1, timeout.toSeconds()))
                .setIdleTimeoutUnit(TimeUnit.SECONDS));
    }

    HealthCheckResponse check(String name, URI baseUrl, String path) {
        try {
            HttpRequest<Buffer> request = client.getAbs(resolve(baseUrl, path))
                    .timeout(timeoutMillis)
                    .followRedirects(false)
                    .putHeader("Accept", "application/json");
            TransportResponse response = HttpTransportSupport.await(request, new byte[0], MAX_HEALTH_RESPONSE_BYTES);
            if (response.status() != 200) {
                return HealthCheckResponse.named(name).down()
                        .withData("statusCode", response.status()).build();
            }
            JsonNode body = mapper.readTree(response.body());
            if (body != null && "UP".equals(body.path("status").asText())) {
                return HealthCheckResponse.named(name).up().build();
            }
            return HealthCheckResponse.named(name).down().withData("reason", "dependency reported DOWN").build();
        } catch (Exception failure) {
            return HealthCheckResponse.named(name).down()
                    .withData("reason", failure.getClass().getSimpleName()).build();
        }
    }

    private static String resolve(URI baseUrl, String path) {
        String base = baseUrl.toString();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + path;
    }

    @Override
    @PreDestroy
    public void close() {
        client.close();
    }
}
