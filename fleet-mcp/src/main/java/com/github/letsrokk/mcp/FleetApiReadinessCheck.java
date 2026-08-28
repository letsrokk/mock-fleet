package com.github.letsrokk.mcp;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

@Readiness
@ApplicationScoped
public final class FleetApiReadinessCheck implements HealthCheck {

    private final DependencyHealthProbe probe;
    private final FleetMcpConfig config;

    @Inject
    public FleetApiReadinessCheck(DependencyHealthProbe probe, FleetMcpConfig config) {
        this.probe = probe;
        this.config = config;
    }

    @Override
    public HealthCheckResponse call() {
        return probe.check("fleet-api", config.apiBaseUrl(), "/__fleet/api/health/ready");
    }
}
