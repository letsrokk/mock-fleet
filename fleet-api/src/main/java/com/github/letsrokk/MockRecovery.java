package com.github.letsrokk;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

@ApplicationScoped
public class MockRecovery {
    private static final Logger LOG = Logger.getLogger(MockRecovery.class);
    private static final String COMPLETE = "complete";

    @Inject
    HazelcastInstance hazelcast;
    @Inject
    KubernetesClient kubernetesClient;
    @Inject
    PodManager podManager;
    @Inject
    PodState podState;
    @Inject
    PodTransitionCoordinator transitions;
    @Inject
    MockFleetConfig config;

    private volatile boolean recovered;
    private volatile boolean initiallySafe;

    void onStartup(@Observes StartupEvent event) {
        recover();
    }

    void recover() {
        IMap<String, Boolean> recovery = hazelcast.getMap(HazelcastMemberConfig.RECOVERY_MAP_NAME);
        recovery.lock(COMPLETE);
        try {
            if (!Boolean.TRUE.equals(recovery.get(COMPLETE))) {
                rebuild();
                recovery.put(COMPLETE, true);
            }
            recovered = true;
        } catch (RuntimeException error) {
            LOG.error("Mock recovery failed; API startup and cleanup remain blocked.", error);
            throw error;
        } finally {
            recovery.unlock(COMPLETE);
        }
    }

    boolean isReady() {
        // Only gate initial admission; migrations must not withdraw all existing endpoints.
        if (recovered && !initiallySafe && hazelcast.getPartitionService().isClusterSafe()) {
            initiallySafe = true;
        }
        return recovered && initiallySafe;
    }

    private void rebuild() {
        long started = System.nanoTime();
        Map<String, Pod> candidates = new LinkedHashMap<>();
        var pods = kubernetesClient.pods().inNamespace(podManager.currentNamespace())
                .withLabel(PodFactory.LABEL_APP_NAME, PodFactory.APP_NAME_VALUE)
                .withLabel(PodFactory.LABEL_MANAGED_BY, PodFactory.MANAGED_BY_VALUE)
                .list();
        Comparator<Pod> preference = Comparator.comparing(podManager::isPodReady)
                .thenComparing(pod -> pod.getMetadata().getCreationTimestamp() == null ? null
                        : Instant.parse(pod.getMetadata().getCreationTimestamp()), Comparator.nullsFirst(Instant::compareTo))
                .thenComparing(pod -> pod.getMetadata().getName());
        for (Pod pod : pods.getItems()) {
            if (!eligible(pod)) {
                continue;
            }
            String mockId = mockId(pod);
            if (!podState.needsRecovery(mockId)) {
                continue;
            }
            candidates.merge(mockId, pod, (left, right) -> {
                Pod selected = preference.compare(left, right) >= 0 ? left : right;
                LOG.warnf("Multiple pods found for mock '%s'; recovering '%s'.", mockId, selected.getMetadata().getName());
                return selected;
            });
        }

        Map<String, Pod> ready = new LinkedHashMap<>();
        Map<String, String> failures = new LinkedHashMap<>();
        Map<String, Pod> failedPods = new LinkedHashMap<>();
        long deadline = System.nanoTime() + config.podCreationTimeout().toNanos();
        while (!candidates.isEmpty()) {
            var iterator = candidates.entrySet().iterator();
            while (iterator.hasNext()) {
                var entry = iterator.next();
                Pod current = currentPod(entry.getValue());
                if (current == null) {
                    iterator.remove();
                } else if (podManager.isPodReady(current)) {
                    ready.put(entry.getKey(), current);
                    iterator.remove();
                } else {
                    String failure = podManager.terminalPodFailure(current).orElse(null);
                    if (failure == null && System.nanoTime() >= deadline) {
                        failure = "Pod did not become Ready before the recovery timeout.";
                    }
                    if (failure != null) {
                        failures.put(entry.getKey(), failure);
                        failedPods.put(entry.getKey(), current);
                        iterator.remove();
                    }
                }
            }
            if (!candidates.isEmpty()) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Mock recovery interrupted.", error);
                }
            }
        }

        long recoveredAt = System.currentTimeMillis();
        int restored = 0;
        for (var entry : ready.entrySet()) {
            String mockId = entry.getKey();
            boolean added = transitions.serialized(mockId, () -> podState.recoverPod(mockId, () -> {
                Pod current = currentPod(entry.getValue());
                if (current != null && !podManager.isPodReady(current)) {
                    throw new IllegalStateException("Pod became unready during recovery: " + current.getMetadata().getName());
                }
                return current == null ? null : podManager.podRef(current, podManager.runtimeVersion(current));
            }, recoveredAt));
            if (added) {
                restored++;
            }
        }
        failures.forEach((mockId, message) -> {
            Pod pod = failedPods.get(mockId);
            podState.recoverFailure(mockId, () -> {
                Pod current = currentPod(pod);
                return current == null ? null : current.getMetadata().getName();
            }, message);
            LOG.warnf("Could not recover mock '%s': %s", mockId, message);
        });
        LOG.infof("Recovered %d active mocks (%d failed) in %dms.", restored, failures.size(),
                (System.nanoTime() - started) / 1_000_000);
    }

    private Pod currentPod(Pod expected) {
        Pod current = kubernetesClient.pods().inNamespace(podManager.currentNamespace())
                .withName(expected.getMetadata().getName()).get();
        return current != null && current.getMetadata() != null
                && Objects.equals(expected.getMetadata().getUid(), current.getMetadata().getUid())
                && current.getMetadata().getDeletionTimestamp() == null
                && podManager.isOwnedManagedPod(current, mockId(expected)) ? current : null;
    }

    private boolean eligible(Pod pod) {
        if (pod == null || pod.getMetadata() == null || pod.getMetadata().getUid() == null
                || pod.getMetadata().getUid().isBlank() || pod.getMetadata().getName() == null
                || pod.getMetadata().getDeletionTimestamp() != null
                || !podManager.isOwnedManagedPod(pod, mockId(pod))) {
            return false;
        }
        try {
            WireMockConfigService.validateMockId(mockId(pod));
        } catch (ApiException error) {
            LOG.warnf("Ignoring pod '%s' with an invalid mock ID.", pod.getMetadata().getName());
            return false;
        }
        String phase = pod.getStatus() == null ? null : pod.getStatus().getPhase();
        return !"Failed".equals(phase) && !"Succeeded".equals(phase);
    }

    private String mockId(Pod pod) {
        return pod.getMetadata().getLabels() == null ? null
                : pod.getMetadata().getLabels().get(PodFactory.LABEL_MOCK_ID);
    }
}
