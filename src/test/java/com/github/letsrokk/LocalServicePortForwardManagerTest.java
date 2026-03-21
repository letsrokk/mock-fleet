package com.github.letsrokk;

import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.LocalPortForward;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.ServiceResource;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LocalServicePortForwardManagerTest {

    @Test
    void createsAndReusesHealthyServicePortForward() throws Exception {
        KubernetesClient kubernetesClient = mock(KubernetesClient.class);
        @SuppressWarnings("unchecked")
        MixedOperation<Service, ServiceList, ServiceResource<Service>> serviceOperations = mock(MixedOperation.class);
        @SuppressWarnings("unchecked")
        NonNamespaceOperation<Service, ServiceList, ServiceResource<Service>> namespacedServices = mock(NonNamespaceOperation.class);
        @SuppressWarnings("unchecked")
        ServiceResource<Service> serviceResource = mock(ServiceResource.class);
        MockFleetConfig config = localDebugConfig(true, "127.0.0.1", 18080, 18081, 8080);
        ServiceFactory serviceFactory = new ServiceFactory();
        LocalPortForward portForward = mock(LocalPortForward.class);
        InetAddress loopback = InetAddress.getByName("127.0.0.1");

        LocalServicePortForwardManager manager = new LocalServicePortForwardManager() {
            @Override
            boolean canConnect(InetAddress address, int port) {
                return true;
            }

            @Override
            boolean isPortAvailable(InetAddress bindAddress, int port) {
                return true;
            }
        };
        manager.kubernetesClient = kubernetesClient;
        manager.config = config;
        manager.serviceFactory = serviceFactory;

        when(kubernetesClient.services()).thenReturn(serviceOperations);
        when(serviceOperations.inNamespace("test")).thenReturn(namespacedServices);
        when(namespacedServices.withName("mock-fleet-demo")).thenReturn(serviceResource);
        when(serviceResource.portForward(8080, loopback, 18080)).thenReturn(portForward);
        when(portForward.isAlive()).thenReturn(true);
        when(portForward.errorOccurred()).thenReturn(false);
        when(portForward.getLocalAddress()).thenReturn(loopback);
        when(portForward.getLocalPort()).thenReturn(18080);

        String firstBaseUrl = manager.getOrCreateForwardBaseUrl("demo", "test");
        String secondBaseUrl = manager.getOrCreateForwardBaseUrl("demo", "test");

        assertEquals("http://127.0.0.1:18080", firstBaseUrl);
        assertEquals(firstBaseUrl, secondBaseUrl);
        verify(serviceResource, times(1)).portForward(8080, loopback, 18080);
    }

    @Test
    void recreatesDeadForwardAndClosesOldHandle() throws Exception {
        KubernetesClient kubernetesClient = mock(KubernetesClient.class);
        @SuppressWarnings("unchecked")
        MixedOperation<Service, ServiceList, ServiceResource<Service>> serviceOperations = mock(MixedOperation.class);
        @SuppressWarnings("unchecked")
        NonNamespaceOperation<Service, ServiceList, ServiceResource<Service>> namespacedServices = mock(NonNamespaceOperation.class);
        @SuppressWarnings("unchecked")
        ServiceResource<Service> serviceResource = mock(ServiceResource.class);
        MockFleetConfig config = localDebugConfig(true, "127.0.0.1", 18080, 18081, 8080);
        ServiceFactory serviceFactory = new ServiceFactory();
        LocalPortForward deadForward = mock(LocalPortForward.class);
        LocalPortForward newForward = mock(LocalPortForward.class);
        InetAddress loopback = InetAddress.getByName("127.0.0.1");

        LocalServicePortForwardManager manager = new LocalServicePortForwardManager() {
            @Override
            boolean canConnect(InetAddress address, int port) {
                return true;
            }

            @Override
            boolean isPortAvailable(InetAddress bindAddress, int port) {
                return true;
            }
        };
        manager.kubernetesClient = kubernetesClient;
        manager.config = config;
        manager.serviceFactory = serviceFactory;

        when(kubernetesClient.services()).thenReturn(serviceOperations);
        when(serviceOperations.inNamespace("test")).thenReturn(namespacedServices);
        when(namespacedServices.withName("mock-fleet-demo")).thenReturn(serviceResource);
        when(serviceResource.portForward(8080, loopback, 18080)).thenReturn(deadForward);
        when(serviceResource.portForward(8080, loopback, 18081)).thenReturn(newForward);
        when(deadForward.isAlive()).thenReturn(false);
        when(newForward.isAlive()).thenReturn(true);
        when(newForward.errorOccurred()).thenReturn(false);
        when(newForward.getLocalAddress()).thenReturn(loopback);
        when(newForward.getLocalPort()).thenReturn(18081);

        manager.getOrCreateForwardBaseUrl("demo", "test");
        String recreatedBaseUrl = manager.getOrCreateForwardBaseUrl("demo", "test");

        assertEquals("http://127.0.0.1:18081", recreatedBaseUrl);
        verify(deadForward).close();
        verify(serviceResource, times(1)).portForward(8080, loopback, 18080);
        verify(serviceResource, times(1)).portForward(8080, loopback, 18081);
    }

    @Test
    void failsWhenNoConfiguredLocalPortIsAvailable() {
        KubernetesClient kubernetesClient = mock(KubernetesClient.class);
        @SuppressWarnings("unchecked")
        MixedOperation<Service, ServiceList, ServiceResource<Service>> serviceOperations = mock(MixedOperation.class);
        @SuppressWarnings("unchecked")
        NonNamespaceOperation<Service, ServiceList, ServiceResource<Service>> namespacedServices = mock(NonNamespaceOperation.class);
        @SuppressWarnings("unchecked")
        ServiceResource<Service> serviceResource = mock(ServiceResource.class);
        MockFleetConfig config = localDebugConfig(true, "127.0.0.1", 18080, 18081, 8080);

        LocalServicePortForwardManager manager = new LocalServicePortForwardManager() {
            @Override
            boolean isPortAvailable(InetAddress bindAddress, int port) {
                return false;
            }
        };
        manager.config = config;
        manager.serviceFactory = new ServiceFactory();
        manager.kubernetesClient = kubernetesClient;

        when(kubernetesClient.services()).thenReturn(serviceOperations);
        when(serviceOperations.inNamespace("test")).thenReturn(namespacedServices);
        when(namespacedServices.withName("mock-fleet-demo")).thenReturn(serviceResource);

        assertThrows(IllegalStateException.class, () -> manager.createForward("test", "mock-fleet-demo", manager.resolveBindAddress()));
    }

    @Test
    void closeForMockRemovesCachedForward() throws Exception {
        KubernetesClient kubernetesClient = mock(KubernetesClient.class);
        @SuppressWarnings("unchecked")
        MixedOperation<Service, ServiceList, ServiceResource<Service>> serviceOperations = mock(MixedOperation.class);
        @SuppressWarnings("unchecked")
        NonNamespaceOperation<Service, ServiceList, ServiceResource<Service>> namespacedServices = mock(NonNamespaceOperation.class);
        @SuppressWarnings("unchecked")
        ServiceResource<Service> serviceResource = mock(ServiceResource.class);
        MockFleetConfig config = localDebugConfig(true, "127.0.0.1", 18080, 18081, 8080);
        ServiceFactory serviceFactory = new ServiceFactory();
        LocalPortForward portForward = mock(LocalPortForward.class);
        InetAddress loopback = InetAddress.getByName("127.0.0.1");

        LocalServicePortForwardManager manager = new LocalServicePortForwardManager() {
            @Override
            boolean canConnect(InetAddress address, int port) {
                return true;
            }

            @Override
            boolean isPortAvailable(InetAddress bindAddress, int port) {
                return true;
            }
        };
        manager.kubernetesClient = kubernetesClient;
        manager.config = config;
        manager.serviceFactory = serviceFactory;

        when(kubernetesClient.services()).thenReturn(serviceOperations);
        when(serviceOperations.inNamespace("test")).thenReturn(namespacedServices);
        when(namespacedServices.withName("mock-fleet-demo")).thenReturn(serviceResource);
        when(serviceResource.portForward(8080, loopback, 18080)).thenReturn(portForward);
        when(portForward.isAlive()).thenReturn(true);
        when(portForward.errorOccurred()).thenReturn(false);
        when(portForward.getLocalAddress()).thenReturn(loopback);
        when(portForward.getLocalPort()).thenReturn(18080);

        manager.getOrCreateForwardBaseUrl("demo", "test");
        manager.closeForMock("demo");
        manager.closeForMock("demo");

        verify(portForward, times(1)).close();
        verify(serviceResource, times(1)).portForward(8080, loopback, 18080);
    }

    private MockFleetConfig localDebugConfig(boolean enabled, String bindAddress, int startPort, int endPort, int servicePort) {
        MockFleetConfig.LocalDebugConfig localDebugConfig = mock(MockFleetConfig.LocalDebugConfig.class);
        when(localDebugConfig.enabled()).thenReturn(enabled);
        when(localDebugConfig.bindAddress()).thenReturn(bindAddress);
        when(localDebugConfig.startPort()).thenReturn(startPort);
        when(localDebugConfig.endPort()).thenReturn(endPort);
        when(localDebugConfig.servicePort()).thenReturn(servicePort);

        MockFleetConfig config = mock(MockFleetConfig.class);
        when(config.localDebug()).thenReturn(localDebugConfig);
        return config;
    }
}
