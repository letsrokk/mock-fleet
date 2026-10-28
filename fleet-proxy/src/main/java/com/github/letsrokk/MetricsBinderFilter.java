package com.github.letsrokk;

import io.micrometer.core.instrument.config.MeterFilter;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@Singleton
public class MetricsBinderFilter {
    @Produces
    @Singleton
    MeterFilter optionalBinders(
            @ConfigProperty(name = "mock-fleet.metrics.binders.netty") boolean netty,
            @ConfigProperty(name = "mock-fleet.metrics.binders.vertx") boolean vertx,
            @ConfigProperty(name = "mock-fleet.metrics.binders.http") boolean http) {
        return MeterFilter.deny(id -> (!netty && id.getName().startsWith("netty."))
                || (!vertx && id.getName().startsWith("worker.pool."))
                || (!http && (id.getName().startsWith("http.server.") || id.getName().startsWith("http.client."))));
    }
}
