package com.github.letsrokk;

import com.github.letsrokk.exceptions.MockIdNotFound;
import com.github.letsrokk.exceptions.PodCreationException;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceList;
import io.fabric8.kubernetes.api.model.StatusDetails;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

@ApplicationScoped
public class PodManager {

    private static final Logger LOG = Logger.getLogger(PodManager.class);

    @Inject
    PodState podState;

    @Inject
    KubernetesClient kubernetesClient;

    @Inject
    PodFactory podFactory;

    @Inject
    ServiceFactory serviceFactory;

    @Inject
    LocalServicePortForwardManager localServicePortForwardManager;

    @Inject
    MockFleetConfig config;

    @ConfigProperty(name = "mock-fleet.inactivity-threshold")
    Duration inactivityThreshold;

    @ConfigProperty(name = "mock-fleet.pod-creation-timeout")
    Duration podCreationTimeout;

    public String getUpstreamBaseUrl(String host) {
        String mockId = extractMockId(host);
        Pod pod = podState.getPod(mockId, this::spawnPod);
        podState.setLastAccessTime(pod.getMetadata().getName(), Instant.now().toEpochMilli());
        ensureServiceExists(mockId);
        if (config.localDebug().enabled()) {
            return localServicePortForwardManager.getOrCreateForwardBaseUrl(mockId, currentNamespace());
        }
        return buildServiceBaseUrl(mockId);
    }

    /**
     * Extract mock id from Host header
     * @param host Host header value
     * @return mock id
     */
    String extractMockId(String host) {
        if (host == null || host.isBlank()) {
            throw new MockIdNotFound("Host header is missing or empty.");
        }

        String normalizedHost = host.trim();
        int portSeparator = normalizedHost.indexOf(':');
        if (portSeparator >= 0) {
            normalizedHost = normalizedHost.substring(0, portSeparator);
        }
        if (normalizedHost.isBlank()) {
            throw new MockIdNotFound(String.format("Unable to extract mock id from host '%s'.", host));
        }

        String[] parts = normalizedHost.split("\\.");
        String subdomain = parts[0];

        subdomain = subdomain.toLowerCase().replaceAll("[^a-z0-9\\-]", "");
        if (subdomain.isEmpty()) {
            throw new MockIdNotFound(String.format("Unable to extract mock id from host '%s'.", host));
        }

        // Trim to max length
        if (subdomain.length() > 63) {
            subdomain = subdomain.substring(0, 63);
        }

        return subdomain;
    }

    /**
     * Spawn a new mock pod
     * @param mockId mock id
     * @return mock pod
     */
    public Pod spawnPod(String mockId) {
        LOG.infof("Creating pod for mock id '%s'...", mockId);

        String podNamePrefix = String.format("mock-fleet-%s-", mockId);
        Pod pod = podFactory.createPodSpec(podNamePrefix, mockId);

        pod = kubernetesClient.resource(pod)
                .inNamespace(kubernetesClient.getNamespace())
                .create();

        pod = waitForPodToBeRunning(pod, podCreationTimeout);
        ensureServiceExists(mockId);

        return pod;
    }

    void ensureServiceExists(String mockId) {
        String namespace = currentNamespace();
        String serviceName = serviceFactory.serviceName(mockId);
        Service existingService = kubernetesClient.services()
                .inNamespace(namespace)
                .withName(serviceName)
                .get();

        if (existingService != null) {
            return;
        }

        LOG.infof("Creating service '%s' for mock id '%s'.", serviceName, mockId);
        Service service = serviceFactory.createServiceSpec(mockId);
        kubernetesClient.resource(service)
                .inNamespace(namespace)
                .create();
    }

    String buildServiceBaseUrl(String mockId) {
        return String.format("http://%s.%s.svc.cluster.local:8080", serviceFactory.serviceName(mockId), currentNamespace());
    }

    String currentNamespace() {
        return Objects.requireNonNullElse(kubernetesClient.getNamespace(), "default");
    }

    /**
     * Wait for the Pod to become Running.
     * @param pod Pod item
     * @return updated Pod
     */
    Pod waitForPodToBeRunning(Pod pod, Duration timeout) {
        LOG.debugf("Waiting for pod '%s'...", pod.getMetadata().getName());

        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            Pod currentPod = kubernetesClient.resource(pod).get();
            if (currentPod != null && currentPod.getStatus() != null) {
                String phase = currentPod.getStatus().getPhase();
                if ("Running".equalsIgnoreCase(phase)) {
                    LOG.infof("Pod '%s' is Running.", currentPod.getMetadata().getName());
                    return currentPod;
                }
            }

            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new PodCreationException("Interrupted while waiting for pod to become running.");
            }
        }

        throw new PodCreationException("Pod '" + pod.getMetadata().getName() + "' did not become running before timeout.");
    }

    /**
     * Periodic job that checks inactivity for each pod.
     * Runs every 5m in this example.
     */
    @Scheduled(every = "5m")
    public void cleanUpIdlePods() {
        long now = System.currentTimeMillis();

        podState.getPods().forEach((mockId, pod) -> {
            Long lastAccess = podState.getLastAccessTime(pod.getMetadata().getName());
            if (lastAccess == null) {
                LOG.warnf("Skipping idle cleanup for pod '%s' because no last access time is recorded.", pod.getMetadata().getName());
                return;
            }

            long diff = now - lastAccess;
            if (diff > inactivityThreshold.toMillis()) {

                boolean deleted = deletePod(pod);

                if (deleted) {
                    LOG.infof("Pod '%s' deleted successfully.", pod.getMetadata().getName());
                    deleteService(mockId);
                    podState.removePod(mockId);
                } else {
                    LOG.warnf("Failed to delete Pod '%s'.", pod.getMetadata().getName());
                }
            }
        });
    }

    /**
     * Periodic job that checks for orphaned pods.
     * Runs every 5m with initial 5m delay in this example.
     */
    @Scheduled(every = "5m", delayed = "5m")
    public void cleanUpOrphanedPods() {
        String namespace = currentNamespace();
        PodList podList = kubernetesClient.pods()
                .inNamespace(namespace)
                .withLabel(PodFactory.LABEL_MANAGED_BY, PodFactory.MANAGED_BY_VALUE)
                .list();

        List<String> ownedPods = podState.getPods().values().stream().map(pod -> pod.getMetadata().getName()).toList();

        podList.getItems().forEach(p -> {
            String podName = p.getMetadata().getName();
            boolean isOrphaned = ownedPods.stream().noneMatch(v -> v.equals(podName));
            if (isOrphaned) {
                boolean deleted = deletePod(p);
                if (deleted) {
                    LOG.infof("Pod '%s' deleted successfully.", podName);
                } else {
                    LOG.warnf("Failed to delete Pod '%s'.", podName);
                }
            }
        });

        ServiceList serviceList = kubernetesClient.services()
                .inNamespace(namespace)
                .withLabel(PodFactory.LABEL_MANAGED_BY, PodFactory.MANAGED_BY_VALUE)
                .list();

        List<String> ownedServiceNames = podState.getPods().keySet().stream()
                .map(serviceFactory::serviceName)
                .toList();

        serviceList.getItems().forEach(service -> {
            String serviceName = service.getMetadata().getName();
            boolean isOrphaned = ownedServiceNames.stream().noneMatch(serviceName::equals);
            if (isOrphaned) {
                boolean deleted = deleteService(service);
                if (deleted) {
                    LOG.infof("Service '%s' deleted successfully.", serviceName);
                } else {
                    LOG.warnf("Failed to delete Service '%s'.", serviceName);
                }
            }
        });
    }

    boolean deletePod(Pod pod) {
        return wasDeleteSuccessful(kubernetesClient.resource(pod).delete());
    }

    boolean deleteService(String mockId) {
        localServicePortForwardManager.closeForMock(mockId);
        Service service = kubernetesClient.services()
                .inNamespace(currentNamespace())
                .withName(serviceFactory.serviceName(mockId))
                .get();
        return service == null || deleteService(service);
    }

    boolean deleteService(Service service) {
        localServicePortForwardManager.close(service.getMetadata().getName());
        return wasDeleteSuccessful(kubernetesClient.resource(service).delete());
    }

    boolean wasDeleteSuccessful(List<StatusDetails> details) {
        return details != null && !details.isEmpty();
    }

}
