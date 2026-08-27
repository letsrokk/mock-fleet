package com.github.letsrokk.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Singleton
public final class FleetApiClient {

    private final URI apiBaseUrl;
    private final WebClient client;
    private final ObjectMapper mapper;
    private final int maxPayloadBytes;
    private final long timeoutMillis;
    private final McpMetrics metrics;

    @Inject
    public FleetApiClient(Vertx vertx, FleetMcpConfig config, ObjectMapper mapper, McpMetrics metrics) {
        this(vertx, config.apiBaseUrl(), config.timeout(), config.maxPayloadBytes(), mapper, metrics);
    }

    FleetApiClient(Vertx vertx, URI apiBaseUrl, Duration timeout, int maxPayloadBytes, ObjectMapper mapper,
            McpMetrics metrics) {
        this.apiBaseUrl = normalize(apiBaseUrl);
        this.mapper = mapper;
        this.maxPayloadBytes = maxPayloadBytes;
        this.timeoutMillis = timeout.toMillis();
        this.metrics = metrics;
        this.client = WebClient.create(vertx, new WebClientOptions()
                .setFollowRedirects(false)
                .setConnectTimeout((int) Math.min(Integer.MAX_VALUE, timeoutMillis))
                .setIdleTimeout((int) Math.max(1, timeout.toSeconds()))
                .setIdleTimeoutUnit(TimeUnit.SECONDS));
    }

    public JsonNode listMocks() {
        return json(HttpMethod.GET, "/__fleet/api/mocks", null);
    }

    public void stopMock(String mockId) {
        raw(HttpMethod.DELETE, "/__fleet/api/mocks/" + MockIdValidator.requireValid(mockId), null);
    }

    public JsonNode getConfig() {
        return json(HttpMethod.GET, "/__fleet/api/config", null);
    }

    public JsonNode updateConfig(String mockId, String resourceVersion, List<String> options, MockResources resources,
            ConfigApplyMode applyMode) {
        if (resourceVersion == null || resourceVersion.isBlank()) {
            throw new IllegalArgumentException("resourceVersion is required");
        }
        if (options == null) {
            throw new IllegalArgumentException("options is required");
        }
        if (resources != null && resources.requests() == null) {
            throw new IllegalArgumentException("resources.requests is required when resources are provided");
        }
        if (resources != null && resources.limits() == null) {
            throw new IllegalArgumentException("resources.limits is required when resources are provided");
        }
        requireApplyMode(applyMode);
        var payload = mapper.createObjectNode();
        payload.put("resourceVersion", resourceVersion);
        payload.set("options", mapper.valueToTree(options));
        if (resources == null) {
            payload.putNull("resources");
        } else {
            payload.set("resources", mapper.valueToTree(resources));
        }
        payload.put("applyMode", applyMode.name());
        return json(HttpMethod.PUT, "/__fleet/api/config/" + MockIdValidator.requireValid(mockId), payload);
    }

    public JsonNode deleteConfig(String mockId, String resourceVersion, ConfigApplyMode applyMode) {
        if (resourceVersion == null || resourceVersion.isBlank()) {
            throw new IllegalArgumentException("resourceVersion is required");
        }
        requireApplyMode(applyMode);
        var payload = mapper.createObjectNode();
        payload.put("resourceVersion", resourceVersion);
        payload.put("applyMode", applyMode.name());
        return json(HttpMethod.DELETE, "/__fleet/api/config/" + MockIdValidator.requireValid(mockId), payload);
    }

    public boolean storageEnabled() {
        return json(HttpMethod.GET, "/__fleet/api/mappings", null).path("enabled").asBoolean(false);
    }

    private JsonNode json(HttpMethod method, String path, JsonNode payload) {
        TransportResponse response = raw(method, path, payload);
        if (response.body().length == 0) {
            return mapper.createObjectNode();
        }
        try {
            return mapper.readTree(response.body());
        } catch (Exception e) {
            throw new McpOperationException("INVALID_UPSTREAM_RESPONSE", "Fleet API returned invalid JSON", false,
                    Map.of("status", response.status()));
        }
    }

    private TransportResponse raw(HttpMethod method, String path, JsonNode payload) {
        return metrics.internalCall("api", () -> rawInternal(method, path, payload));
    }

    private TransportResponse rawInternal(HttpMethod method, String path, JsonNode payload) {
        try {
            byte[] body = payload == null ? new byte[0] : mapper.writeValueAsBytes(payload);
            HttpRequest<Buffer> request = client.requestAbs(method, apiBaseUrl + path)
                    .timeout(timeoutMillis)
                    .followRedirects(false)
                    .putHeader("Accept", "application/json");
            if (payload != null) {
                request.putHeader("Content-Type", "application/json");
            }
            TransportResponse response = HttpTransportSupport.await(request, body, maxPayloadBytes);
            if (response.status() < 200 || response.status() >= 300) {
                throw new McpOperationException("FLEET_API_ERROR", "Fleet API returned HTTP " + response.status(),
                        response.status() >= 500, Map.of("status", response.status(), "body", response.bodyAsString()));
            }
            return response;
        } catch (McpOperationException e) {
            throw e;
        } catch (Exception e) {
            throw new McpOperationException("INVALID_JSON", "Unable to serialize Fleet API request", false, Map.of());
        }
    }

    private static URI normalize(URI value) {
        if (value == null || value.getHost() == null
                || (!"http".equalsIgnoreCase(value.getScheme()) && !"https".equalsIgnoreCase(value.getScheme()))) {
            throw new IllegalArgumentException("apiBaseUrl must be an absolute HTTP(S) URL");
        }
        String normalized = value.toString();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return URI.create(normalized);
    }

    private static void requireApplyMode(ConfigApplyMode value) {
        if (value == null) {
            throw new IllegalArgumentException("applyMode is required");
        }
    }

    @PreDestroy
    void close() {
        client.close();
    }
}
