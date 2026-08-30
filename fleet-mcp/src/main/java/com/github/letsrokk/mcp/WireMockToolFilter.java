package com.github.letsrokk.mcp;

import io.quarkiverse.mcp.server.FilterContext;
import io.quarkiverse.mcp.server.ToolFilter;
import io.quarkiverse.mcp.server.ToolManager.ToolInfo;
public final class WireMockToolFilter implements ToolFilter {

    @Override
    public boolean test(ToolInfo tool, FilterContext context) {
        return true;
    }
}
