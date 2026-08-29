package com.github.letsrokk;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.ConfigMapList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

class WireMockVersionCatalogServiceTest {

    @Test
    void loadsAndWatchesOnlyTheConfiguredNamedCatalog() {
        Fixture fixture = fixture(catalog("41", "3.13.2", "wiremock/wiremock:3.13.2-2"));

        fixture.service.loadCatalog();

        assertEquals("41", fixture.service.catalog().resourceVersion());
        verify(fixture.configMaps, times(2)).inNamespace("testing");
        verify(fixture.namespaced, times(2)).withName("catalog-name");
        verify(fixture.resource).watch(any(Watcher.class));
    }

    @Test
    void publishesAValidModifiedSnapshotWithItsResourceVersion() {
        Fixture fixture = fixture(catalog("41", "3.13.2", "wiremock/wiremock:3.13.2-2"));
        fixture.service.loadCatalog();

        fixture.service.handleCatalogWatchEvent(Watcher.Action.MODIFIED,
                catalog("42", "3.12.1", "wiremock/wiremock:3.12.1-2"));

        assertEquals("3.12.1", fixture.service.catalog().defaultVersion().toString());
        assertEquals("42", fixture.service.catalog().resourceVersion());
    }

    @Test
    void invalidOrPartialWatchEventsKeepTheLastValidSnapshot() {
        Fixture fixture = fixture(catalog("41", "3.13.2", "wiremock/wiremock:3.13.2-2"));
        fixture.service.loadCatalog();

        fixture.service.handleCatalogWatchEvent(Watcher.Action.MODIFIED,
                catalog("42", "3.13.2", "wiremock/wiremock:3.12.1-2"));
        fixture.service.handleCatalogWatchEvent(Watcher.Action.DELETED, null);
        fixture.service.handleCatalogWatchEvent(Watcher.Action.ERROR, null);

        assertEquals("41", fixture.service.catalog().resourceVersion());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Fixture fixture(ConfigMap catalog) {
        MockFleetConfig config = mock(MockFleetConfig.class);
        when(config.namespace()).thenReturn("testing");
        when(config.wiremockVersionCatalogConfigMapName()).thenReturn(Optional.of("catalog-name"));

        KubernetesClient client = mock(KubernetesClient.class);
        MixedOperation<ConfigMap, ConfigMapList, Resource<ConfigMap>> configMaps = mock(MixedOperation.class);
        NonNamespaceOperation<ConfigMap, ConfigMapList, Resource<ConfigMap>> namespaced =
                mock(NonNamespaceOperation.class);
        Resource<ConfigMap> resource = mock(Resource.class);
        when(client.configMaps()).thenReturn(configMaps);
        when(configMaps.inNamespace("testing")).thenReturn(namespaced);
        when(namespaced.withName("catalog-name")).thenReturn(resource);
        when(resource.get()).thenReturn(catalog);

        WireMockVersionCatalogService service = new WireMockVersionCatalogService();
        service.config = config;
        service.kubernetesClient = client;
        service.parser = new WireMockVersionCatalogParser();
        return new Fixture(service, configMaps, namespaced, resource);
    }

    private ConfigMap catalog(String resourceVersion, String version, String image) {
        return new ConfigMapBuilder()
                .withNewMetadata()
                    .withName("catalog-name")
                    .withResourceVersion(resourceVersion)
                .endMetadata()
                .withData(Map.of(
                        "defaultVersion", version,
                        "selectable." + version, image))
                .build();
    }

    private record Fixture(WireMockVersionCatalogService service,
                           MixedOperation<ConfigMap, ConfigMapList, Resource<ConfigMap>> configMaps,
                           NonNamespaceOperation<ConfigMap, ConfigMapList, Resource<ConfigMap>> namespaced,
                           Resource<ConfigMap> resource) {
    }
}
