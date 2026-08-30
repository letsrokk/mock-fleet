package com.github.letsrokk.mcp;

import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Singleton;

@Startup
@Singleton
public final class FleetMcpStartup {

    private final FleetMcpConfig config;

    public FleetMcpStartup(FleetMcpConfig config) {
        this.config = config;
    }

    @PostConstruct
    void validate() {
        if (config.defaultPageSize() < 1 || config.defaultPageSize() > config.maxPageSize()) {
            throw new IllegalArgumentException("defaultPageSize must be between 1 and maxPageSize");
        }
        if (config.maxPageSize() > 200) {
            throw new IllegalArgumentException("maxPageSize cannot exceed 200");
        }
        if (config.maxPayloadBytes() < 1 || config.includedBodyBytes() < 1
                || config.includedBodyBytes() > config.maxPayloadBytes()) {
            throw new IllegalArgumentException("Payload and included-body limits are invalid");
        }
    }
}
