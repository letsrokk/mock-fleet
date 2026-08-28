package com.github.letsrokk.mcp;

import io.quarkiverse.mcp.server.ToolOutputGuardrail;
import jakarta.inject.Singleton;

@Singleton
public final class StructuredToolErrorGuardrail implements ToolOutputGuardrail {

    private final McpMetrics metrics;

    public StructuredToolErrorGuardrail(McpMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    public void apply(ToolOutputContext context) {
        if (context.getResponse().isError() && context.getResponse().structuredContent() == null) {
            metrics.bindingFailure(context.getTool().name());
            String frameworkMessage = context.getResponse().firstContent().asText().text();
            String message = frameworkMessage != null && frameworkMessage.startsWith("Invalid tool arguments:")
                    ? frameworkMessage : "Invalid tool arguments";
            context.setResponse(McpToolExecutor.invalidArgument(message));
        }
    }
}
