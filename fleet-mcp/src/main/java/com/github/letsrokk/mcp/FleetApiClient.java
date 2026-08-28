package com.github.letsrokk.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
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
    private final long lifecycleTimeoutMillis;
    private final McpMetrics metrics;

    @Inject
    public FleetApiClient(Vertx vertx, FleetMcpConfig config, ObjectMapper mapper, McpMetrics metrics) {
        this(vertx, config.apiBaseUrl(), config.timeout(), config.lifecycleTimeout(), config.maxPayloadBytes(), mapper,
                metrics);
    }

    FleetApiClient(Vertx vertx, URI apiBaseUrl, Duration timeout, int maxPayloadBytes, ObjectMapper mapper,
            McpMetrics metrics) {
        this(vertx, apiBaseUrl, timeout, timeout, maxPayloadBytes, mapper, metrics);
    }

    FleetApiClient(Vertx vertx, URI apiBaseUrl, Duration timeout, Duration lifecycleTimeout, int maxPayloadBytes,
            ObjectMapper mapper, McpMetrics metrics) {
        this.apiBaseUrl = normalize(apiBaseUrl);
        this.mapper = mapper;
        this.maxPayloadBytes = maxPayloadBytes;
        this.timeoutMillis = timeout.toMillis();
        this.lifecycleTimeoutMillis = lifecycleTimeout.toMillis();
        this.metrics = metrics;
        this.client = WebClient.create(vertx, new WebClientOptions()
                .setFollowRedirects(false)
                .setConnectTimeout((int) Math.min(Integer.MAX_VALUE,
                        Math.max(timeoutMillis, lifecycleTimeoutMillis)))
                .setIdleTimeout((int) Math.max(1, Math.max(timeout.toSeconds(), lifecycleTimeout.toSeconds())))
                .setIdleTimeoutUnit(TimeUnit.SECONDS));
    }

    public JsonNode listMocks() {
        return json(HttpMethod.GET, "/__fleet/api/mocks", null);
    }

    public JsonNode startMock(String mockId) {
        return json(HttpMethod.POST, "/__fleet/api/mocks/" + MockIdValidator.requireValid(mockId) + "/start", null);
    }

    public JsonNode stopMock(String mockId) {
        return json(HttpMethod.DELETE, "/__fleet/api/mocks/" + MockIdValidator.requireValid(mockId), null,
                lifecycleTimeoutMillis);
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
        return json(method, path, payload, timeoutMillis);
    }

    private JsonNode json(HttpMethod method, String path, JsonNode payload, long requestTimeoutMillis) {
        TransportResponse response = raw(method, path, payload, requestTimeoutMillis);
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

    private TransportResponse raw(HttpMethod method, String path, JsonNode payload, long requestTimeoutMillis) {
        return metrics.internalCall("api", () -> rawInternal(method, path, payload, requestTimeoutMillis));
    }

    private TransportResponse rawInternal(HttpMethod method, String path, JsonNode payload, long requestTimeoutMillis) {
        try {
            byte[] body = payload == null ? new byte[0] : mapper.writeValueAsBytes(payload);
            HttpRequest<Buffer> request = client.requestAbs(method, apiBaseUrl + path)
                    .timeout(requestTimeoutMillis)
                    .followRedirects(false)
                    .putHeader("Accept", "application/json");
            if (payload != null) {
                request.putHeader("Content-Type", "application/json");
            }
            TransportResponse response = HttpTransportSupport.await(request, body, maxPayloadBytes);
            if (response.status() < 200 || response.status() >= 300) {
                throw apiError(response, method != HttpMethod.GET);
            }
            return response;
        } catch (McpOperationException e) {
            if (method != HttpMethod.GET && "UPSTREAM_UNAVAILABLE".equals(e.code())
                    && !e.stateMayHaveChanged()) {
                throw new McpOperationException(e.code(), e.getMessage(), e.retryable(), true, e.details());
            }
            throw e;
        } catch (Exception e) {
            throw new McpOperationException("INVALID_JSON", "Unable to serialize Fleet API request", false, Map.of());
        }
    }

    private McpOperationException apiError(TransportResponse response, boolean stateMayHaveChanged) {
        try {
            JsonNode body = mapper.readTree(response.body());
            if (body != null && body.isObject() && body.path("code").isTextual()
                    && body.path("message").isTextual() && body.path("retryable").isBoolean()
                    && body.path("stateMayHaveChanged").isBoolean() && body.path("details").isObject()) {
                Map<String, Object> details = mapper.convertValue(body.path("details"),
                        new TypeReference<Map<String, Object>>() {});
                return new McpOperationException(body.path("code").asText(), body.path("message").asText(),
                        body.path("retryable").asBoolean(), body.path("stateMayHaveChanged").asBoolean(), details);
            }
        } catch (Exception ignored) {
            // Fall through to a stable transport-level error when the API did not return ApiError JSON.
        }
        return new McpOperationException("FLEET_API_ERROR", "Fleet API returned HTTP " + response.status(),
                response.status() >= 500, stateMayHaveChanged,
                Map.of("status", response.status(), "body", response.bodyAsString()));
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
