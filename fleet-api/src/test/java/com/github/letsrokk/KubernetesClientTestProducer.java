package com.github.letsrokk;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.ConfigMapList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.Produces;

import java.util.Map;

import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ApplicationScoped
@Alternative
@Priority(1)
public class KubernetesClientTestProducer {

    @Produces
    @ApplicationScoped
    @SuppressWarnings({"rawtypes", "unchecked"})
    KubernetesClient kubernetesClient() {
        KubernetesClient kubernetesClient = mock(KubernetesClient.class, RETURNS_DEEP_STUBS);
        MixedOperation<ConfigMap, ConfigMapList, Resource<ConfigMap>> configMaps = mock(MixedOperation.class);
        NonNamespaceOperation<ConfigMap, ConfigMapList, Resource<ConfigMap>> namespaced =
                mock(NonNamespaceOperation.class);
        Resource<ConfigMap> catalogResource = mock(Resource.class);
        when(kubernetesClient.getNamespace()).thenReturn("test");
        when(kubernetesClient.configMaps()).thenReturn(configMaps);
        when(configMaps.inNamespace("mock-fleet")).thenReturn(namespaced);
        when(namespaced.withName("version-catalog")).thenReturn(catalogResource);
        when(catalogResource.get()).thenReturn(new ConfigMapBuilder()
                        .withNewMetadata()
                            .withName("version-catalog")
                            .withResourceVersion("test-catalog")
                        .endMetadata()
                        .withData(Map.of(
                                "defaultVersion", "3.13.2",
                                "selectable.3.13.2", "wiremock/wiremock:3.13.2-2"))
                        .build());
        return kubernetesClient;
    }
}
