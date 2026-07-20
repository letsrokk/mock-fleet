package com.github.letsrokk;

import com.github.letsrokk.exceptions.PodCreationException;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.StatusDetails;
import io.fabric8.kubernetes.client.KubernetesClient;
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
    WireMockOptions wireMockOptions;

    @Inject
    MockFleetConfig config;

    @ConfigProperty(name = "mock-fleet.inactivity-threshold")
    Duration inactivityThreshold;

    @ConfigProperty(name = "mock-fleet.pod-creation-timeout")
    Duration podCreationTimeout;

    public String getUpstreamBaseUrl(String mockId) {
        MockPodRef pod = podState.getPod(mockId, this::spawnPod);
        podState.setLastAccessTime(pod.podName(), Instant.now().toEpochMilli());
        return buildPodBaseUrl(pod);
    }

    public List<ActiveMockPod> listActiveMocks() {
        return podState.getPods().entrySet().stream()
                .map(entry -> new ActiveMockPod(entry.getKey(), entry.getValue().podName()))
                .sorted(Comparator.comparing(ActiveMockPod::mockId))
                .toList();
    }

    public DeleteMockResult deleteMock(String mockId) {
        MockPodRef pod = podState.getPod(mockId);
        if (pod == null) {
            return DeleteMockResult.NOT_FOUND;
        }
        if (!deletePod(pod)) {
            return DeleteMockResult.FAILED;
        }

        LOG.infof("Pod '%s' deleted manually for mock id '%s'.", pod.podName(), mockId);
        podState.removePod(mockId);
        return DeleteMockResult.DELETED;
    }

    /**
     * Spawn a new mock pod
     * @param mockId mock id
     * @return mock pod reference
     */
    public MockPodRef spawnPod(String mockId) {
        LOG.infof("Creating pod for mock id '%s'...", mockId);

        String podNamePrefix = String.format("%s-%s-", config.wiremockPodNamePrefix(), mockId);
        Pod pod = podFactory.createPodSpec(podNamePrefix, mockId,
                wireMockOptions.optionsFor(mockId),
                wireMockOptions.resourcesFor(mockId));
        String namespace = currentNamespace();

        pod = kubernetesClient.resource(pod)
                .inNamespace(namespace)
                .create();

        pod = waitForPodToBeRunning(pod, podCreationTimeout);
        LOG.infof("Created pod '%s' for mock id '%s' in namespace '%s'.",
                pod.getMetadata().getName(), mockId, namespace);
        return podRef(pod);
    }

    String buildPodBaseUrl(MockPodRef pod) {
        return String.format("http://%s:8080", pod.podIp());
    }

    MockPodRef podRef(Pod pod) {
        String podName = pod.getMetadata() == null ? null : pod.getMetadata().getName();
        String podIp = pod.getStatus() == null ? null : pod.getStatus().getPodIP();
        if (podName == null || podName.isBlank()) {
            throw new PodCreationException("Created pod is missing its name.");
        }
        if (podIp == null || podIp.isBlank()) {
            throw new PodCreationException("Pod '" + podName + "' did not receive a pod IP.");
        }
        return new MockPodRef(podName, podIp);
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
            Long lastAccess = podState.getLastAccessTime(pod.podName());
            if (lastAccess == null) {
                LOG.warnf("Skipping idle cleanup for pod '%s' because no last access time is recorded.", pod.podName());
                return;
            }

            long diff = now - lastAccess;
            if (diff > inactivityThreshold.toMillis()) {

                boolean deleted = deletePod(pod);

                if (deleted) {
                    LOG.infof("Pod '%s' deleted for mock id '%s' after %dms of inactivity.",
                            pod.podName(), mockId, diff);
                    podState.removePod(mockId);
                } else {
                    LOG.warnf("Failed to delete inactive pod '%s' for mock id '%s'.", pod.podName(), mockId);
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

        List<String> ownedPods = podState.getPods().values().stream().map(MockPodRef::podName).toList();

        podList.getItems().forEach(p -> {
            String podName = p.getMetadata().getName();
            boolean isOrphaned = ownedPods.stream().noneMatch(v -> v.equals(podName));
            if (isOrphaned) {
                boolean deleted = deletePod(p);
                if (deleted) {
                    LOG.infof("Orphaned pod '%s' deleted in namespace '%s'.", podName, namespace);
                } else {
                    LOG.warnf("Failed to delete orphaned pod '%s' in namespace '%s'.", podName, namespace);
                }
            }
        });
    }

    boolean deletePod(Pod pod) {
        return wasDeleteSuccessful(kubernetesClient.resource(pod).delete());
    }

    boolean deletePod(MockPodRef pod) {
        return wasDeleteSuccessful(kubernetesClient.pods()
                .inNamespace(currentNamespace())
                .withName(pod.podName())
                .delete());
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
