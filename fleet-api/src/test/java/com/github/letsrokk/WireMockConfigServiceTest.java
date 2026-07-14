package com.github.letsrokk;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.ConfigMapList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NamespaceableResource;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WireMockConfigServiceTest {

    @Test
    void watchModifiedEventUpdatesLocalUserConfigWithoutReload() {
        WireMockConfigService service = service(mock(KubernetesClient.class), config());
        ConfigMap configMap = configMap("user-config", "42", """
                wiremock:
                  default:
                    options: []
                  mocks:
                    - id: demo
                      options:
                        - --verbose
                """);

        service.handleUserConfigWatchEvent(Watcher.Action.MODIFIED, configMap);

        assertEquals(List.of("--verbose"), service.wireMockOptions.optionsFor("demo"));
    }

    @Test
    void upsertWritesConfiguredUserConfigMapName() {
        KubernetesClient kubernetesClient = mock(KubernetesClient.class);
        @SuppressWarnings("unchecked")
        MixedOperation<ConfigMap, ConfigMapList, Resource<ConfigMap>> configMaps = mock(MixedOperation.class);
        @SuppressWarnings("unchecked")
        NonNamespaceOperation<ConfigMap, ConfigMapList, Resource<ConfigMap>> namespacedConfigMaps =
                mock(NonNamespaceOperation.class);
        @SuppressWarnings("unchecked")
        Resource<ConfigMap> configMapResource = mock(Resource.class);
        @SuppressWarnings("unchecked")
        NamespaceableResource<ConfigMap> createdConfigMap = mock(NamespaceableResource.class);

        when(kubernetesClient.getNamespace()).thenReturn("test");
        when(kubernetesClient.configMaps()).thenReturn(configMaps);
        when(configMaps.inNamespace("test")).thenReturn(namespacedConfigMaps);
        when(namespacedConfigMaps.withName("user-config")).thenReturn(configMapResource);
        when(configMapResource.get()).thenReturn(null);
        when(namespacedConfigMaps.resource(argThat(configMap ->
                "user-config".equals(configMap.getMetadata().getName())
                        && "test".equals(configMap.getMetadata().getNamespace())
                        && configMap.getData().get("wiremock-options.yaml").contains("id: demo")
                        && configMap.getData().get("wiremock-options.yaml").contains("--verbose"))))
                .thenReturn(createdConfigMap);
        WireMockConfigService service = service(kubernetesClient, config());

        service.upsertMockConfig("demo", new WireMockConfigService.ConfigUpdateRequest(
                null,
                List.of("--verbose"),
                new WireMockConfigService.ResourceData(Map.of(), Map.of()),
                "futureOnly"));

        verify(createdConfigMap).create();
    }

    private WireMockConfigService service(KubernetesClient kubernetesClient, MockFleetConfig config) {
        WireMockConfigService service = new WireMockConfigService();
        service.config = config;
        service.kubernetesClient = kubernetesClient;
        service.wireMockOptions = new WireMockOptions();
        service.podManager = mock(PodManager.class);
        when(service.podManager.listActiveMocks()).thenReturn(List.of());
        return service;
    }

    private MockFleetConfig config() {
        MockFleetConfig config = mock(MockFleetConfig.class);
        when(config.namespace()).thenReturn("mock-fleet");
        when(config.wiremockUserConfigMapName()).thenReturn(Optional.of("user-config"));
        when(config.wiremockConfigKey()).thenReturn("wiremock-options.yaml");
        return config;
    }

    private ConfigMap configMap(String name, String resourceVersion, String yaml) {
        return new ConfigMapBuilder()
                .withNewMetadata()
                    .withName(name)
                    .withNamespace("test")
                    .withResourceVersion(resourceVersion)
                .endMetadata()
                .withData(Map.of("wiremock-options.yaml", yaml))
                .build();
    }
}
