package com.github.letsrokk.mcp;

import io.quarkiverse.mcp.server.FilterContext;
import io.quarkiverse.mcp.server.ToolFilter;
import io.quarkiverse.mcp.server.ToolManager.ToolInfo;
import jakarta.inject.Singleton;

@Singleton
public final class WireMockToolFilter implements ToolFilter {

    private final WireMockVersion configuredVersion;

    public WireMockToolFilter(FleetMcpConfig config) {
        configuredVersion = WireMockVersion.parseImage(config.wiremockImage());
    }

    @Override
    public boolean test(ToolInfo tool, FilterContext context) {
        return !ToolCapabilityRegistry.isWireMockTool(tool.name())
                || ToolCapabilityRegistry.supports(tool.name(), configuredVersion);
    }
}
