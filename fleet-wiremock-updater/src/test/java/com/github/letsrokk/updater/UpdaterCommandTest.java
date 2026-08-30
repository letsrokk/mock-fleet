package com.github.letsrokk.updater;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.ConfigMapList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UpdaterCommandTest {

    @Test
    void derivesPullableImageRepositoryForPrivateRegistry() {
        String imageRepository = UpdaterCommand.imageRepository(
                URI.create("http://registry.testing.svc:5000"), "mirror/wiremock", Optional.empty());

        assertEquals("registry.testing.svc:5000/mirror/wiremock", imageRepository);
        assertEquals(Map.of("3.13.2", "registry.testing.svc:5000/mirror/wiremock:3.13.2-2"),
                CatalogSelection.select(imageRepository, List.of("3.13.2-2"), 1).selectable());
    }

    @Test
    void keepsDockerHubDefaultImageRepositoryUnqualified() {
        assertEquals("wiremock/wiremock",
                UpdaterCommand.imageRepository(URI.create("https://registry-1.docker.io"),
                        "wiremock/wiremock", Optional.empty()));
        assertEquals("mirror.example/wiremock/team",
                UpdaterCommand.imageRepository(URI.create("https://registry-1.docker.io"),
                        "wiremock/wiremock", Optional.of("mirror.example/wiremock/team")));
        assertThrows(IllegalArgumentException.class,
                () -> UpdaterCommand.imageRepository(URI.create("https://registry-1.docker.io"),
                        "wiremock/wiremock", Optional.of("wiremock:3")));
    }

    @Test
    void acceptsDockerRepositorySeparatorsAndRejectsMalformedComponents() {
        URI registry = URI.create("https://registry-1.docker.io");
        assertEquals("team/mock--image",
                UpdaterCommand.imageRepository(registry, "wiremock/wiremock",
                        Optional.of("team/mock--image")));
        assertEquals("registry.example:5000/team/mock__image",
                UpdaterCommand.imageRepository(registry, "wiremock/wiremock",
                        Optional.of("registry.example:5000/team/mock__image")));

        for (String invalid : List.of("team/-mock", "team/mock_", "team/mock:::image", "team//mock")) {
            assertThrows(IllegalArgumentException.class,
                    () -> UpdaterCommand.imageRepository(registry, "wiremock/wiremock", Optional.of(invalid)));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void privateRegistryRunWritesPullableImagesWithRegistryHost() throws Exception {
        HttpServer registry = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        registry.createContext("/v2/mirror/wiremock/tags/list",
                exchange -> json(exchange, "{\"tags\":[\"3.14.1-2\"]}"));
        registry.start();
        try {
            String registryUrl = "http://127.0.0.1:" + registry.getAddress().getPort();
            UpdaterConfig config = config(registryUrl, "mirror/wiremock", Optional.empty());
            KubernetesClient kubernetes = mock(KubernetesClient.class);
            MixedOperation<ConfigMap, ConfigMapList, Resource<ConfigMap>> configMaps = mock(MixedOperation.class);
            NonNamespaceOperation<ConfigMap, ConfigMapList, Resource<ConfigMap>> namespaced =
                    mock(NonNamespaceOperation.class);
            Resource<ConfigMap> catalog = mock(Resource.class);
            Resource<ConfigMap> baseline = mock(Resource.class);
            Resource<ConfigMap> user = mock(Resource.class);
            Resource<ConfigMap> updated = mock(Resource.class);
            when(kubernetes.configMaps()).thenReturn(configMaps);
            when(configMaps.inNamespace("test")).thenReturn(namespaced);
            when(namespaced.withName("catalog")).thenReturn(catalog);
            when(namespaced.withName("baseline")).thenReturn(baseline);
            when(namespaced.withName("user")).thenReturn(user);
            when(catalog.get()).thenReturn(new ConfigMapBuilder().withNewMetadata().withName("catalog")
                    .withResourceVersion("30").endMetadata().withData(Map.of(
                            "defaultVersion", "3.13.2",
                            "selectable.3.13.2", "old.example/wiremock:3.13.2-2")).build());
            ConfigMap emptyConfig = new ConfigMapBuilder().withNewMetadata().endMetadata()
                    .withData(Map.of("wiremock.yaml", "wiremock:\n  mocks: []\n")).build();
            when(baseline.get()).thenReturn(new ConfigMapBuilder(emptyConfig)
                    .editMetadata().withName("baseline").endMetadata().build());
            when(user.get()).thenReturn(new ConfigMapBuilder(emptyConfig)
                    .editMetadata().withName("user").endMetadata().build());
            when(namespaced.resource(org.mockito.ArgumentMatchers.any(ConfigMap.class))).thenReturn(updated);
            UpdaterCommand command = command(config, kubernetes);

            assertEquals(0, command.run());

            ArgumentCaptor<ConfigMap> update = ArgumentCaptor.forClass(ConfigMap.class);
            verify(namespaced).resource(update.capture());
            assertEquals("127.0.0.1:" + registry.getAddress().getPort()
                            + "/mirror/wiremock:3.14.1-2",
                    update.getValue().getData().get("selectable.3.14.1"));
        } finally {
            registry.stop(0);
        }
    }

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

    private static UpdaterConfig config(String registryUrl, String repository,
                                        Optional<String> imageRepository) {
        UpdaterConfig config = mock(UpdaterConfig.class);
        when(config.registryUrl()).thenReturn(registryUrl);
        when(config.repository()).thenReturn(repository);
        when(config.imageRepository()).thenReturn(imageRepository);
        when(config.pageSize()).thenReturn(100);
        when(config.minorLines()).thenReturn(1);
        when(config.defaultVersionConstraint()).thenReturn("3.x");
        when(config.namespace()).thenReturn("test");
        when(config.catalogConfigMapName()).thenReturn("catalog");
        when(config.baselineConfigMapName()).thenReturn("baseline");
        when(config.userConfigMapName()).thenReturn("user");
        when(config.configKey()).thenReturn("wiremock.yaml");
        when(config.registryUsername()).thenReturn(Optional.empty());
        when(config.registryPassword()).thenReturn(Optional.empty());
        return config;
    }

    private static UpdaterCommand command(UpdaterConfig config, KubernetesClient kubernetes) {
        UpdaterCommand command = new UpdaterCommand();
        command.config = config;
        command.kubernetes = kubernetes;
        command.json = new ObjectMapper();
        return command;
    }

    private static void json(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
