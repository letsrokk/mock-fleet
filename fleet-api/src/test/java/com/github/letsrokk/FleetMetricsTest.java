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
    void createsMockSeriesOnlyOnUseWithoutResettingExistingValues() {
        WireMockOptions options = new WireMockOptions();
        options.load(new ByteArrayInputStream("""
                wiremock:
                  default: {}
                  mocks:
                    - id: mock-a
                    - id: invalid_id
                """.getBytes(StandardCharsets.UTF_8)));
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try {
            FleetMetrics metrics = new FleetMetrics(registry, options);
            assertNoMockSeries(registry, "mock-a");
            for (String outcome : List.of("success", "error", "cancelled", "rejected")) {
                assertEquals(0, registry.get("mock_fleet_start_attempts")
                        .tags("mock_id", "unknown", "outcome", outcome).counter().count());
            }
            for (String reason : List.of("capacity", "queue_full")) {
                assertEquals(0, registry.get("mock_fleet_start_rejections").tag("reason", reason).counter().count());
            }
            for (String outcome : List.of("deleted", "already_absent", "error")) {
                assertEquals(0, registry.get("mock_fleet_pod_deletions").tag("outcome", outcome).counter().count());
            }
            assertFalse(registry.scrape().contains("invalid_id"));

            metrics.finishStart(metrics.start(), "error", "mock-a");
            options.setUserConfig(WireMockConfigDocument.of(List.of(), null,
                    Map.of("mock-b", new WireMockPodConfig(List.of(), null))));
            assertNoMockSeries(registry, "mock-b");
            assertEquals(1, registry.get("mock_fleet_start_attempts")
                    .tags("mock_id", "mock-a", "outcome", "error").counter().count());

            metrics.finishStart(metrics.start(), "error", "mock-b");
            options.setUserConfig(WireMockConfigDocument.empty());
            assertEquals(1, registry.get("mock_fleet_start_attempts")
                    .tags("mock_id", "mock-b", "outcome", "error").counter().count());
            assertTrue(registry.scrape().contains("mock_id=\"mock-b\""));
            options.setUserConfig(WireMockConfigDocument.of(List.of(), null,
                    Map.of("mock-b", new WireMockPodConfig(List.of(), null))));
            assertEquals(1, registry.get("mock_fleet_start_attempts")
                    .tags("mock_id", "mock-b", "outcome", "error").counter().count());
            options.load(new ByteArrayInputStream("""
                    wiremock:
                      default: {}
                      mocks:
                        - id: mock-c
                    """.getBytes(StandardCharsets.UTF_8)));
            assertNoMockSeries(registry, "mock-c");
        } finally {
            registry.close();
        }
    }

    private void assertNoMockSeries(PrometheusMeterRegistry registry, String mockId) {
        assertFalse(registry.scrape().contains("mock_id=\"" + mockId + "\""));
    }

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
                metrics.startRejected("capacity");
                metrics.startRejected("queue_full");
                for (String outcome : List.of("deleted", "already_absent", "error")) {
                    metrics.podDeleted(outcome);
                }
            }

            for (String label : List.of("mock-a", "mock-b", "unknown")) {
                int expected = "unknown".equals(label) ? 2 : 1;
                for (String outcome : List.of("success", "error", "cancelled", "rejected")) {
                    assertEquals(expected, registry.get("mock_fleet_start_attempts")
                            .tags("outcome", outcome, "mock_id", label).counter().count());
                    if ("success".equals(outcome)) {
                        var timer = registry.get("mock_fleet_start_duration_by_mock")
                                .tags("outcome", outcome, "mock_id", label).timer();
                        assertEquals(expected, timer.count());
                        assertEquals(2 * expected, timer.totalTime(TimeUnit.SECONDS));
                        assertEquals(2, timer.max(TimeUnit.SECONDS));
                    }
                }
            }
            for (String reason : List.of("capacity", "queue_full")) {
                assertEquals(4, registry.get("mock_fleet_start_rejections")
                        .tag("reason", reason).counter().count());
            }
            for (String outcome : List.of("deleted", "already_absent", "error")) {
                assertEquals(4, registry.get("mock_fleet_pod_deletions")
                        .tag("outcome", outcome).counter().count());
            }
            for (String outcome : List.of("success", "error", "cancelled")) {
                var aggregate = registry.get("mock_fleet_start_duration").tag("outcome", outcome).timer();
                assertEquals(4, aggregate.count());
                assertEquals(8, aggregate.totalTime(TimeUnit.SECONDS));
            }
            for (String outcome : List.of("error", "cancelled", "rejected")) {
                assertTrue(registry.find("mock_fleet_start_duration_by_mock").tag("outcome", outcome)
                        .timers().isEmpty());
            }

            String scrape = registry.scrape();
            assertFalse(scrape.contains("unconfigured"));
            assertFalse(scrape.contains("invalid_id"));
            assertFalse(scrape.contains("mock_fleet_start_duration_by_mock_seconds_bucket"));
            assertTrue(scrape.contains("mock_fleet_start_duration_seconds_bucket"));
            for (String series : List.of("mock_fleet_start_attempts_total", "mock_fleet_start_duration_by_mock_seconds_count",
                    "mock_fleet_start_duration_by_mock_seconds_sum", "mock_fleet_start_duration_by_mock_seconds_max")) {
                var lines = scrape.lines().filter(line -> line.startsWith(series + "{")).toList();
                assertFalse(lines.isEmpty(), series);
                assertTrue(lines.stream().allMatch(line -> line.contains("mock_id=\"")), series);
            }
            assertTrue(scrape.lines().filter(line -> line.startsWith("mock_fleet_start_duration_seconds"))
                    .noneMatch(line -> line.contains("mock_id=")));

            for (String series : List.of("mock_fleet_start_rejections_total", "mock_fleet_pod_deletions_total")) {
                var lines = scrape.lines().filter(line -> line.startsWith(series + "{")).toList();
                assertFalse(lines.isEmpty(), series);
                assertTrue(lines.stream().noneMatch(line -> line.contains("mock_id=")), series);
            }
            options.setUserConfig(WireMockConfigDocument.empty());
            metrics.finishStart(metrics.start(), "error", "mock-b");
            assertEquals(3, registry.get("mock_fleet_start_attempts")
                    .tags("outcome", "error", "mock_id", "unknown").counter().count());
        } finally {
            registry.close();
        }
    }
}
