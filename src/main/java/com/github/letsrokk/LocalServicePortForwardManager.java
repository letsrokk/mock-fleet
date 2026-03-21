package com.github.letsrokk;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.LocalPortForward;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.ServiceResource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class LocalServicePortForwardManager {

    private static final Logger LOG = Logger.getLogger(LocalServicePortForwardManager.class);

    @Inject
    KubernetesClient kubernetesClient;

    @Inject
    MockFleetConfig config;

    @Inject
    ServiceFactory serviceFactory;

    private final Map<String, ForwardEntry> forwards = new ConcurrentHashMap<>();

    public String getOrCreateForwardBaseUrl(String mockId, String namespace) {
        String serviceName = serviceFactory.serviceName(mockId);
        ForwardEntry current = forwards.get(serviceName);
        if (isUsable(current)) {
            return current.baseUrl();
        }

        close(serviceName);

        InetAddress bindAddress = resolveBindAddress();
        LocalPortForward portForward = createForward(namespace, serviceName, bindAddress);
        ForwardEntry entry = new ForwardEntry(serviceName, portForward);
        forwards.put(serviceName, entry);
        LOG.infof("Opened local port-forward for service '%s' on %s:%d.", serviceName, bindAddress.getHostAddress(), portForward.getLocalPort());
        return entry.baseUrl();
    }

    public void closeForMock(String mockId) {
        close(serviceFactory.serviceName(mockId));
    }

    public void close(String serviceName) {
        ForwardEntry removed = forwards.remove(serviceName);
        if (removed != null) {
            try {
                removed.portForward().close();
            } catch (IOException e) {
                LOG.debugf(e, "Failed to close port-forward for service '%s'.", serviceName);
            }
        }
    }

    LocalPortForward createForward(String namespace, String serviceName, InetAddress bindAddress) {
        MixedOperation<io.fabric8.kubernetes.api.model.Service, io.fabric8.kubernetes.api.model.ServiceList, ServiceResource<io.fabric8.kubernetes.api.model.Service>> services =
                kubernetesClient.services();
        NonNamespaceOperation<io.fabric8.kubernetes.api.model.Service, io.fabric8.kubernetes.api.model.ServiceList, ServiceResource<io.fabric8.kubernetes.api.model.Service>> namespacedServices =
                services.inNamespace(namespace);
        ServiceResource<io.fabric8.kubernetes.api.model.Service> serviceResource = namespacedServices.withName(serviceName);

        for (int localPort = config.localDebug().startPort(); localPort <= config.localDebug().endPort(); localPort++) {
            if (!isPortAvailable(bindAddress, localPort)) {
                continue;
            }

            try {
                LocalPortForward portForward = serviceResource.portForward(config.localDebug().servicePort(), bindAddress, localPort);
                if (portForward.isAlive() && !portForward.errorOccurred()) {
                    return portForward;
                }
                portForward.close();
            } catch (IOException | RuntimeException e) {
                LOG.debugf(e, "Unable to port-forward service '%s' to local port %d.", serviceName, localPort);
            }
        }

        throw new IllegalStateException(String.format(
                "Unable to create a local port-forward for service '%s' in namespace '%s' using ports %d-%d.",
                serviceName,
                namespace,
                config.localDebug().startPort(),
                config.localDebug().endPort()));
    }

    boolean isUsable(ForwardEntry entry) {
        if (entry == null) {
            return false;
        }
        LocalPortForward portForward = entry.portForward();
        return portForward.isAlive()
                && !portForward.errorOccurred()
                && canConnect(portForward.getLocalAddress(), portForward.getLocalPort());
    }

    boolean canConnect(InetAddress address, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(address, port), 200);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    boolean isPortAvailable(InetAddress bindAddress, int port) {
        try (ServerSocket serverSocket = new ServerSocket()) {
            serverSocket.bind(new InetSocketAddress(bindAddress, port));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    InetAddress resolveBindAddress() {
        try {
            return InetAddress.getByName(config.localDebug().bindAddress());
        } catch (IOException e) {
            throw new IllegalStateException("Unable to resolve local debug bind address.", e);
        }
    }

    record ForwardEntry(String serviceName, LocalPortForward portForward) {
        String baseUrl() {
            return String.format("http://%s:%d", portForward.getLocalAddress().getHostAddress(), portForward.getLocalPort());
        }
    }
}
