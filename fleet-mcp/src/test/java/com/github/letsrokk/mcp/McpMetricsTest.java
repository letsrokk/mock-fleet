package com.github.letsrokk.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class McpMetricsTest {

    @Test
    void recordsLowCardinalityInternalOutcomesAndSafetyEvents() {
        var registry = new SimpleMeterRegistry();
        var metrics = new McpMetrics(registry);

        assertEquals("ok", metrics.internalCall("api", () -> "ok"));
        assertThrows(IllegalStateException.class,
                () -> metrics.internalCall("proxy", () -> { throw new IllegalStateException("down"); }));
        metrics.targetBlocked();
        metrics.headersRedacted(3);

        assertEquals(1, registry.get("mock_fleet_mcp_internal_duration")
                .tag("service", "api").tag("outcome", "success").timer().count());
        assertEquals(1, registry.get("mock_fleet_mcp_internal_errors")
                .tag("service", "proxy").counter().count());
        assertEquals(1, registry.get("mock_fleet_mcp_target_blocks").counter().count());
        assertEquals(3, registry.get("mock_fleet_mcp_redactions").tag("kind", "header").counter().count());
    }
}
