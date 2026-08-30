package com.github.letsrokk;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.ConfigMapList;
import io.fabric8.kubernetes.api.model.ResourceRequirements;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NamespaceableResource;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WireMockConfigServiceTest {

    @Test
    void viewReportsCatalogAndDesiredRuntimeDrift() {
        WireMockConfigService service = serviceWithUserConfig("""
                wiremock:
                  default:
                    options: []
                  mocks: []
                """);
        installCatalog(service, versionCatalog());
        when(service.podManager.listMocks()).thenReturn(List.of(new PodManager.MockPodStatus(
                "demo", "mock-fleet-demo-1", MockLifecycleStatus.RUNNING, null, "3.12.1")));

        WireMockConfigService.ConfigView view = service.view();

        assertEquals("3.13.2", view.defaultVersion());
        assertEquals("catalog-17", view.catalogResourceVersion());
        assertEquals(List.of(
                new WireMockConfigService.VersionView("3.13.2", "wiremock/wiremock:3.13.2-7", true),
                new WireMockConfigService.VersionView("3.12.1", "wiremock/wiremock:3.12.1-2", false)),
                view.versions());
        assertEquals("3.13.2", view.mocks().getFirst().wireMockVersion());
        assertEquals("3.12.1", view.mocks().getFirst().runtimeVersion());
    }

    @Test
    void rejectsRetainedVersionAsANewSelection() {
        WireMockConfigService service = serviceWithUserConfig("""
                wiremock:
                  default:
                    options: []
                  mocks: []
                """);
        installCatalog(service, versionCatalog());

        ApiException exception = assertThrows(ApiException.class, () -> service.upsertMockConfig("demo",
                new WireMockConfigService.ConfigUpdateRequest(
                        "42", "3.12.1", List.of(), null, "futureOnly")));

        assertEquals("UNSUPPORTED_WIREMOCK_VERSION", ((ApiError) exception.getResponse().getEntity()).code());
    }

    @Test
    void allowsRetainedVersionOnlyWhenAlreadyReferencedForTheMock() {
        WireMockConfigService service = service(mock(KubernetesClient.class), config());
        WireMockVersionCatalog catalog = versionCatalog();
        service.wireMockOptions.load(new ByteArrayInputStream("""
                wiremock:
                  default:
                    options: []
                  mocks:
                    - id: baseline-pinned
                      version: 3.12.1
                      options: []
                """.getBytes(StandardCharsets.UTF_8)));
        WireMockConfigDocument currentUser = WireMockConfigDocument.load("""
                wiremock:
                  default:
                    options: []
                  mocks:
                    - id: user-pinned
                      version: 3.12.1
                      options: []
                """);

        assertDoesNotThrow(() -> service.validateVersionSelection(
                "baseline-pinned", "3.12.1", currentUser, catalog));
        assertDoesNotThrow(() -> service.validateVersionSelection(
                "user-pinned", "3.12.1", currentUser, catalog));
        ApiException exception = assertThrows(ApiException.class, () -> service.validateVersionSelection(
                "new-pin", "3.12.1", currentUser, catalog));
        assertEquals("UNSUPPORTED_WIREMOCK_VERSION", ((ApiError) exception.getResponse().getEntity()).code());
    }

    @Test
    void optionCatalogUsesTheConfiguredVersionForBlankRequestsAndExcludesCredentials() {
        KubernetesClient kubernetesClient = mock(KubernetesClient.class);
        WireMockConfigService service = service(kubernetesClient, config());

        WireMockConfigService.OptionCatalogView catalog = service.optionCatalog(" ");

        assertEquals("3.13.2", catalog.wireMockVersion());
        assertEquals("supported", catalog.catalogStatus());
        assertTrue(catalog.options().stream().anyMatch(option -> "--verbose".equals(option.name())));
        assertTrue(catalog.options().stream().noneMatch(option -> Set.of(
                "--admin-api-basic-auth",
                "--ca-keystore-password",
                "--keystore-password",
                "--key-manager-password",
                "--truststore-password").contains(option.name())));
        assertTrue(catalog.options().stream().allMatch(option -> option.name() != null
                && option.label() != null
                && option.kind() != null
                && option.group() != null
                && option.description() != null
                && option.values() != null));
        verifyNoInteractions(kubernetesClient);
    }

    @Test
    void optionCatalogPreservesNewerThanResearchedFallbackAndRejectsInvalidVersions() {
        WireMockConfigService service = service(mock(KubernetesClient.class), config());

        assertEquals("newer_unresearched", service.optionCatalog("3.14.0").catalogStatus());
        for (String version : List.of("3.14", "3.01.0", "2.13.2", "4.0.0")) {
            ApiException exception = assertThrows(ApiException.class, () -> service.optionCatalog(version));
            assertEquals(400, exception.getResponse().getStatus());
            assertEquals("INVALID_WIREMOCK_VERSION", ((ApiError) exception.getResponse().getEntity()).code());
        }
    }

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
    void viewNormalizesExplicitEmptyUserResourcesToTheBaseline() {
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
        assertEquals(Map.of("cpu", "0.5", "memory", "512Mi"), mock.effective().resources().requests());
        assertEquals(Map.of("cpu", "1", "memory", "1Gi"), mock.effective().resources().limits());
    }

    @Test
    void viewMergesPartialLegacyResourceMapsWithTheBaseline() {
        WireMockConfigService.ConfigView view = configViewForUserYaml("""
                wiremock:
                  default:
                    options: []
                  mocks:
                    - id: demo
                      options: []
                      resources:
                        requests:
                          cpu: "0.75"
                        limits:
                          memory: 1536Mi
                """);

        WireMockConfigService.MockConfigView mock = view.mocks().getFirst();
        assertEquals(Map.of("cpu", "0.75", "memory", "512Mi"), mock.effective().resources().requests());
        assertEquals(Map.of("cpu", "1", "memory", "1536Mi"), mock.effective().resources().limits());
    }

    @Test
    void viewRedactsLegacyPasswordOptionsWithoutMutatingRetainedConfiguration() {
        String secret = "legacy-secret-must-not-escape";
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
                        - --keystore-password
                        - %s
                """.formatted(secret));
        when(kubernetesClient.configMaps()).thenReturn(configMaps);
        when(configMaps.inNamespace("mock-fleet")).thenReturn(namespacedConfigMaps);
        when(namespacedConfigMaps.withName("user-config")).thenReturn(configMapResource);
        when(configMapResource.get()).thenReturn(existing);
        WireMockConfigService service = service(kubernetesClient, config());

        WireMockConfigService.ConfigView view = service.view();

        String renderedView = view.toString();
        org.junit.jupiter.api.Assertions.assertTrue(renderedView.contains("--keystore-password"));
        org.junit.jupiter.api.Assertions.assertFalse(renderedView.contains(secret));
        assertEquals(List.of("--keystore-password", secret),
                service.wireMockOptions.userConfig().mockConfigs().get("demo").options());
        org.junit.jupiter.api.Assertions.assertTrue(existing.getData().get("wiremock-options.yaml").contains(secret));
    }

    @Test
    void retainedConfigMapOptionsAreRevalidatedBeforeStartup() {
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
                        - --async-response-threads
                        - "1.5"
                """);
        when(kubernetesClient.configMaps()).thenReturn(configMaps);
        when(configMaps.inNamespace("mock-fleet")).thenReturn(namespacedConfigMaps);
        when(namespacedConfigMaps.withName("user-config")).thenReturn(configMapResource);
        when(configMapResource.get()).thenReturn(existing);
        WireMockConfigService service = service(kubernetesClient, config());
        service.view();

        jakarta.ws.rs.WebApplicationException exception = assertThrows(jakarta.ws.rs.WebApplicationException.class,
                () -> service.wireMockOptions.optionsFor("demo"));

        assertEquals(400, exception.getResponse().getStatus());
        org.junit.jupiter.api.Assertions.assertTrue(exception.getMessage().contains("requires an integer"));
    }

    @Test
    void viewListsOnlySortedUserSavedMockIds() {
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
                    - id: zeta
                      options: []
                    - id: alpha
                      options:
                        - --verbose
                """);
        when(kubernetesClient.getNamespace()).thenReturn("test");
        when(kubernetesClient.configMaps()).thenReturn(configMaps);
        when(configMaps.inNamespace("test")).thenReturn(namespacedConfigMaps);
        when(namespacedConfigMaps.withName("user-config")).thenReturn(configMapResource);
        when(configMapResource.get()).thenReturn(existing);
        WireMockConfigService service = service(kubernetesClient, config());
        service.wireMockOptions.load(new ByteArrayInputStream("""
                wiremock:
                  default:
                    options: []
                  mocks:
                    - id: baseline-only
                      options:
                        - --disable-banner
                """.getBytes(StandardCharsets.UTF_8)));
        when(service.podManager.listMocks()).thenReturn(List.of(
                new PodManager.MockPodStatus("active-only", "mock-fleet-active-only-1",
                        MockLifecycleStatus.RUNNING, null)));

        WireMockConfigService.ConfigView view = service.view();

        assertEquals(List.of("alpha", "zeta"), view.savedMockIds());
        assertEquals(List.of("active-only", "alpha", "baseline-only", "zeta"), view.mockIds());
    }

    @Test
    void loadUserConfigRequiresTheReleaseOwnedUserConfigMapBeforeStartingWatch() {
        KubernetesClient kubernetesClient = mock(KubernetesClient.class);
        @SuppressWarnings("unchecked")
        MixedOperation<ConfigMap, ConfigMapList, Resource<ConfigMap>> configMaps = mock(MixedOperation.class);
        @SuppressWarnings("unchecked")
        NonNamespaceOperation<ConfigMap, ConfigMapList, Resource<ConfigMap>> namespacedConfigMaps =
                mock(NonNamespaceOperation.class);
        @SuppressWarnings("unchecked")
        Resource<ConfigMap> configMapResource = mock(Resource.class);
        when(kubernetesClient.getNamespace()).thenReturn("test");
        when(kubernetesClient.configMaps()).thenReturn(configMaps);
        when(configMaps.inNamespace("test")).thenReturn(namespacedConfigMaps);
        when(namespacedConfigMaps.withName("user-config")).thenReturn(configMapResource);
        when(configMapResource.get()).thenReturn(null);
        WireMockConfigService service = service(kubernetesClient, config());

        ApiException exception = assertThrows(ApiException.class, service::loadUserConfig);

        assertEquals(503, exception.getResponse().getStatus());
        assertEquals(new ApiError("CONFIG_UNAVAILABLE", "User editable WireMock ConfigMap is missing.",
                        true, false, Map.of("namespace", "test", "name", "user-config")),
                exception.getResponse().getEntity());
        verify(namespacedConfigMaps, never()).resource(any());
        verify(configMapResource, never()).watch(any());
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
        verify(namespacedConfigMaps, org.mockito.Mockito.times(2)).withName("user-config");
        verify(configMapResource).watch(any());
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
    void upsertRequiresTheReleaseOwnedUserConfigMap() {
        KubernetesClient kubernetesClient = mock(KubernetesClient.class);
        @SuppressWarnings("unchecked")
        MixedOperation<ConfigMap, ConfigMapList, Resource<ConfigMap>> configMaps = mock(MixedOperation.class);
        @SuppressWarnings("unchecked")
        NonNamespaceOperation<ConfigMap, ConfigMapList, Resource<ConfigMap>> namespacedConfigMaps =
                mock(NonNamespaceOperation.class);
        @SuppressWarnings("unchecked")
        Resource<ConfigMap> configMapResource = mock(Resource.class);
        when(kubernetesClient.getNamespace()).thenReturn("test");
        when(kubernetesClient.configMaps()).thenReturn(configMaps);
        when(configMaps.inNamespace("test")).thenReturn(namespacedConfigMaps);
        when(namespacedConfigMaps.withName("user-config")).thenReturn(configMapResource);
        when(configMapResource.get()).thenReturn(null);
        WireMockConfigService service = service(kubernetesClient, config());

        ApiException exception = assertThrows(ApiException.class,
                () -> service.upsertMockConfig("demo", new WireMockConfigService.ConfigUpdateRequest(
                        null,
                        List.of("--verbose"),
                        null,
                        "futureOnly")));

        assertEquals(503, exception.getResponse().getStatus());
        assertEquals(new ApiError("CONFIG_UNAVAILABLE", "User editable WireMock ConfigMap is missing.",
                        true, false, Map.of("namespace", "test", "name", "user-config")),
                exception.getResponse().getEntity());
        verify(namespacedConfigMaps, never()).resource(any());
    }

    @Test
    void upsertAddsMissingMockWithoutOverwritingSavedConfigs() {
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
                    - id: alpha
                      options:
                        - --verbose
                """);
        when(kubernetesClient.getNamespace()).thenReturn("test");
        when(kubernetesClient.configMaps()).thenReturn(configMaps);
        when(configMaps.inNamespace("test")).thenReturn(namespacedConfigMaps);
        when(namespacedConfigMaps.withName("user-config")).thenReturn(configMapResource);
        when(configMapResource.get()).thenReturn(existing);
        when(namespacedConfigMaps.resource(any())).thenReturn(updatedConfigMap);
        WireMockConfigService service = service(kubernetesClient, config());
        service.wireMockOptions.load(new ByteArrayInputStream("""
                wiremock:
                  default:
                    options: [--disable-banner]
                    resources:
                      requests: {cpu: "0.5", memory: 512Mi}
                      limits: {cpu: "1", memory: 1Gi}
                  mocks: []
                """.getBytes(StandardCharsets.UTF_8)));

        service.upsertMockConfig("beta", new WireMockConfigService.ConfigUpdateRequest(
                "42",
                List.of(),
                null,
                "futureOnly"));

        ArgumentCaptor<ConfigMap> persistedConfig = ArgumentCaptor.forClass(ConfigMap.class);
        verify(namespacedConfigMaps).resource(persistedConfig.capture());
        String yaml = persistedConfig.getValue().getData().get("wiremock-options.yaml");
        WireMockConfigDocument saved = WireMockConfigDocument.load(yaml);
        assertEquals(List.of("alpha", "beta"), saved.mockConfigs().keySet().stream().toList());
        assertEquals(List.of(), saved.mockConfigs().get("beta").options(),
                "effective baseline options must not be copied into the user override");
        verify(updatedConfigMap).update();
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

    @Test
    void upsertNormalizesPartialResourcesAgainstTheBaselineBeforePersistence() {
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
                  mocks: []
                """);
        when(kubernetesClient.getNamespace()).thenReturn("test");
        when(kubernetesClient.configMaps()).thenReturn(configMaps);
        when(configMaps.inNamespace("test")).thenReturn(namespacedConfigMaps);
        when(namespacedConfigMaps.withName("user-config")).thenReturn(configMapResource);
        when(configMapResource.get()).thenReturn(existing);
        when(namespacedConfigMaps.resource(any())).thenReturn(updatedConfigMap);
        MockFleetConfig config = config();
        WireMockConfigService service = service(kubernetesClient, config);
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

        service.upsertMockConfig("demo", new WireMockConfigService.ConfigUpdateRequest(
                "42", List.of(),
                new WireMockConfigService.ResourceData(Map.of("cpu", "0.75"), Map.of("memory", "1536Mi")),
                "futureOnly"));

        ArgumentCaptor<ConfigMap> persisted = ArgumentCaptor.forClass(ConfigMap.class);
        verify(namespacedConfigMaps).resource(persisted.capture());
        ResourceRequirements saved = WireMockConfigDocument.load(
                        persisted.getValue().getData().get("wiremock-options.yaml"))
                .mockConfigs().get("demo").resources();
        assertEquals(Map.of("cpu", new io.fabric8.kubernetes.api.model.Quantity("0.75"),
                        "memory", new io.fabric8.kubernetes.api.model.Quantity("512Mi")), saved.getRequests());
        assertEquals(Map.of("cpu", new io.fabric8.kubernetes.api.model.Quantity("1"),
                        "memory", new io.fabric8.kubernetes.api.model.Quantity("1536Mi")), saved.getLimits());
    }

    @Test
    void upsertRejectsAnInvalidInheritedResourceSetBeforePersistence() {
        KubernetesClient kubernetesClient = mock(KubernetesClient.class);
        @SuppressWarnings("unchecked")
        MixedOperation<ConfigMap, ConfigMapList, Resource<ConfigMap>> configMaps = mock(MixedOperation.class);
        @SuppressWarnings("unchecked")
        NonNamespaceOperation<ConfigMap, ConfigMapList, Resource<ConfigMap>> namespacedConfigMaps =
                mock(NonNamespaceOperation.class);
        @SuppressWarnings("unchecked")
        Resource<ConfigMap> configMapResource = mock(Resource.class);
        when(kubernetesClient.getNamespace()).thenReturn("test");
        when(kubernetesClient.configMaps()).thenReturn(configMaps);
        when(configMaps.inNamespace("test")).thenReturn(namespacedConfigMaps);
        when(namespacedConfigMaps.withName("user-config")).thenReturn(configMapResource);
        when(configMapResource.get()).thenReturn(configMap("user-config", "42", """
                wiremock:
                  default:
                    options: []
                  mocks: []
                """));
        MockFleetConfig config = config();
        WireMockConfigService service = service(kubernetesClient, config);
        service.wireMockOptions.load(new ByteArrayInputStream("""
                wiremock:
                  default:
                    options: []
                    resources:
                      requests:
                        cpu: "0.5"
                      limits:
                        cpu: "1"
                  mocks: []
                """.getBytes(StandardCharsets.UTF_8)));

        jakarta.ws.rs.WebApplicationException exception = assertThrows(jakarta.ws.rs.WebApplicationException.class,
                () -> service.upsertMockConfig("demo", new WireMockConfigService.ConfigUpdateRequest(
                        "42", List.of("--verbose"), null, "futureOnly")));

        assertEquals("WireMock resources require requests and limits for cpu and memory.", exception.getMessage());
        verify(namespacedConfigMaps, never()).resource(any());
    }

    @Test
    void deleteRejectsInvalidApplyModeBeforeReadingOrMutatingConfig() {
        KubernetesClient kubernetesClient = mock(KubernetesClient.class);
        WireMockConfigService service = service(kubernetesClient, config());
        service.wireMockOptions.setUserConfig(WireMockConfigDocument.of(
                List.of(), null, Map.of("demo", new WireMockPodConfig(List.of("--verbose"), null))));

        jakarta.ws.rs.WebApplicationException exception = assertThrows(jakarta.ws.rs.WebApplicationException.class,
                () -> service.deleteMockConfig("demo", new WireMockConfigService.ConfigUpdateRequest(
                        "42", null, null, "now")));

        assertEquals(400, exception.getResponse().getStatus());
        assertEquals(new ApiError("INVALID_APPLY_MODE", "Unsupported config apply mode: now",
                        false, false, Map.of("applyMode", "now")),
                exception.getResponse().getEntity());
        assertEquals(List.of("--verbose"), service.wireMockOptions.optionsFor("demo"));
        verify(kubernetesClient, never()).configMaps();
    }

    @Test
    void configConflictIncludesExpectedAndCurrentVersions() {
        KubernetesClient kubernetesClient = mock(KubernetesClient.class);
        @SuppressWarnings("unchecked")
        MixedOperation<ConfigMap, ConfigMapList, Resource<ConfigMap>> configMaps = mock(MixedOperation.class);
        @SuppressWarnings("unchecked")
        NonNamespaceOperation<ConfigMap, ConfigMapList, Resource<ConfigMap>> namespacedConfigMaps =
                mock(NonNamespaceOperation.class);
        @SuppressWarnings("unchecked")
        Resource<ConfigMap> configMapResource = mock(Resource.class);
        when(kubernetesClient.getNamespace()).thenReturn("test");
        when(kubernetesClient.configMaps()).thenReturn(configMaps);
        when(configMaps.inNamespace("test")).thenReturn(namespacedConfigMaps);
        when(namespacedConfigMaps.withName("user-config")).thenReturn(configMapResource);
        when(configMapResource.get()).thenReturn(configMap("user-config", "42", """
                wiremock:
                  default:
                    options: []
                  mocks: []
                """));
        WireMockConfigService service = service(kubernetesClient, config());

        jakarta.ws.rs.WebApplicationException exception = assertThrows(jakarta.ws.rs.WebApplicationException.class,
                () -> service.upsertMockConfig("demo", new WireMockConfigService.ConfigUpdateRequest(
                        "41", List.of(), null, "futureOnly")));

        assertEquals(409, exception.getResponse().getStatus());
        assertEquals(new ApiError("CONFIG_CONFLICT", "WireMock config was modified by another writer.",
                        true, false, Map.of("expectedVersion", "41", "currentVersion", "42")),
                exception.getResponse().getEntity());
    }

    @Test
    void updateRaceConflictRereadsCurrentVersionAndReturnsConfigConflict() {
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
        ConfigMap expected = configMap("user-config", "42", """
                wiremock:
                  default:
                    options: []
                  mocks: []
                """);
        ConfigMap winner = configMap("user-config", "43", """
                wiremock:
                  default:
                    options: []
                  mocks:
                    - id: other
                      options: []
                """);
        when(kubernetesClient.getNamespace()).thenReturn("test");
        when(kubernetesClient.configMaps()).thenReturn(configMaps);
        when(configMaps.inNamespace("test")).thenReturn(namespacedConfigMaps);
        when(namespacedConfigMaps.withName("user-config")).thenReturn(configMapResource);
        when(configMapResource.get()).thenReturn(expected, winner);
        when(namespacedConfigMaps.resource(any())).thenReturn(updatedConfigMap);
        when(updatedConfigMap.update()).thenThrow(new KubernetesClientException("conflict", 409, null));
        WireMockConfigService service = service(kubernetesClient, config());

        jakarta.ws.rs.WebApplicationException exception = assertThrows(jakarta.ws.rs.WebApplicationException.class,
                () -> service.upsertMockConfig("demo", new WireMockConfigService.ConfigUpdateRequest(
                        "42", List.of(), null, "futureOnly")));

        assertEquals(409, exception.getResponse().getStatus());
        assertEquals(new ApiError("CONFIG_CONFLICT", "WireMock config was modified by another writer.",
                        true, false, Map.of("expectedVersion", "42", "currentVersion", "43")),
                exception.getResponse().getEntity());
        verify(configMapResource, org.mockito.Mockito.times(2)).get();
    }

    @Test
    void updateDoesNotTranslateUnrelatedKubernetesFailureAsConflict() {
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
                  mocks: []
                """);
        KubernetesClientException forbidden = new KubernetesClientException("forbidden", 403, null);
        when(kubernetesClient.getNamespace()).thenReturn("test");
        when(kubernetesClient.configMaps()).thenReturn(configMaps);
        when(configMaps.inNamespace("test")).thenReturn(namespacedConfigMaps);
        when(namespacedConfigMaps.withName("user-config")).thenReturn(configMapResource);
        when(configMapResource.get()).thenReturn(existing);
        when(namespacedConfigMaps.resource(any())).thenReturn(updatedConfigMap);
        when(updatedConfigMap.update()).thenThrow(forbidden);
        WireMockConfigService service = service(kubernetesClient, config());

        KubernetesClientException thrown = assertThrows(KubernetesClientException.class,
                () -> service.upsertMockConfig("demo", new WireMockConfigService.ConfigUpdateRequest(
                        "42", List.of(), null, "futureOnly")));

        assertEquals(forbidden, thrown);
        verify(configMapResource).get();
    }

    private WireMockConfigService service(KubernetesClient kubernetesClient, MockFleetConfig config) {
        WireMockConfigService service = new WireMockConfigService();
        service.config = config;
        service.kubernetesClient = kubernetesClient;
        service.wireMockOptions = new WireMockOptions();
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
        service.resourcePolicy = new WireMockResourcePolicy(config);
        service.podManager = mock(PodManager.class);
        when(service.podManager.listMocks()).thenReturn(List.of());
        when(service.podManager.status(any())).thenAnswer(invocation -> new PodManager.MockPodStatus(
                invocation.getArgument(0), null, MockLifecycleStatus.STOPPED, null));
        return service;
    }

    private WireMockConfigService serviceWithUserConfig(String yaml) {
        KubernetesClient kubernetesClient = mock(KubernetesClient.class);
        @SuppressWarnings("unchecked")
        MixedOperation<ConfigMap, ConfigMapList, Resource<ConfigMap>> configMaps = mock(MixedOperation.class);
        @SuppressWarnings("unchecked")
        NonNamespaceOperation<ConfigMap, ConfigMapList, Resource<ConfigMap>> namespaced = mock(NonNamespaceOperation.class);
        @SuppressWarnings("unchecked")
        Resource<ConfigMap> resource = mock(Resource.class);
        when(kubernetesClient.configMaps()).thenReturn(configMaps);
        when(configMaps.inNamespace("mock-fleet")).thenReturn(namespaced);
        when(namespaced.withName("user-config")).thenReturn(resource);
        when(resource.get()).thenReturn(configMap("user-config", "42", yaml));
        return service(kubernetesClient, config());
    }

    private void installCatalog(WireMockConfigService service, WireMockVersionCatalog catalog) {
        WireMockVersionCatalogService catalogService = mock(WireMockVersionCatalogService.class);
        when(catalogService.catalog()).thenReturn(catalog);
        service.wireMockOptions.catalogService = catalogService;
    }

    private WireMockVersionCatalog versionCatalog() {
        WireMockVersion current = WireMockVersion.parse("3.13.2");
        WireMockVersion retained = WireMockVersion.parse("3.12.1");
        Map<WireMockVersion, WireMockVersionCatalog.VersionEntry> versions = new java.util.LinkedHashMap<>();
        versions.put(current, new WireMockVersionCatalog.VersionEntry(
                current, "wiremock/wiremock:3.13.2-7", true));
        versions.put(retained, new WireMockVersionCatalog.VersionEntry(
                retained, "wiremock/wiremock:3.12.1-2", false));
        return new WireMockVersionCatalog(current, versions, "catalog-17");
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
        when(config.wiremockImage()).thenReturn("wiremock/wiremock:3.13.2-2");
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
