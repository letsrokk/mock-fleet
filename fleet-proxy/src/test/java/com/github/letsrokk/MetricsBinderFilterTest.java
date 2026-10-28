package com.github.letsrokk;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MetricsBinderFilterTest {
    @Test
    void controlsBinderFamiliesIndependentlyAndPreservesApplicationMetrics() {
        for (boolean netty : new boolean[]{false, true}) {
            for (boolean vertx : new boolean[]{false, true}) {
                for (boolean http : new boolean[]{false, true}) {
                    var registry = new SimpleMeterRegistry();
                    try {
                        registry.config().meterFilter(new MetricsBinderFilter().optionalBinders(netty, vertx, http));
                        registry.counter("netty.allocator.memory.used").increment();
                        registry.counter("worker.pool.completed").increment();
                        registry.counter("http.server.requests").increment();
                        registry.counter("http.client.requests").increment();
                        registry.counter("mock_fleet_start_attempts").increment();
                        assertEquals(netty ? 1 : 0, registry.find("netty.allocator.memory.used").meters().size());
                        assertEquals(vertx ? 1 : 0, registry.find("worker.pool.completed").meters().size());
                        assertEquals(http ? 1 : 0, registry.find("http.server.requests").meters().size());
                        assertEquals(http ? 1 : 0, registry.find("http.client.requests").meters().size());
                        assertEquals(1, registry.get("mock_fleet_start_attempts").counter().count());
                    } finally {
                        registry.close();
                    }
                }
            }
        }
    }
}
