package com.github.letsrokk;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.quarkus.runtime.Startup;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.time.Duration;
import java.util.HashSet;
import java.util.Locale;
import java.util.concurrent.ThreadPoolExecutor;

@Singleton
@Startup
public class FleetMetrics {
    private final MeterRegistry registry;

    public FleetMetrics(MeterRegistry registry) {
        this.registry = registry;
        for (String outcome : new String[]{"success", "error", "rejected", "cancelled"}) {
            registry.counter("mock_fleet_start_attempts", "outcome", outcome);
            if (!"rejected".equals(outcome)) {
                Timer.builder("mock_fleet_start_duration")
                        .description("Accepted startup duration including queue time")
                        .tag("outcome", outcome)
                        .serviceLevelObjectives(Duration.ofMillis(100), Duration.ofMillis(500),
                                Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(5),
                                Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofSeconds(60),
                                Duration.ofSeconds(120))
                        .register(registry);
            }
        }
        for (String reason : new String[]{"capacity", "queue_full"}) {
            registry.counter("mock_fleet_start_rejections", "reason", reason);
        }
        for (String outcome : new String[]{"deleted", "already_absent", "error"}) {
            registry.counter("mock_fleet_pod_deletions", "outcome", outcome);
        }
        registry.counter("mock_fleet_start_reservations_reclaimed");
    }

    @Inject
    void bindState(HazelcastInstance hazelcast, MockFleetConfig config) {
        IMap<String, MockPodRef> pods = hazelcast.getMap(HazelcastMemberConfig.POD_MAP_NAME);
        IMap<String, MockPodLifecycle> lifecycles = hazelcast.getMap(HazelcastMemberConfig.POD_LIFECYCLE_MAP_NAME);
        IMap<String, String> reservations = hazelcast.getMap(MockCapacity.RESERVATION_MAP_NAME);
        for (MockLifecycleStatus state : new MockLifecycleStatus[]{MockLifecycleStatus.RUNNING,
                MockLifecycleStatus.STARTING, MockLifecycleStatus.FAILED}) {
            Gauge.builder("mock_fleet_mocks", () -> {
                var runningIds = pods.keySet();
                long count = state == MockLifecycleStatus.RUNNING ? runningIds.size() : 0;
                return count + lifecycles.entrySet().stream()
                        .filter(entry -> !runningIds.contains(entry.getKey()) && entry.getValue().status() == state)
                        .count();
            }).description("Shared mock lifecycle state; published pods take precedence")
                    .tag("state", state.name().toLowerCase(Locale.ROOT)).register(registry);
        }
        Gauge.builder("mock_fleet_capacity_used", () -> {
            var active = new HashSet<>(reservations.keySet());
            active.addAll(pods.keySet());
            lifecycles.entrySet().stream()
                    .filter(entry -> entry.getValue().status() == MockLifecycleStatus.RUNNING)
                    .forEach(entry -> active.add(entry.getKey()));
            return active.size();
        }).description("Shared capacity occupancy, without reconciling expired reservations").register(registry);
        Gauge.builder("mock_fleet_capacity_limit", config::maxActiveMocks).register(registry);
    }

    void bindExecutor(ThreadPoolExecutor executor) {
        Gauge.builder("mock_fleet_start_queue_depth", executor, e -> e.getQueue().size()).register(registry);
        Gauge.builder("mock_fleet_start_workers_active", executor, ThreadPoolExecutor::getActiveCount).register(registry);
    }

    Timer.Sample start() {
        return Timer.start(registry);
    }

    void finishStart(Timer.Sample sample, String outcome) {
        registry.counter("mock_fleet_start_attempts", "outcome", outcome).increment();
        if (!"rejected".equals(outcome)) {
            sample.stop(registry.timer("mock_fleet_start_duration", "outcome", outcome));
        }
    }

    void startRejected(String reason) {
        registry.counter("mock_fleet_start_rejections", "reason", reason).increment();
    }

    void podDeleted(String outcome) {
        registry.counter("mock_fleet_pod_deletions", "outcome", outcome).increment();
    }

    void reservationReclaimed() {
        registry.counter("mock_fleet_start_reservations_reclaimed").increment();
    }
}
