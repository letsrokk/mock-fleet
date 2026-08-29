package com.github.letsrokk;

import com.github.letsrokk.exceptions.PodCreationException;
import io.fabric8.kubernetes.api.model.DeleteOptions;
import io.fabric8.kubernetes.api.model.DeleteOptionsBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.utils.Utils;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

@ApplicationScoped
public class PodManager {

    private static final Logger LOG = Logger.getLogger(PodManager.class);
    private static final long START_EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS = 5;

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

    @Inject
    PodTransitionCoordinator podTransitionCoordinator;

    @Inject
    MockCapacity mockCapacity;

    @ConfigProperty(name = "mock-fleet.inactivity-threshold")
    Duration inactivityThreshold;

    @ConfigProperty(name = "mock-fleet.pod-creation-timeout")
    Duration podCreationTimeout;

    Executor startExecutor = Runnable::run;
    ScheduledExecutorService reservationHeartbeatExecutor;
    private final java.util.Set<StartAttempt> activeStartAttempts = ConcurrentHashMap.newKeySet();

    @PostConstruct
    void initializeStartExecutor() {
        AtomicInteger threadSequence = new AtomicInteger();
        startExecutor = new ThreadPoolExecutor(
                config.maxConcurrentStarts(),
                config.maxConcurrentStarts(),
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(config.queuedStartCapacity()),
                task -> {
                    Thread thread = new Thread(task,
                            "mock-pod-start-" + threadSequence.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
        if (mockCapacity != null) {
            long heartbeatIntervalMillis = Math.max(1L, mockCapacity.reservationLeaseMillis() / 3L);
            reservationHeartbeatExecutor = Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "mock-capacity-heartbeat");
                thread.setDaemon(true);
                return thread;
            });
            reservationHeartbeatExecutor.scheduleWithFixedDelay(
                    this::renewStartReservations,
                    heartbeatIntervalMillis,
                    heartbeatIntervalMillis,
                    TimeUnit.MILLISECONDS);
        }
    }

    public String getUpstreamBaseUrl(String mockId) {
        return getUpstreamBaseUrlAsync(mockId).toCompletableFuture().join();
    }

    public CompletionStage<String> getUpstreamBaseUrlAsync(String mockId) {
        MockPodRef existing = podState.getPod(mockId);
        if (existing != null) {
            return CompletableFuture.completedFuture(touchAndBuildUpstream(existing));
        }
        PodState.StartClaim claim = claimStart(mockId);
        if (claim.pod() != null) {
            return CompletableFuture.completedFuture(touchAndBuildUpstream(claim.pod()));
        }
        CompletionStage<MockPodRef> pod = claim.claimed()
                ? submitStart(mockId, claim.lifecycle().attemptId(), claim.previousPodName())
                : awaitCurrentStart(mockId);
        return pod.thenApply(this::touchAndBuildUpstream);
    }

    public MockPodStatus startMock(String mockId) {
        PodState.StartClaim claim = claimStart(mockId);
        if (claim.claimed()) {
            submitStart(mockId, claim.lifecycle().attemptId(), claim.previousPodName());
        }
        return status(mockId);
    }

    public MockPodStatus restartActive(String mockId) {
        PodState.RestartClaim claim = podState.claimRestart(mockId);
        if (!claim.claimed()) {
            MockPodLifecycle lifecycle = claim.lifecycle();
            return new MockPodStatus(mockId, lifecycle.podName(), lifecycle.status(), lifecycle.message());
        }
        submitStart(mockId, claim.lifecycle().attemptId(), claim.previousPodName(), failure -> {
            if (!podState.rollbackRejectedRestart(mockId, claim)) {
                podState.failStart(mockId, claim.lifecycle().attemptId(), failure);
            }
        }, true);
        MockPodLifecycle lifecycle = claim.lifecycle();
        return new MockPodStatus(mockId, lifecycle.podName(), lifecycle.status(), lifecycle.message());
    }

    private CompletionStage<MockPodRef> submitStart(String mockId, String attemptId, String previousPodName) {
        return submitStart(mockId, attemptId, previousPodName,
                failure -> podState.failStart(mockId, attemptId, failure), false);
    }

    private CompletionStage<MockPodRef> submitStart(String mockId, String attemptId, String previousPodName,
                                                    Consumer<PodCreationException> cancelAttempt,
                                                    boolean stateMayHaveChanged) {
        CompletableFuture<MockPodRef> completion = new CompletableFuture<>();
        StartTask task = new StartTask(
                mockId, attemptId, previousPodName, completion, cancelAttempt);
        StartAttempt startAttempt = new StartAttempt(mockId, attemptId);
        activeStartAttempts.add(startAttempt);
        completion.whenComplete((ignored, failure) -> activeStartAttempts.remove(startAttempt));
        try {
            startExecutor.execute(task);
        } catch (RejectedExecutionException rejection) {
            PodCreationException failure = new PodCreationException("Mock start queue is full.");
            task.cancelBeforeStart(failure);
            throw new StartQueueFullException(mockId, stateMayHaveChanged);
        }
        return completion;
    }

    private CompletionStage<MockPodRef> awaitCurrentStart(String mockId) {
        CompletableFuture<MockPodRef> completion = new CompletableFuture<>();
        AtomicReference<io.smallrye.mutiny.subscription.Cancellable> subscription = new AtomicReference<>();
        io.smallrye.mutiny.subscription.Cancellable subscribed = podState.podChanges().subscribe().with(
                ignored -> completeFromCurrentState(mockId, completion),
                completion::completeExceptionally);
        subscription.set(subscribed);
        if (completion.isDone()) {
            subscribed.cancel();
        }
        completion.whenComplete((ignored, failure) -> {
            io.smallrye.mutiny.subscription.Cancellable cancellable = subscription.get();
            if (cancellable != null) {
                cancellable.cancel();
            }
        });
        completeFromCurrentState(mockId, completion);
        completion.orTimeout(Math.max(1L, podCreationTimeout.toMillis()), TimeUnit.MILLISECONDS);
        return completion.exceptionallyCompose(failure -> CompletableFuture.failedFuture(
                failure instanceof java.util.concurrent.TimeoutException
                        ? new PodCreationException(
                                "Mock '" + mockId + "' did not become running before timeout.")
                        : failure));
    }

    private void completeFromCurrentState(String mockId, CompletableFuture<MockPodRef> completion) {
        if (completion.isDone()) {
            return;
        }
        MockPodRef pod = podState.getPod(mockId);
        if (pod != null) {
            completion.complete(pod);
            return;
        }
        MockPodLifecycle lifecycle = podState.lifecycle(mockId);
        if (lifecycle.status() == MockLifecycleStatus.FAILED) {
            completion.completeExceptionally(new PodCreationException(lifecycle.message()));
        } else if (lifecycle.status() == MockLifecycleStatus.STOPPED) {
            completion.completeExceptionally(new PodCreationException("Pod startup was stopped."));
        }
    }

    private String touchAndBuildUpstream(MockPodRef pod) {
        podState.setLastAccessTime(pod.podName(), Instant.now().toEpochMilli());
        return buildPodBaseUrl(pod);
    }

    public MockPodStatus status(String mockId) {
        MockPodLifecycle lifecycle = podState.lifecycle(mockId);
        return new MockPodStatus(mockId, lifecycle.podName(), lifecycle.status(), lifecycle.message());
    }

    private MockPodRef startClaimedMock(String mockId, String attemptId, String previousPodName) {
        return serializedPodTransition(mockId,
                () -> startClaimedMockSerialized(mockId, attemptId, previousPodName));
    }

    private MockPodRef startClaimedMockSerialized(String mockId, String attemptId, String previousPodName) {
        try {
            requireCurrentStartingAttempt(mockId, attemptId);
            if (previousPodName != null && !previousPodName.isBlank()
                    && !deletePod(previousPodName, mockId)) {
                throw new PodCreationException(
                        "Failed to delete stale startup pod '" + previousPodName + "' before retry.");
            }
            requireCurrentStartingAttempt(mockId, attemptId);
            requireCurrentReservation(mockId, attemptId);
            MockPodRef pod = spawnPod(mockId, attemptId);
            if (!podState.completeStart(mockId, attemptId, pod, Instant.now().toEpochMilli())) {
                if (!deletePod(pod, mockId)) {
                    throw new PodCreationException("Pod startup was superseded or stopped, and cleanup of pod '"
                            + pod.podName() + "' could not be confirmed.");
                }
                throw new PodCreationException("Pod startup was superseded or stopped.");
            }
            return pod;
        } catch (RuntimeException error) {
            RuntimeException reportedFailure = cleanUpFailedStartup(mockId, attemptId, error);
            podState.failStart(mockId, attemptId, reportedFailure);
            throw reportedFailure;
        }
    }

    private RuntimeException cleanUpFailedStartup(String mockId, String attemptId, RuntimeException failure) {
        String podName = podState.currentStartupPodName(mockId, attemptId);
        if (podName == null || podName.isBlank()) {
            return failure;
        }
        boolean deleted = deletePod(podName, mockId);
        try {
            podState.removeLastAccessTime(podName);
        } catch (RuntimeException cleanupFailure) {
            LOG.warnf(cleanupFailure,
                    "Failed to remove last-access state for startup pod '%s'.", podName);
        }
        if (deleted) {
            return failure;
        }
        return new PodCreationException(failure.getMessage()
                + " Cleanup of pod '" + podName + "' could not be confirmed.");
    }

    private void requireCurrentStartingAttempt(String mockId, String attemptId) {
        if (!podState.isCurrentStartingAttempt(mockId, attemptId)) {
            throw new PodCreationException("Pod startup was superseded or stopped.");
        }
    }

    private void requireCurrentReservation(String mockId, String attemptId) {
        if (mockCapacity != null && !mockCapacity.isCurrentReservation(mockId, attemptId)) {
            throw new PodCreationException("Pod startup was superseded or stopped.");
        }
    }

    void renewStartReservations() {
        if (mockCapacity == null) {
            return;
        }
        activeStartAttempts.forEach(attempt -> {
            try {
                if (!mockCapacity.renew(attempt.mockId(), attempt.attemptId())) {
                    activeStartAttempts.remove(attempt);
                }
            } catch (RuntimeException failure) {
                LOG.warnf(failure,
                        "Failed to renew capacity ownership for mock id '%s', attempt '%s'.",
                        attempt.mockId(), attempt.attemptId());
            }
        });
    }

    private <T> T serializedPodTransition(String mockId, Supplier<T> action) {
        return podTransitionCoordinator == null ? action.get() : podTransitionCoordinator.serialized(mockId, action);
    }

    private PodState.StartClaim claimStart(String mockId) {
        return podState.claimStart(mockId, System.currentTimeMillis(), startupLeaseMillis());
    }

    private long startupLeaseMillis() {
        long configuredTimeoutMillis = Math.max(1L, podCreationTimeout.toMillis());
        return configuredTimeoutMillis > Long.MAX_VALUE / 2L
                ? Long.MAX_VALUE
                : configuredTimeoutMillis * 2L;
    }

    @PreDestroy
    void closeStartExecutor() {
        if (reservationHeartbeatExecutor != null) {
            reservationHeartbeatExecutor.shutdownNow();
        }
        if (startExecutor instanceof ExecutorService executorService) {
            List<Runnable> abandoned = executorService.shutdownNow();
            abandoned.forEach(task -> {
                if (task instanceof StartTask startTask) {
                    startTask.cancelBeforeStart(
                            new PodCreationException("Mock start cancelled during shutdown."));
                } else {
                    LOG.warnf("Start executor abandoned an unowned task of type '%s'.",
                            task.getClass().getName());
                }
            });
            try {
                if (!executorService.awaitTermination(
                        START_EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    LOG.errorf("Start executor did not terminate within %d seconds.",
                            START_EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS);
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                LOG.warn("Interrupted while waiting for the start executor to terminate.", error);
            }
        }
    }

    private final class StartTask implements Runnable {
        private final String mockId;
        private final String attemptId;
        private final String previousPodName;
        private final CompletableFuture<MockPodRef> completion;
        private final Consumer<PodCreationException> cancelAttempt;

        private StartTask(String mockId, String attemptId, String previousPodName,
                          CompletableFuture<MockPodRef> completion,
                          Consumer<PodCreationException> cancelAttempt) {
            this.mockId = mockId;
            this.attemptId = attemptId;
            this.previousPodName = previousPodName;
            this.completion = completion;
            this.cancelAttempt = cancelAttempt;
        }

        @Override
        public void run() {
            try {
                completion.complete(startClaimedMock(mockId, attemptId, previousPodName));
            } catch (RuntimeException failure) {
                LOG.warnf(failure, "Failed to start pod for mock id '%s'.", mockId);
                completion.completeExceptionally(failure);
            } finally {
                activeStartAttempts.remove(new StartAttempt(mockId, attemptId));
            }
        }

        private void cancelBeforeStart(PodCreationException failure) {
            try {
                cancelAttempt.accept(failure);
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
                LOG.warnf(cleanupFailure,
                        "Failed to cancel queued start for mock id '%s', attempt '%s'.",
                        mockId, attemptId);
            } finally {
                completion.completeExceptionally(failure);
            }
        }
    }

    private record StartAttempt(String mockId, String attemptId) {
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
        if (!deletePod(stopped.podName(), mockId)) {
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

        if (attemptId != null) {
            String reservedPodName = podNamePrefix + attemptId;
            if (!podState.markStartupPodName(mockId, attemptId, reservedPodName)) {
                throw new PodCreationException("Pod startup was superseded or stopped.");
            }
            pod.getMetadata().setName(reservedPodName);
            pod.getMetadata().setGenerateName(null);
        }

        pod = kubernetesClient.resource(pod)
                .inNamespace(namespace)
                .create();
        if (attemptId == null) {
            podState.markStartupPodName(mockId, pod.getMetadata().getName());
        } else if (!podState.isCurrentStartingAttempt(mockId, attemptId)) {
            if (!deletePod(pod, mockId)) {
                throw new PodCreationException("Pod startup was superseded or stopped, and cleanup of pod '"
                        + pod.getMetadata().getName() + "' could not be confirmed.");
            }
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

                boolean deleted = deletePod(pod, mockId);

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
        if (mockCapacity == null) {
            podState.getPodLifecycles().values().stream()
                    .filter(lifecycle -> lifecycle.status() != MockLifecycleStatus.STARTING
                            || isLiveStartingForCleanup(
                                    null, lifecycle, System.currentTimeMillis()))
                    .map(MockPodLifecycle::podName)
                    .filter(Objects::nonNull)
                    .filter(podName -> !podName.isBlank())
                    .forEach(ownedPods::add);
        } else {
            podState.getPodLifecycles().entrySet().stream()
                    .filter(entry -> entry.getValue().status() != MockLifecycleStatus.STARTING
                            || isLiveStartingForCleanup(
                                    entry.getKey(), entry.getValue(), System.currentTimeMillis()))
                    .map(Map.Entry::getValue)
                    .map(MockPodLifecycle::podName)
                    .filter(Objects::nonNull)
                    .filter(podName -> !podName.isBlank())
                    .forEach(ownedPods::add);
        }

        podList.getItems().forEach(p -> {
            String podName = p.getMetadata().getName();
            boolean isOrphaned = !ownedPods.contains(podName);
            if (isOrphaned) {
                String mockId = p.getMetadata().getLabels() == null
                        ? null
                        : p.getMetadata().getLabels().get(PodFactory.LABEL_MOCK_ID);
                boolean deleted = deletePod(p, mockId);
                if (deleted) {
                    LOG.infof("Orphaned pod '%s' deleted in namespace '%s'.", podName, namespace);
                } else {
                    LOG.warnf("Failed to delete orphaned pod '%s' in namespace '%s'.", podName, namespace);
                }
            }
        });
    }

    private boolean isLiveStartingForCleanup(String mockId, MockPodLifecycle lifecycle,
                                             long nowEpochMillis) {
        if (mockCapacity != null) {
            return mockCapacity.isCurrentReservation(mockId, lifecycle.attemptId());
        }
        if (lifecycle.startedAtEpochMillis() <= 0L) {
            return false;
        }
        long leaseMillis = startupLeaseMillis();
        return nowEpochMillis < lifecycle.startedAtEpochMillis()
                || nowEpochMillis - lifecycle.startedAtEpochMillis() < leaseMillis;
    }

    boolean deletePod(Pod pod, String mockId) {
        if (pod == null || pod.getMetadata() == null) {
            return false;
        }
        return deletePod(pod.getMetadata().getName(), mockId);
    }

    boolean deletePod(MockPodRef pod, String mockId) {
        return deletePod(pod.podName(), mockId);
    }

    boolean deletePod(String podName, String mockId) {
        try {
            String namespace = currentNamespace();
            var podResource = kubernetesClient.pods()
                    .inNamespace(namespace)
                    .withName(podName);
            Pod currentPod = podResource.get();
            if (currentPod == null) {
                return true;
            }
            if (!isOwnedManagedPod(currentPod, mockId)) {
                LOG.warnf("Refusing to delete pod '%s' because it is not owned by mock id '%s'.",
                        podName, mockId);
                return false;
            }
            String podUid = currentPod.getMetadata().getUid();
            if (podUid == null || podUid.isBlank()) {
                LOG.warnf("Refusing to delete pod '%s' because its UID is missing.", podName);
                return false;
            }
            DeleteOptions deleteOptions = new DeleteOptionsBuilder()
                    .withApiVersion("v1")
                    .withKind("DeleteOptions")
                    .withNewPreconditions()
                        .withUid(podUid)
                    .endPreconditions()
                    .build();
            kubernetesClient.raw(podDeletePath(namespace, podName), "DELETE", deleteOptions);
            return waitForPodToBeDeleted(podName, podResource::get);
        } catch (KubernetesClientException failure) {
            if (failure.getCode() == 404) {
                return true;
            }
            LOG.warnf(failure, "Failed while deleting pod '%s'.", podName);
            return false;
        } catch (RuntimeException failure) {
            LOG.warnf(failure, "Failed while deleting pod '%s'.", podName);
            return false;
        }
    }

    private String podDeletePath(String namespace, String podName) {
        return "/api/v1/namespaces/" + Utils.toUrlEncoded(namespace)
                + "/pods/" + Utils.toUrlEncoded(podName);
    }

    private boolean isOwnedManagedPod(Pod pod, String mockId) {
        if (mockId == null || mockId.isBlank()
                || pod == null || pod.getMetadata() == null
                || pod.getMetadata().getLabels() == null) {
            return false;
        }
        Map<String, String> labels = pod.getMetadata().getLabels();
        return PodFactory.APP_NAME_VALUE.equals(labels.get(PodFactory.LABEL_APP_NAME))
                && PodFactory.MANAGED_BY_VALUE.equals(labels.get(PodFactory.LABEL_MANAGED_BY))
                && mockId.equals(labels.get(PodFactory.LABEL_MOCK_ID));
    }

    private boolean waitForPodToBeDeleted(String podName, Supplier<Pod> podLookup) {
        if (podLookup.get() == null) {
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
            if (podLookup.get() == null) {
                return true;
            }
        }
        LOG.warnf("Pod '%s' was still present after the deletion timeout.", podName);
        return false;
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

    public static final class StartQueueFullException extends WebApplicationException {
        StartQueueFullException(String mockId) {
            this(mockId, false);
        }

        StartQueueFullException(String mockId, boolean stateMayHaveChanged) {
            super("Mock start queue is full.", Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .type(MediaType.APPLICATION_JSON_TYPE)
                    .entity(new ApiError(
                            "MOCK_START_QUEUE_FULL",
                            "Mock start queue is full.",
                            true,
                            stateMayHaveChanged,
                            Map.of("mockId", mockId)))
                    .build());
        }
    }

}
