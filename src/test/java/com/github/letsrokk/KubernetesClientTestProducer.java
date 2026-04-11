package com.github.letsrokk;

import io.fabric8.kubernetes.client.KubernetesClient;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.Produces;

import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ApplicationScoped
@Alternative
@Priority(1)
public class KubernetesClientTestProducer {

    @Produces
    @ApplicationScoped
    KubernetesClient kubernetesClient() {
        KubernetesClient kubernetesClient = mock(KubernetesClient.class, RETURNS_DEEP_STUBS);
        when(kubernetesClient.getNamespace()).thenReturn("test");
        return kubernetesClient;
    }
}
