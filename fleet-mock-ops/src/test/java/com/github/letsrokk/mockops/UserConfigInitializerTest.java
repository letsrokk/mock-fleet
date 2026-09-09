package com.github.letsrokk.mockops;

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

    private ConfigMap savedConfig() {
        return new ConfigMapBuilder().withNewMetadata().withName("user").withNamespace("test")
                .withResourceVersion("42").addToAnnotations("argocd.argoproj.io/sync-options", "Validate=false")
                .endMetadata().withData(Map.of("wiremock-options.yaml",
                        "wiremock:\n  mocks:\n    - id: saved\n      options: [--verbose]\n", "extra", "preserve"))
                .build();
    }
}
