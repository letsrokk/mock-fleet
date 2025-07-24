package com.github.letsrokk;

import com.github.letsrokk.exceptions.MockIdNotFound;
import com.github.letsrokk.exceptions.PodCreationException;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.cache.CacheResult;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@ApplicationScoped
public class PodManager {

    private static final Logger LOG = Logger.getLogger(PodManager.class);

    @Inject
    PodState podState;

    @Inject
    KubernetesClient kubernetesClient;

    // Inactivity threshold (e.g., 1 minute = 60_000 ms).
    // Could be externalized via @ConfigProperty if desired.
    private static final long INACTIVITY_THRESHOLD_MS = 60_000;

    // Pod creation timeout (e.g., 1 minute = 60_000 ms).
    // Could be externalized via @ConfigProperty if desired.
    private static final long POD_CREATION_TIMEOUT_MS = 60_000;

    public String getPodIP(String host) {
        // 1. Extract subdomain from Host header
        String mockId = extractMockId(host);
        Pod pod = podState.getPod(mockId, this::spawnPod);
        podState.setLastAccessTime1(pod.getMetadata().getName(), Instant.now().toEpochMilli());
        return pod.getStatus().getPodIP();
    }

    /**
     * Extract mock id from Host header
     * @param host Host header value
     * @return mock id
     */
    private String extractMockId(String host) {
        // verify that host is valid
        if (host == null || host.isEmpty()) {
            throw new MockIdNotFound("Host header is missing or empty.");
        }

        // "abc.example.com" => mock id "abc"
        String[] parts = host.split("\\.");
        String subdomain = parts[0];

        // Basic cleanup for DNS-1123
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
        Pod pod = PodFactory.createMyPodSpec(podNamePrefix, mockId);

        pod = kubernetesClient.resource(pod)
                .inNamespace(kubernetesClient.getNamespace())
                .create();

        pod = waitForPodToBeRunning(pod);

        return pod;
    }

    /**
     * Wait for the Pod to become Running.
     * @param pod Pod item
     * @return updated Pod
     */
    @Retry(
            maxRetries = -1,
            maxDuration = POD_CREATION_TIMEOUT_MS,
            durationUnit = ChronoUnit.MILLIS,
            delay = 100,
            delayUnit = ChronoUnit.MILLIS
    )
    public Pod waitForPodToBeRunning(Pod pod) {
        LOG.debugf("Waiting for pod '%s'...", pod.getMetadata().getName());

        pod = kubernetesClient.resource(pod).get();

        if (pod != null && pod.getStatus() != null) {
            String phase = pod.getStatus().getPhase();
            if ("Running".equalsIgnoreCase(phase)) {
                LOG.infof("Pod '%s' is Running.", pod.getMetadata().getName());
                return pod;
            } else {
                throw new PodCreationException("Pod '" + pod.getMetadata().getName() + "' is not running.");
            }
        } else {
            throw new PodCreationException("Unable to fetch pod information.");
        }

    }

    /**
     * Periodic job that checks inactivity for each pod.
     * Runs every 5m in this example.
     */
    @Scheduled(every = "5m")
    public void cleanUpIdlePods() {
        long now = System.currentTimeMillis();

        podState.getPods().forEach((mockId, pod) -> {
            long lastAccess = podState.getLastAccessTime1(pod.getMetadata().getName());
            long diff = now - lastAccess;
            if (diff > INACTIVITY_THRESHOLD_MS) {

                boolean deleted = kubernetesClient
                        .resource(pod)
                        .delete()
                        .stream()
                        .anyMatch(statusDetails ->
                                statusDetails.getCauses() != null || !statusDetails.getCauses().isEmpty());

                if (deleted) {
                    LOG.infof("Pod '%s' deleted successfully.", pod.getMetadata().getName());
                } else {
                    LOG.warnf("Failed to delete Pod '%s'.", pod.getMetadata().getName());
                }

                podState.removePod(mockId);
            }
        });
    }

    /**
     * Periodic job that checks for orphaned pods.
     * Runs every 5m with initial 5m delay in this example.
     */
    @Scheduled(every = "5m", delayed = "5m")
    public void cleanUpOrphanedPods() {
        PodList podList = kubernetesClient.pods()
                .inNamespace(kubernetesClient.getNamespace())
                .withLabel("app", "mock-fleet-wiremock")
                .list();

        List<String> ownedPods = podState.getPods().values().stream().map(pod -> pod.getMetadata().getName()).toList();

        podList.getItems().forEach(p -> {
            String podName = p.getMetadata().getName();
            boolean isOrphaned = ownedPods.stream().noneMatch(v -> v.equals(podName));
            if (isOrphaned) {
                boolean deleted = kubernetesClient.resource(p).delete()
                        .stream()
                        .anyMatch(statusDetails ->
                                statusDetails.getCauses() != null || !statusDetails.getCauses().isEmpty());
                if (deleted) {
                    LOG.infof("Pod '%s' deleted successfully.", podName);
                } else {
                    LOG.warnf("Failed to delete Pod '%s'.", podName);
                }
            }
        });
    }

}
