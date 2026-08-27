package com.github.letsrokk.mcp;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.inject.Singleton;
import java.util.function.Supplier;

@Singleton
public final class McpMetrics {

    private final MeterRegistry registry;

    public McpMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public <T> T internalCall(String service, Supplier<T> action) {
        Timer.Sample sample = Timer.start(registry);
        String outcome = "success";
        try {
            return action.get();
        } catch (RuntimeException e) {
            outcome = "error";
            registry.counter("mock_fleet_mcp_internal_errors", "service", service).increment();
            throw e;
        } finally {
            sample.stop(registry.timer("mock_fleet_mcp_internal_duration", "service", service, "outcome", outcome));
        }
    }

    public void targetBlocked() {
        registry.counter("mock_fleet_mcp_target_blocks").increment();
    }

    public void headersRedacted(int count) {
        if (count > 0) {
            registry.counter("mock_fleet_mcp_redactions", "kind", "header").increment(count);
        }
    }
}
