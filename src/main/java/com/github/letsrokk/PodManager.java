package com.github.letsrokk;

import com.github.letsrokk.exceptions.PodCreationException;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceList;
import io.fabric8.kubernetes.api.model.StatusDetails;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
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
    MockFleetConfig config;

    @ConfigProperty(name = "mock-fleet.inactivity-threshold")
    Duration inactivityThreshold;

    @ConfigProperty(name = "mock-fleet.pod-creation-timeout")
    Duration podCreationTimeout;

    public String getUpstreamBaseUrl(String mockId) {
        Pod existingPod = podState.getPod(mockId);
        Pod pod = existingPod != null ? existingPod : podState.getPod(mockId, this::spawnPod);
        podState.setLastAccessTime(pod.getMetadata().getName(), Instant.now().toEpochMilli());
        if (existingPod != null) {
            ensureServiceExists(mockId);
        }
        return buildServiceBaseUrl(mockId);
    }

    public List<ActiveMockPod> listActiveMocks() {
        return podState.getPods().entrySet().stream()
                .map(entry -> new ActiveMockPod(entry.getKey(), entry.getValue().getMetadata().getName()))
                .sorted(Comparator.comparing(ActiveMockPod::mockId))
                .toList();
    }

    public DeleteMockResult deleteMock(String mockId) {
        Pod pod = podState.getPod(mockId);
        if (pod == null) {
            return DeleteMockResult.NOT_FOUND;
        }
        if (!deletePod(pod)) {
            return DeleteMockResult.FAILED;
        }

        LOG.infof("Pod '%s' deleted manually for mock id '%s'.", pod.getMetadata().getName(), mockId);
        deleteService(mockId);
        podState.removePod(mockId);
        return DeleteMockResult.DELETED;
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
        String namespace = currentNamespace();

        pod = kubernetesClient.resource(pod)
                .inNamespace(namespace)
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
        try {
            kubernetesClient.resource(service)
                    .inNamespace(namespace)
                    .create();
        } catch (KubernetesClientException e) {
            if (e.getCode() == 409) {
                LOG.debugf("Service '%s' already exists for mock id '%s'.", serviceName, mockId);
                return;
            }
            throw e;
        }
    }

    String buildServiceBaseUrl(String mockId) {
        return String.format("http://%s.%s.svc.cluster.local:8080", serviceFactory.serviceName(mockId), currentNamespace());
    }

    String currentNamespace() {
        String clientNamespace = kubernetesClient.getNamespace();
        if (clientNamespace != null && !clientNamespace.isBlank()) {
            return clientNamespace;
        }

        if (config != null) {
            String configuredNamespace = config.namespace();
            if (configuredNamespace != null && !configuredNamespace.isBlank()) {
                return configuredNamespace;
            }
        }

        return "mock-fleet";
    }

    /**
     * Wait for the Pod to become Running and Ready.
     * @param pod Pod item
     * @return updated Pod
     */
    Pod waitForPodToBeRunning(Pod pod, Duration timeout) {
        LOG.debugf("Waiting for pod '%s'...", pod.getMetadata().getName());

        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            Pod currentPod = kubernetesClient.resource(pod).get();
            if (isPodReady(currentPod)) {
                LOG.infof("Pod '%s' is Running and Ready.", currentPod.getMetadata().getName());
                    return currentPod;
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

    boolean isPodReady(Pod pod) {
        if (pod == null || pod.getStatus() == null) {
            return false;
        }

        String phase = pod.getStatus().getPhase();
        if (!"Running".equalsIgnoreCase(phase)) {
            return false;
        }

        return pod.getStatus().getConditions() != null
                && pod.getStatus().getConditions().stream()
                .filter(condition -> Objects.equals("Ready", condition.getType()))
                .anyMatch(condition -> "True".equalsIgnoreCase(condition.getStatus()));
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
        Service service = kubernetesClient.services()
                .inNamespace(currentNamespace())
                .withName(serviceFactory.serviceName(mockId))
                .get();
        return service == null || deleteService(service);
    }

    boolean deleteService(Service service) {
        return wasDeleteSuccessful(kubernetesClient.resource(service).delete());
    }

    boolean wasDeleteSuccessful(List<StatusDetails> details) {
        return details != null && !details.isEmpty();
    }

    public record ActiveMockPod(String mockId, String podName) {
    }

    public enum DeleteMockResult {
        DELETED,
        NOT_FOUND,
        FAILED
    }

}
