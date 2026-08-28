package com.github.letsrokk.mcp;

import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Singleton
public final class FleetProxyClient implements FleetProxyTransport {

    private final FleetProxyRequestFactory requestFactory;
    private final WebClient client;
    private final int maxPayloadBytes;
    private final long timeoutMillis;
    private final McpMetrics metrics;

    @Inject
    public FleetProxyClient(Vertx vertx, FleetMcpConfig config, McpMetrics metrics) {
        this(vertx, config.proxyBaseUrl(), config.routingMode(), config.fleetHost().orElse(null), config.timeout(),
                config.maxPayloadBytes(), metrics);
    }

    FleetProxyClient(Vertx vertx, URI proxyBaseUrl, RoutingMode routingMode, String fleetHost, Duration timeout,
            int maxPayloadBytes, McpMetrics metrics) {
        requestFactory = new FleetProxyRequestFactory(proxyBaseUrl, routingMode, fleetHost);
        timeoutMillis = timeout.toMillis();
        this.maxPayloadBytes = maxPayloadBytes;
        this.metrics = metrics;
        client = WebClient.create(vertx, new WebClientOptions()
                .setFollowRedirects(false)
                .setConnectTimeout((int) Math.min(Integer.MAX_VALUE, timeoutMillis))
                .setIdleTimeout((int) Math.max(1, timeout.toSeconds()))
                .setIdleTimeoutUnit(TimeUnit.SECONDS));
    }

    @Override
    public TransportResponse execute(String mockId, TransportRequest request) {
        return metrics.internalCall("proxy", () -> executeInternal(mockId, request));
    }

    @Override
    public CollectionScan scanCollection(String mockId, TransportRequest request, ObjectMapper mapper,
            String collectionField, long position, int limit, long maxBytes, int maxItems) {
        return metrics.internalCall("proxy", () -> scanCollectionInternal(mockId, request, mapper, collectionField,
                position, limit, maxBytes, maxItems));
    }

    private TransportResponse executeInternal(String mockId, TransportRequest request) {
        if (request.body().length > maxPayloadBytes) {
            throw new McpOperationException("RESULT_TOO_LARGE", "Request payload exceeds the configured limit", false,
                    java.util.Map.of("limitBytes", maxPayloadBytes));
        }
        FleetProxyRequestFactory.ProxyRequest resolved = requestFactory.create(mockId, request.target());
        HttpRequest<Buffer> outbound = client.requestAbs(request.method(), resolved.uri().toString())
                .timeout(timeoutMillis)
                .followRedirects(false);
        HttpTransportSupport.applyHeaders(outbound, request.headers());
        if (resolved.hostHeader() != null) {
            outbound.putHeader("Host", resolved.hostHeader());
        }
        return HttpTransportSupport.await(outbound, request.body(), maxPayloadBytes);
    }

    private CollectionScan scanCollectionInternal(String mockId, TransportRequest request, ObjectMapper mapper,
            String collectionField, long position, int limit, long maxBytes, int maxItems) {
        if (request.body().length > maxPayloadBytes) {
            throw new McpOperationException("RESULT_TOO_LARGE", "Request payload exceeds the configured limit", false,
                    java.util.Map.of("limitBytes", maxPayloadBytes));
        }
        FleetProxyRequestFactory.ProxyRequest resolved = requestFactory.create(mockId, request.target());
        HttpRequest<Buffer> outbound = client.requestAbs(request.method(), resolved.uri().toString())
                .timeout(timeoutMillis)
                .followRedirects(false);
        HttpTransportSupport.applyHeaders(outbound, request.headers());
        if (resolved.hostHeader() != null) {
            outbound.putHeader("Host", resolved.hostHeader());
        }
        return HttpTransportSupport.awaitCollection(outbound, request.body(), mapper, collectionField, position,
                limit, maxBytes, maxItems);
    }

    @PreDestroy
    void close() {
        client.close();
    }
}
