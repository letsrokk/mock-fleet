package com.github.letsrokk;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.ConfigMapList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NamespaceableResource;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WireMockConfigServiceTest {

    @Test
    void viewPreservesInheritedUserResourcesAsNull() {
        WireMockConfigService.ConfigView view = configViewForUserYaml("""
                wiremock:
                  default:
                    options: []
                  mocks:
                    - id: demo
                      options:
                        - --verbose
                """);

        WireMockConfigService.MockConfigView mock = view.mocks().getFirst();
        assertNull(mock.user().resources());
        assertEquals("0.5", mock.effective().resources().requests().get("cpu"));
        assertEquals("1Gi", mock.effective().resources().limits().get("memory"));
    }

    @Test
    void viewPreservesExplicitEmptyUserResourcesAsAnOverride() {
        WireMockConfigService.ConfigView view = configViewForUserYaml("""
                wiremock:
                  default:
                    options: []
                  mocks:
                    - id: demo
                      options:
                        - --verbose
                      resources:
                        requests: {}
                        limits: {}
                """);

        WireMockConfigService.MockConfigView mock = view.mocks().getFirst();
        assertEquals(Map.of(), mock.user().resources().requests());
        assertEquals(Map.of(), mock.user().resources().limits());
        assertEquals(Map.of(), mock.effective().resources().requests());
        assertEquals(Map.of(), mock.effective().resources().limits());
    }

    @Test
    void loadUserConfigCreatesMissingUserConfigMapBeforeStartingWatch() {
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
        Watch watch = mock(Watch.class);

        when(kubernetesClient.getNamespace()).thenReturn("test");
        when(kubernetesClient.configMaps()).thenReturn(configMaps);
        when(configMaps.inNamespace("test")).thenReturn(namespacedConfigMaps);
        when(namespacedConfigMaps.withName("user-config")).thenReturn(configMapResource);
        when(configMapResource.get()).thenReturn(null);
        when(configMapResource.watch(any())).thenReturn(watch);
        when(namespacedConfigMaps.resource(argThat(configMap ->
                "user-config".equals(configMap.getMetadata().getName())
                        && "test".equals(configMap.getMetadata().getNamespace())
                        && configMap.getData().containsKey("wiremock-options.yaml"))))
                .thenReturn(createdConfigMap);
        ConfigMap created = configMap("user-config", "43", """
                wiremock:
                  default:
                    options: []
                  mocks: []
                """);
        when(createdConfigMap.create()).thenReturn(created);
        WireMockConfigService service = service(kubernetesClient, config());

        service.loadUserConfig();

        InOrder inOrder = inOrder(createdConfigMap, configMapResource);
        inOrder.verify(createdConfigMap).create();
        inOrder.verify(configMapResource).watch(any());
        assertEquals(List.of(), service.wireMockOptions.optionsFor("demo"));
    }

    @Test
    void loadUserConfigDoesNotOverwriteExistingUserConfigMap() {
        KubernetesClient kubernetesClient = mock(KubernetesClient.class);
        @SuppressWarnings("unchecked")
        MixedOperation<ConfigMap, ConfigMapList, Resource<ConfigMap>> configMaps = mock(MixedOperation.class);
        @SuppressWarnings("unchecked")
        NonNamespaceOperation<ConfigMap, ConfigMapList, Resource<ConfigMap>> namespacedConfigMaps =
                mock(NonNamespaceOperation.class);
        @SuppressWarnings("unchecked")
        Resource<ConfigMap> configMapResource = mock(Resource.class);

        ConfigMap existing = configMap("user-config", "42", """
                wiremock:
                  default:
                    options: []
                  mocks:
                    - id: demo
                      options:
                        - --verbose
                """);
        when(kubernetesClient.getNamespace()).thenReturn("test");
        when(kubernetesClient.configMaps()).thenReturn(configMaps);
        when(configMaps.inNamespace("test")).thenReturn(namespacedConfigMaps);
        when(namespacedConfigMaps.withName("user-config")).thenReturn(configMapResource);
        when(configMapResource.get()).thenReturn(existing);
        WireMockConfigService service = service(kubernetesClient, config());

        service.loadUserConfig();

        verify(namespacedConfigMaps, never()).resource(any());
        assertEquals(List.of("--verbose"), service.wireMockOptions.optionsFor("demo"));
    }

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

    @Test
    void deleteWritesConfiguredUserConfigMapName() {
        KubernetesClient kubernetesClient = mock(KubernetesClient.class);
        @SuppressWarnings("unchecked")
        MixedOperation<ConfigMap, ConfigMapList, Resource<ConfigMap>> configMaps = mock(MixedOperation.class);
        @SuppressWarnings("unchecked")
        NonNamespaceOperation<ConfigMap, ConfigMapList, Resource<ConfigMap>> namespacedConfigMaps =
                mock(NonNamespaceOperation.class);
        @SuppressWarnings("unchecked")
        Resource<ConfigMap> configMapResource = mock(Resource.class);
        @SuppressWarnings("unchecked")
        NamespaceableResource<ConfigMap> updatedConfigMap = mock(NamespaceableResource.class);

        ConfigMap existing = configMap("user-config", "42", """
                wiremock:
                  default:
                    options: []
                  mocks:
                    - id: demo
                      options:
                        - --verbose
                """);
        when(kubernetesClient.getNamespace()).thenReturn("test");
        when(kubernetesClient.configMaps()).thenReturn(configMaps);
        when(configMaps.inNamespace("test")).thenReturn(namespacedConfigMaps);
        when(namespacedConfigMaps.withName("user-config")).thenReturn(configMapResource);
        when(configMapResource.get()).thenReturn(existing);
        when(namespacedConfigMaps.resource(argThat(configMap ->
                "user-config".equals(configMap.getMetadata().getName())
                        && "test".equals(configMap.getMetadata().getNamespace())
                        && !configMap.getData().get("wiremock-options.yaml").contains("id: demo"))))
                .thenReturn(updatedConfigMap);
        WireMockConfigService service = service(kubernetesClient, config());

        service.deleteMockConfig("demo", new WireMockConfigService.ConfigUpdateRequest(
                "42",
                List.of(),
                new WireMockConfigService.ResourceData(Map.of(), Map.of()),
                "futureOnly"));

        verify(updatedConfigMap).update();
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

    private WireMockConfigService.ConfigView configViewForUserYaml(String userYaml) {
        KubernetesClient kubernetesClient = mock(KubernetesClient.class);
        @SuppressWarnings("unchecked")
        MixedOperation<ConfigMap, ConfigMapList, Resource<ConfigMap>> configMaps = mock(MixedOperation.class);
        @SuppressWarnings("unchecked")
        NonNamespaceOperation<ConfigMap, ConfigMapList, Resource<ConfigMap>> namespacedConfigMaps =
                mock(NonNamespaceOperation.class);
        @SuppressWarnings("unchecked")
        Resource<ConfigMap> configMapResource = mock(Resource.class);

        when(kubernetesClient.configMaps()).thenReturn(configMaps);
        when(configMaps.inNamespace("mock-fleet")).thenReturn(namespacedConfigMaps);
        when(namespacedConfigMaps.withName("user-config")).thenReturn(configMapResource);
        when(configMapResource.get()).thenReturn(configMap("user-config", "42", userYaml));

        WireMockConfigService service = service(kubernetesClient, config());
        service.wireMockOptions.load(new ByteArrayInputStream("""
                wiremock:
                  default:
                    options: []
                    resources:
                      requests:
                        cpu: "0.5"
                        memory: 512Mi
                      limits:
                        cpu: "1"
                        memory: 1Gi
                  mocks: []
                """.getBytes(StandardCharsets.UTF_8)));
        return service.view();
    }

    private MockFleetConfig config() {
        MockFleetConfig config = mock(MockFleetConfig.class);
        when(config.namespace()).thenReturn("mock-fleet");
        when(config.wiremockUserConfigMapName()).thenReturn(Optional.of("user-config"));
        when(config.wiremockConfigKey()).thenReturn("wiremock-options.yaml");
        MockFleetConfig.ProxyConfig proxy = mock(MockFleetConfig.ProxyConfig.class);
        MockFleetConfig.RoutingConfig routing = mock(MockFleetConfig.RoutingConfig.class);
        when(config.proxy()).thenReturn(proxy);
        when(proxy.routing()).thenReturn(routing);
        when(routing.mode()).thenReturn(MockFleetConfig.RoutingMode.HOST);
        when(routing.host()).thenReturn("mock-fleet.localhost");
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
