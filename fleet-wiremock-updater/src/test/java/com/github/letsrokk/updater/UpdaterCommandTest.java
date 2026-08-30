package com.github.letsrokk.updater;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UpdaterCommandTest {

    @Test
    void registryParsingFailureLeavesKubernetesCatalogUntouched() throws Exception {
        HttpServer registry = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        registry.createContext("/v2/example/wiremock/tags/list", exchange -> json(exchange, "not-json"));
        registry.start();
        try {
            UpdaterConfig config = mock(UpdaterConfig.class);
            KubernetesClient kubernetes = mock(KubernetesClient.class);
            when(config.registryUrl()).thenReturn("http://127.0.0.1:" + registry.getAddress().getPort());
            when(config.repository()).thenReturn("example/wiremock");
            when(config.pageSize()).thenReturn(100);
            when(config.registryUsername()).thenReturn(Optional.empty());
            when(config.registryPassword()).thenReturn(Optional.empty());
            UpdaterCommand command = new UpdaterCommand();
            command.config = config;
            command.kubernetes = kubernetes;
            command.json = new ObjectMapper();

            assertThrows(IllegalStateException.class, command::run);

            verify(kubernetes, never()).configMaps();
            verify(kubernetes, never()).pods();
        } finally {
            registry.stop(0);
        }
    }

    private static void json(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
