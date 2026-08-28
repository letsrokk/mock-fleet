package com.github.letsrokk.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import java.net.URI;
import java.time.Duration;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DependencyHealthProbeTest {

    private Vertx vertx;
    private HttpServer server;
    private URI baseUrl;

    @BeforeEach
    void start() {
        vertx = Vertx.vertx();
        server = vertx.createHttpServer().requestHandler(request -> {
            switch (request.path()) {
                case "/healthy" -> request.response().setStatusCode(200).end("{\"status\":\"UP\"}");
                case "/down" -> request.response().setStatusCode(200).end("{\"status\":\"DOWN\"}");
                case "/malformed" -> request.response().setStatusCode(200).end("not-json");
                case "/slow" -> vertx.setTimer(500, ignored -> request.response().end("{\"status\":\"UP\"}"));
                default -> request.response().setStatusCode(503).end("{\"status\":\"UP\"}");
            }
        }).listen(0, "127.0.0.1").toCompletionStage().toCompletableFuture().join();
        baseUrl = URI.create("http://127.0.0.1:" + server.actualPort());
    }

    @AfterEach
    void stop() {
        server.close().toCompletionStage().toCompletableFuture().join();
        vertx.close().toCompletionStage().toCompletableFuture().join();
    }

    @Test
    void requiresHttp200AndUpStatus() {
        try (DependencyHealthProbe probe = new DependencyHealthProbe(vertx, Duration.ofMillis(100),
                new ObjectMapper())) {
            assertEquals(HealthCheckResponse.Status.UP, probe.check("fleet-api", baseUrl, "/healthy").getStatus());
            assertEquals(HealthCheckResponse.Status.DOWN, probe.check("fleet-api", baseUrl, "/down").getStatus());
            assertEquals(HealthCheckResponse.Status.DOWN,
                    probe.check("fleet-api", baseUrl, "/malformed").getStatus());
            assertEquals(HealthCheckResponse.Status.DOWN, probe.check("fleet-api", baseUrl, "/status").getStatus());
        }
    }

    @Test
    void reportsUnavailableAndSlowDependenciesAsDown() {
        try (DependencyHealthProbe probe = new DependencyHealthProbe(vertx, Duration.ofMillis(50),
                new ObjectMapper())) {
            assertEquals(HealthCheckResponse.Status.DOWN, probe.check("fleet-proxy", baseUrl, "/slow").getStatus());
            assertEquals(HealthCheckResponse.Status.DOWN,
                    probe.check("fleet-proxy", URI.create("http://127.0.0.1:1"), "/health/ready").getStatus());
        }
    }
}
