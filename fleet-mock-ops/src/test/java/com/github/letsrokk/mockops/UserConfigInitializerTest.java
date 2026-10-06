package com.github.letsrokk.mockops;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.ConfigMapList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class UserConfigInitializerTest {
    private final KubernetesClient client = mock(KubernetesClient.class);
    @SuppressWarnings("unchecked")
    private final Resource<ConfigMap> writer = mock(Resource.class);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void configureClient() {
        MixedOperation<ConfigMap, ConfigMapList, Resource<ConfigMap>> configMaps = mock(MixedOperation.class);
        NonNamespaceOperation<ConfigMap, ConfigMapList, Resource<ConfigMap>> namespace = mock(NonNamespaceOperation.class);
        Resource<ConfigMap> reader = mock(Resource.class);
        when(client.configMaps()).thenReturn(configMaps);
        when(configMaps.inNamespace("test")).thenReturn(namespace);
        when(namespace.withName("user")).thenReturn(reader);
        when(namespace.withName("catalog")).thenReturn(reader);
    }

    @Test
    void initializesMissingConfigWithoutRegistryAccess() {
        when(client.configMaps().inNamespace("test").withName("user").get()).thenReturn(null);
        when(client.configMaps().inNamespace("test").resource(any(ConfigMap.class))).thenReturn(writer);
        MockOpsCommand command = new MockOpsCommand();
        command.kubernetes = client;
        command.config = mock(MockOpsConfig.class);
        when(command.config.namespace()).thenReturn("test");
        when(command.config.userConfigMapName()).thenReturn("user");
        when(command.config.configKey()).thenReturn("wiremock-options.yaml");

        assertEquals(0, command.run("initialize-user-config"));

        ArgumentCaptor<ConfigMap> created = ArgumentCaptor.forClass(ConfigMap.class);
        verify(client.configMaps().inNamespace("test")).resource(created.capture());
        verify(writer).create();
        assertEquals("wiremock:\n  default:\n    options: []\n  mocks: []\n",
                created.getValue().getData().get("wiremock-options.yaml"));
        assertEquals("Prune=false,Delete=false", created.getValue().getMetadata().getAnnotations()
                .get("argocd.argoproj.io/sync-options"));
        assertEquals("IgnoreExtraneous", created.getValue().getMetadata().getAnnotations()
                .get("argocd.argoproj.io/compare-options"));
        verify(command.config, never()).registryUrl();
    }

    @Test
    void protectsExistingOverridesWithoutChangingDataAndThenLeavesThemUntouched() {
        ConfigMap saved = savedConfig();
        when(client.configMaps().inNamespace("test").withName("user").get()).thenReturn(saved);
        when(client.configMaps().inNamespace("test").resource(any(ConfigMap.class))).thenReturn(writer);

        UserConfigInitializer.initialize(client, "test", "user", "wiremock-options.yaml");

        ArgumentCaptor<ConfigMap> updated = ArgumentCaptor.forClass(ConfigMap.class);
        verify(client.configMaps().inNamespace("test")).resource(updated.capture());
        verify(writer).update();
        assertEquals(saved.getData(), updated.getValue().getData());
        assertEquals("42", updated.getValue().getMetadata().getResourceVersion());
        assertEquals("IgnoreExtraneous", updated.getValue().getMetadata().getAnnotations()
                .get("argocd.argoproj.io/compare-options"));
        assertEquals("Validate=false,Prune=false,Delete=false", updated.getValue().getMetadata().getAnnotations()
                .get("argocd.argoproj.io/sync-options"));
        when(client.configMaps().inNamespace("test").withName("user").get()).thenReturn(updated.getValue());
        clearInvocations(writer);
        UserConfigInitializer.initialize(client, "test", "user", "wiremock-options.yaml");
        verifyNoInteractions(writer);
    }

    @Test
    void concurrentCreationPreservesTheWinningConfigAndUpdateConflictsFail() {
        when(client.configMaps().inNamespace("test").withName("user").get())
                .thenReturn(null, savedConfig());
        when(client.configMaps().inNamespace("test").resource(any(ConfigMap.class))).thenReturn(writer);
        when(writer.create()).thenThrow(new KubernetesClientException("Already exists", 409, null));
        when(writer.update()).thenThrow(new KubernetesClientException("Concurrent API save", 409, null));

        assertThrows(KubernetesClientException.class,
                () -> UserConfigInitializer.initialize(client, "test", "user", "wiremock-options.yaml"));
        ArgumentCaptor<ConfigMap> writes = ArgumentCaptor.forClass(ConfigMap.class);
        verify(client.configMaps().inNamespace("test"), times(2)).resource(writes.capture());
        assertEquals(savedConfig().getData(), writes.getAllValues().getLast().getData());
        assertEquals("42", writes.getAllValues().getLast().getMetadata().getResourceVersion());
    }

    @Test
    void initializesTheDiscoveryCatalogFromTheHookWithoutRegistryAccess() throws Exception {
        when(client.configMaps().inNamespace("test").resource(any(ConfigMap.class))).thenReturn(writer);
        MockOpsCommand command = new MockOpsCommand();
        command.kubernetes = client;
        command.json = new ObjectMapper();
        command.config = mock(MockOpsConfig.class);
        when(command.config.namespace()).thenReturn("test");
        when(command.config.userConfigMapName()).thenReturn("user");
        when(command.config.configKey()).thenReturn("wiremock-options.yaml");
        when(command.config.catalogConfigMapName()).thenReturn("catalog");
        when(command.config.catalogSeed()).thenReturn(java.util.Optional.of(seed("[3.6,4.0)")));

        assertEquals(0, command.run("initialize-user-config"));

        ArgumentCaptor<ConfigMap> created = ArgumentCaptor.forClass(ConfigMap.class);
        verify(client.configMaps().inNamespace("test"), times(2)).resource(created.capture());
        verify(writer, times(2)).create();
        ConfigMap catalog = created.getAllValues().getLast();
        assertEquals("catalog", catalog.getMetadata().getName());
        assertEquals(Map.of("defaultVersion", "3.6.0", "selectable.3.6.0", "wiremock/wiremock:3.6.0"), catalog.getData());
        assertEquals("Prune=false,Delete=false", catalog.getMetadata().getAnnotations().get("argocd.argoproj.io/sync-options"));
        verify(command.config, never()).registryUrl();
    }

    @Test
    void preservesRuntimeDefaultAndRetirementWhileRepairingIdenticalDuplicates() throws Exception {
        ConfigMap current = savedCatalog(Map.of(
                "defaultVersion", "3.13.2", "selectable.3.13.2", "wiremock/wiremock:3.13.2-2",
                "retained.3.6.0", "wiremock/wiremock:3.6.0",
                "selectable.3.12.1", "wiremock/wiremock:3.12.1", "retained.3.12.1", "wiremock/wiremock:3.12.1"));
        when(client.configMaps().inNamespace("test").withName("catalog").get()).thenReturn(current);
        when(client.configMaps().inNamespace("test").resource(any(ConfigMap.class))).thenReturn(writer);

        VersionCatalogInitializer.initialize(client, new ObjectMapper(), "test", "catalog", seed("[3.6,4.0)"));

        ArgumentCaptor<ConfigMap> updated = ArgumentCaptor.forClass(ConfigMap.class);
        verify(client.configMaps().inNamespace("test")).resource(updated.capture());
        verify(writer).update();
        ConfigMap next = updated.getValue();
        assertEquals(Map.of("defaultVersion", "3.13.2", "selectable.3.13.2", "wiremock/wiremock:3.13.2-2",
                "retained.3.6.0", "wiremock/wiremock:3.6.0", "selectable.3.12.1", "wiremock/wiremock:3.12.1"), next.getData());
        assertEquals("42", next.getMetadata().getResourceVersion());
        assertEquals("IgnoreExtraneous", next.getMetadata().getAnnotations().get("argocd.argoproj.io/compare-options"));
        assertEquals("keep", next.getMetadata().getAnnotations().get("helm.sh/resource-policy"));
        when(client.configMaps().inNamespace("test").withName("catalog").get()).thenReturn(next);
        clearInvocations(writer);
        VersionCatalogInitializer.initialize(client, new ObjectMapper(), "test", "catalog", seed("[3.6,4.0)"));
        verifyNoInteractions(writer);
    }

    @Test
    void policyUpgradeRetainsExcludedImagesAndSelectsTheConfiguredFallback() throws Exception {
        ConfigMap current = savedCatalog(Map.of(
                "defaultVersion", "3.14.0", "selectable.3.14.0", "wiremock/wiremock:3.14.0-2",
                "selectable.3.13.2", "wiremock/wiremock:3.13.2", "retained.3.6.0", "wiremock/wiremock:3.6.0"));
        when(client.configMaps().inNamespace("test").withName("catalog").get()).thenReturn(current);
        when(client.configMaps().inNamespace("test").resource(any(ConfigMap.class))).thenReturn(writer);

        VersionCatalogInitializer.initialize(client, new ObjectMapper(), "test", "catalog", seed("[3.6,3.14)"));

        ArgumentCaptor<ConfigMap> updated = ArgumentCaptor.forClass(ConfigMap.class);
        verify(client.configMaps().inNamespace("test")).resource(updated.capture());
        assertEquals(Map.of("defaultVersion", "3.6.0", "selectable.3.6.0", "wiremock/wiremock:3.6.0",
                "retained.3.14.0", "wiremock/wiremock:3.14.0-2", "selectable.3.13.2", "wiremock/wiremock:3.13.2"),
                updated.getValue().getData());
        assertEquals(policy("[3.6,3.14)"), updated.getValue().getMetadata().getAnnotations().get(CatalogReconciler.IMAGE_POLICY));
    }

    @Test
    void conflictingCatalogImagesFailWithoutWriting() throws Exception {
        when(client.configMaps().inNamespace("test").withName("catalog").get()).thenReturn(savedCatalog(Map.of(
                "defaultVersion", "3.13.2", "selectable.3.13.2", "wiremock/wiremock:3.13.2-2",
                "retained.3.13.2", "wiremock/wiremock:3.13.2-1")));
        assertThrows(IllegalStateException.class, () -> VersionCatalogInitializer.initialize(
                client, new ObjectMapper(), "test", "catalog", seed("[3.6,4.0)")));
        verifyNoInteractions(writer);
    }

    @Test
    void concurrentCatalogCreationPreservesTheWinnerAndUpdateConflictsFail() throws Exception {
        when(client.configMaps().inNamespace("test").withName("catalog").get()).thenReturn(null, savedCatalog(Map.of(
                "defaultVersion", "3.13.2", "selectable.3.13.2", "wiremock/wiremock:3.13.2-2")));
        when(client.configMaps().inNamespace("test").resource(any(ConfigMap.class))).thenReturn(writer);
        when(writer.create()).thenThrow(new KubernetesClientException("Already exists", 409, null));
        when(writer.update()).thenThrow(new KubernetesClientException("Concurrent discovery", 409, null));
        assertThrows(KubernetesClientException.class, () -> VersionCatalogInitializer.initialize(
                client, new ObjectMapper(), "test", "catalog", seed("[3.6,4.0)")));
        ArgumentCaptor<ConfigMap> writes = ArgumentCaptor.forClass(ConfigMap.class);
        verify(client.configMaps().inNamespace("test"), times(2)).resource(writes.capture());
        assertEquals("3.13.2", writes.getAllValues().getLast().getData().get("defaultVersion"));
        assertEquals("42", writes.getAllValues().getLast().getMetadata().getResourceVersion());
    }

    private static ConfigMap savedCatalog(Map<String, String> data) {
        return new ConfigMapBuilder().withNewMetadata().withName("catalog").withNamespace("test")
                .withResourceVersion("42").endMetadata().withData(data).build();
    }

    private static String seed(String range) throws Exception {
        return new ObjectMapper().writeValueAsString(new ConfigMapBuilder(savedCatalog(Map.of(
                "defaultVersion", "3.6.0", "selectable.3.6.0", "wiremock/wiremock:3.6.0")))
                .editMetadata().addToAnnotations(CatalogReconciler.IMAGE_POLICY, policy(range)).endMetadata().build());
    }

    private static String policy(String range) {
        return "{\"defaultImage\":\"wiremock/wiremock:3.6.0\",\"allowedImages\":[],\"allowedVersionRange\":\"" + range + "\"}";
    }

    private ConfigMap savedConfig() {
        return new ConfigMapBuilder().withNewMetadata().withName("user").withNamespace("test")
                .withResourceVersion("42").addToAnnotations("argocd.argoproj.io/sync-options", "Validate=false")
                .endMetadata().withData(Map.of("wiremock-options.yaml",
                        "wiremock:\n  mocks:\n    - id: saved\n      options: [--verbose]\n", "extra", "preserve"))
                .build();
    }
}
