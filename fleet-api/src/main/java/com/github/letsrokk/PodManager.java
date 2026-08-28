package com.github.letsrokk;

import com.github.letsrokk.exceptions.PodCreationException;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.StatusDetails;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.PodResource;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

    Executor startExecutor = Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(task, "mock-pod-start");
        thread.setDaemon(true);
        return thread;
    });

    public String getUpstreamBaseUrl(String mockId) {
        MockPodRef pod = getOrStartPod(mockId);
        podState.setLastAccessTime(pod.podName(), Instant.now().toEpochMilli());
        return buildPodBaseUrl(pod);
    }

    public MockPodStatus startMock(String mockId) {
        PodState.StartClaim claim = claimStart(mockId);
        if (claim.claimed()) {
            startExecutor.execute(() -> startClaimedMock(
                    mockId, claim.lifecycle().attemptId(), claim.previousPodName()));
        }
        return status(mockId);
    }

    public MockPodStatus restartActive(String mockId) {
        PodState.RestartClaim claim = podState.claimRestart(mockId);
        if (!claim.claimed()) {
            MockPodLifecycle lifecycle = claim.lifecycle();
            return new MockPodStatus(mockId, lifecycle.podName(), lifecycle.status(), lifecycle.message());
        }
        startExecutor.execute(() -> replaceClaimedMock(mockId, claim));
        MockPodLifecycle lifecycle = claim.lifecycle();
        return new MockPodStatus(mockId, lifecycle.podName(), lifecycle.status(), lifecycle.message());
    }

    private void replaceClaimedMock(String mockId, PodState.RestartClaim claim) {
        String previousPodName = claim.previousPodName();
        if (previousPodName != null && !previousPodName.isBlank() && !deletePod(previousPodName)) {
            PodCreationException failure = new PodCreationException(
                    "Failed to delete previous pod '" + previousPodName + "' before replacement startup.");
            podState.failStart(mockId, claim.lifecycle().attemptId(), failure);
            LOG.warnf(failure, "Failed to replace active pod for mock id '%s'.", mockId);
            return;
        }
        try {
            startClaimedMock(mockId, claim.lifecycle().attemptId());
        } catch (RuntimeException failure) {
            LOG.warnf(failure, "Failed to replace active pod for mock id '%s'.", mockId);
        }
    }

    public MockPodStatus status(String mockId) {
        MockPodLifecycle lifecycle = podState.lifecycle(mockId);
        return new MockPodStatus(mockId, lifecycle.podName(), lifecycle.status(), lifecycle.message());
    }

    private MockPodRef getOrStartPod(String mockId) {
        MockPodRef existing = podState.getPod(mockId);
        if (existing != null) {
            return existing;
        }
        PodState.StartClaim claim = claimStart(mockId);
        if (claim.pod() != null) {
            return claim.pod();
        }
        if (claim.claimed()) {
            return startClaimedMock(mockId, claim.lifecycle().attemptId(), claim.previousPodName());
        }
        long deadline = System.nanoTime() + podCreationTimeout.toNanos();
        while (System.nanoTime() < deadline) {
            MockPodRef pod = podState.getPod(mockId);
            if (pod != null) {
                return pod;
            }
            MockPodLifecycle lifecycle = podState.lifecycle(mockId);
            if (lifecycle.status() == MockLifecycleStatus.FAILED) {
                throw new PodCreationException(lifecycle.message());
            }
            if (lifecycle.status() == MockLifecycleStatus.STOPPED) {
                throw new PodCreationException("Pod startup was stopped.");
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new PodCreationException("Interrupted while waiting for pod startup.");
            }
        }
        throw new PodCreationException("Mock '" + mockId + "' did not become running before timeout.");
    }

    private MockPodRef startClaimedMock(String mockId, String attemptId) {
        return startClaimedMock(mockId, attemptId, null);
    }

    private MockPodRef startClaimedMock(String mockId, String attemptId, String previousPodName) {
        try {
            if (previousPodName != null && !previousPodName.isBlank() && !deletePod(previousPodName)) {
                throw new PodCreationException(
                        "Failed to delete stale startup pod '" + previousPodName + "' before retry.");
            }
            MockPodRef pod = spawnPod(mockId, attemptId);
            if (!podState.completeStart(mockId, attemptId, pod)) {
                deletePod(pod);
                throw new PodCreationException("Pod startup was superseded or stopped.");
            }
            return pod;
        } catch (RuntimeException error) {
            podState.failStart(mockId, attemptId, error);
            throw error;
        }
    }

    private PodState.StartClaim claimStart(String mockId) {
        long configuredTimeoutMillis = Math.max(1L, podCreationTimeout.toMillis());
        long startupLeaseMillis = configuredTimeoutMillis > Long.MAX_VALUE / 2L
                ? Long.MAX_VALUE
                : configuredTimeoutMillis * 2L;
        return podState.claimStart(mockId, System.currentTimeMillis(), startupLeaseMillis);
    }

    @PreDestroy
    void closeStartExecutor() {
        if (startExecutor instanceof ExecutorService executorService) {
            executorService.shutdownNow();
        }
    }

    public List<ActiveMockPod> listActiveMocks() {
        return podState.getPods().entrySet().stream()
                .map(entry -> new ActiveMockPod(entry.getKey(), entry.getValue().podName()))
                .sorted(Comparator.comparing(ActiveMockPod::mockId))
                .toList();
    }

    public List<MockPodStatus> listMocks() {
        Map<String, MockPodStatus> mocks = new HashMap<>();
        podState.getPodLifecycles().entrySet().forEach(entry -> {
            MockPodLifecycle lifecycle = entry.getValue();
            if (lifecycle.status() == MockLifecycleStatus.STOPPED) {
                return;
            }
            mocks.put(entry.getKey(), new MockPodStatus(
                    entry.getKey(), lifecycle.podName(), lifecycle.status(), lifecycle.message()));
        });
        podState.getPods().entrySet().forEach(entry -> mocks.put(entry.getKey(),
                new MockPodStatus(entry.getKey(), entry.getValue().podName(), MockLifecycleStatus.RUNNING, null)));
        return mocks.values().stream()
                .sorted(Comparator.comparing(MockPodStatus::mockId))
                .toList();
    }

    public DeleteMockResult deleteMock(String mockId) {
        PodState.StopClaim stopped = podState.stop(mockId);
        if (stopped.podName() == null || stopped.podName().isBlank()) {
            return DeleteMockResult.STOPPED;
        }
        if (!deletePod(stopped.podName())) {
            return DeleteMockResult.FAILED;
        }

        podState.confirmStopped(mockId, stopped.podName());
        LOG.infof("Pod '%s' deleted manually for mock id '%s'.", stopped.podName(), mockId);
        return DeleteMockResult.DELETED;
    }

    /**
     * Spawn a new mock pod
     * @param mockId mock id
     * @return mock pod reference
     */
    public MockPodRef spawnPod(String mockId) {
        return spawnPod(mockId, null);
    }

    MockPodRef spawnPod(String mockId, String attemptId) {
        LOG.infof("Creating pod for mock id '%s'...", mockId);

        String podNamePrefix = String.format("%s-%s-", config.wiremockPodNamePrefix(), mockId);
        Pod pod = podFactory.createPodSpec(podNamePrefix, mockId,
                wireMockOptions.optionsFor(mockId),
                wireMockOptions.resourcesFor(mockId));
        String namespace = currentNamespace();

        pod = kubernetesClient.resource(pod)
                .inNamespace(namespace)
                .create();
        if (attemptId == null) {
            podState.markStartupPodName(mockId, pod.getMetadata().getName());
        } else if (!podState.markStartupPodName(mockId, attemptId, pod.getMetadata().getName())) {
            deletePod(pod);
            throw new PodCreationException("Pod startup was superseded or stopped.");
        }

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
            if (currentPod == null) {
                throw new PodCreationException("Pod '" + pod.getMetadata().getName()
                        + "' disappeared while starting.");
            }
            if (isPodReady(currentPod)) {
                LOG.infof("Pod '%s' is Running and Ready.", currentPod.getMetadata().getName());
                    return currentPod;
            }
            terminalPodFailure(currentPod).ifPresent(message -> {
                throw new PodCreationException(message);
            });

            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new PodCreationException("Interrupted while waiting for pod to become running.");
            }
        }

        throw new PodCreationException("Pod '" + pod.getMetadata().getName() + "' did not become running before timeout.");
    }

    private java.util.Optional<String> terminalPodFailure(Pod pod) {
        if (pod == null || pod.getStatus() == null) {
            return java.util.Optional.empty();
        }
        String podName = pod.getMetadata() == null ? null : pod.getMetadata().getName();
        String prefix = "Pod '" + podName + "' failed: ";
        if ("Failed".equalsIgnoreCase(pod.getStatus().getPhase())) {
            return java.util.Optional.of(prefix + diagnostic(
                    pod.getStatus().getReason(), pod.getStatus().getMessage(), "terminal pod phase"));
        }
        if (pod.getStatus().getContainerStatuses() == null) {
            return java.util.Optional.empty();
        }
        for (var containerStatus : pod.getStatus().getContainerStatuses()) {
            if (containerStatus.getState() == null) {
                continue;
            }
            var terminated = containerStatus.getState().getTerminated();
            if (terminated != null) {
                String fallback = "container " + containerStatus.getName() + " exited with code " + terminated.getExitCode();
                return java.util.Optional.of(prefix + diagnostic(
                        terminated.getReason(), terminated.getMessage(), fallback));
            }
            var waiting = containerStatus.getState().getWaiting();
            if (waiting != null && isTerminalWaitingReason(waiting.getReason())) {
                return java.util.Optional.of(prefix + diagnostic(
                        waiting.getReason(), waiting.getMessage(), "container could not start"));
            }
        }
        return java.util.Optional.empty();
    }

    private boolean isTerminalWaitingReason(String reason) {
        return reason != null && List.of(
                "CrashLoopBackOff", "ImagePullBackOff", "ErrImagePull", "CreateContainerConfigError",
                "InvalidImageName", "RunContainerError").contains(reason);
    }

    private String diagnostic(String reason, String message, String fallback) {
        if (reason != null && !reason.isBlank() && message != null && !message.isBlank()) {
            return reason + ": " + message;
        }
        if (reason != null && !reason.isBlank()) {
            return reason;
        }
        if (message != null && !message.isBlank()) {
            return message;
        }
        return fallback;
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

        java.util.Set<String> ownedPods = new java.util.HashSet<>();
        podState.getPods().values().stream()
                .map(MockPodRef::podName)
                .filter(Objects::nonNull)
                .forEach(ownedPods::add);
        podState.getPodLifecycles().values().stream()
                .map(MockPodLifecycle::podName)
                .filter(Objects::nonNull)
                .filter(podName -> !podName.isBlank())
                .forEach(ownedPods::add);

        podList.getItems().forEach(p -> {
            String podName = p.getMetadata().getName();
            boolean isOrphaned = !ownedPods.contains(podName);
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
        return deletePod(pod.podName());
    }

    boolean deletePod(String podName) {
        var podResource = kubernetesClient.pods()
                .inNamespace(currentNamespace())
                .withName(podName);
        List<StatusDetails> details = podResource.delete();
        if (!wasDeleteSuccessful(details)) {
            return podResource.get() == null;
        }
        return waitForPodToBeDeleted(podName, podResource);
    }

    private boolean waitForPodToBeDeleted(String podName, PodResource podResource) {
        if (podResource.get() == null) {
            return true;
        }
        long deadline = System.nanoTime() + podCreationTimeout.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOG.warnf("Interrupted while waiting for pod '%s' to be deleted.", podName);
                return false;
            }
            if (podResource.get() == null) {
                return true;
            }
        }
        LOG.warnf("Pod '%s' was still present after the deletion timeout.", podName);
        return false;
    }

    boolean wasDeleteSuccessful(List<StatusDetails> details) {
        return details != null && !details.isEmpty();
    }

    public record ActiveMockPod(String mockId, String podName) {
    }

    public record MockPodStatus(String mockId, String podName, MockLifecycleStatus status, String message) {
    }

    public enum DeleteMockResult {
        DELETED,
        NOT_FOUND,
        STOPPED,
        FAILED
    }

}
