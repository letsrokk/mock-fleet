package com.github.letsrokk;

import io.micrometer.core.instrument.MockClock;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.prometheus.client.CollectorRegistry;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FleetMetricsTest {

    @Test
    void exportsBoundedMockLabelsAndSeparateDurationWithoutBuckets() {
        MockClock clock = new MockClock();
        WireMockOptions options = new WireMockOptions();
        options.load(new ByteArrayInputStream("""
                wiremock:
                  default: {}
                  mocks:
                    - id: mock-a
                    - id: invalid_id
                """.getBytes(StandardCharsets.UTF_8)));
        options.setUserConfig(WireMockConfigDocument.of(List.of(), null,
                Map.of("mock-b", new WireMockPodConfig(List.of(), null))));

        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(
                PrometheusConfig.DEFAULT, new CollectorRegistry(), clock);
        try {
            registry.throwExceptionOnRegistrationFailure();
            FleetMetrics metrics = new FleetMetrics(registry, options);
            for (String mockId : List.of("mock-a", "mock-b", "unconfigured", "invalid_id")) {
                for (String outcome : List.of("success", "error", "cancelled", "rejected")) {
                    var sample = metrics.start();
                    clock.add(Duration.ofSeconds(2));
                    metrics.finishStart(sample, outcome, mockId);
                }
                metrics.startRejected("capacity", mockId);
                metrics.startRejected("queue_full", mockId);
                for (String outcome : List.of("deleted", "already_absent", "error")) {
                    metrics.podDeleted(outcome, mockId);
                }
            }

            for (String label : List.of("mock-a", "mock-b", "unknown")) {
                int expected = "unknown".equals(label) ? 2 : 1;
                for (String outcome : List.of("success", "error", "cancelled", "rejected")) {
                    assertEquals(expected, registry.get("mock_fleet_start_attempts")
                            .tags("outcome", outcome, "mock_id", label).counter().count());
                    if (!"rejected".equals(outcome)) {
                        var timer = registry.get("mock_fleet_start_duration_by_mock")
                                .tags("outcome", outcome, "mock_id", label).timer();
                        assertEquals(expected, timer.count());
                        assertEquals(2 * expected, timer.totalTime(TimeUnit.SECONDS));
                        assertEquals(2, timer.max(TimeUnit.SECONDS));
                    }
                }
                for (String reason : List.of("capacity", "queue_full")) {
                    assertEquals(expected, registry.get("mock_fleet_start_rejections")
                            .tags("reason", reason, "mock_id", label).counter().count());
                }
                for (String outcome : List.of("deleted", "already_absent", "error")) {
                    assertEquals(expected, registry.get("mock_fleet_pod_deletions")
                            .tags("outcome", outcome, "mock_id", label).counter().count());
                }
            }
            for (String outcome : List.of("success", "error", "cancelled")) {
                var aggregate = registry.get("mock_fleet_start_duration").tag("outcome", outcome).timer();
                assertEquals(4, aggregate.count());
                assertEquals(8, aggregate.totalTime(TimeUnit.SECONDS));
            }
            assertTrue(registry.find("mock_fleet_start_duration_by_mock").tag("outcome", "rejected")
                    .timers().isEmpty());

            String scrape = registry.scrape();
            assertFalse(scrape.contains("unconfigured"));
            assertFalse(scrape.contains("invalid_id"));
            assertFalse(scrape.contains("mock_fleet_start_duration_by_mock_seconds_bucket"));
            assertTrue(scrape.contains("mock_fleet_start_duration_seconds_bucket"));
            for (String series : List.of("mock_fleet_start_attempts_total", "mock_fleet_start_rejections_total",
                    "mock_fleet_pod_deletions_total", "mock_fleet_start_duration_by_mock_seconds_count",
                    "mock_fleet_start_duration_by_mock_seconds_sum", "mock_fleet_start_duration_by_mock_seconds_max")) {
                var lines = scrape.lines().filter(line -> line.startsWith(series + "{")).toList();
                assertFalse(lines.isEmpty(), series);
                assertTrue(lines.stream().allMatch(line -> line.contains("mock_id=\"")), series);
            }
            assertTrue(scrape.lines().filter(line -> line.startsWith("mock_fleet_start_duration_seconds"))
                    .noneMatch(line -> line.contains("mock_id=")));

            options.setUserConfig(WireMockConfigDocument.empty());
            metrics.startRejected("capacity", "mock-b");
            assertEquals(3, registry.get("mock_fleet_start_rejections")
                    .tags("reason", "capacity", "mock_id", "unknown").counter().count());
        } finally {
            registry.close();
        }
    }
}
