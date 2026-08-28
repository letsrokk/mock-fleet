package com.github.letsrokk.mcp;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.quarkiverse.mcp.server.TextContent;
import io.quarkiverse.mcp.server.ToolResponse;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

@Singleton
public final class McpToolExecutor {

    private final MeterRegistry registry;

    public McpToolExecutor(MeterRegistry registry) {
        this.registry = registry;
    }

    public ToolResponse execute(String toolName, Supplier<ToolResult> action) {
        Timer.Sample sample = Timer.start(registry);
        String outcome = "success";
        try {
            ToolResult result = action.get();
            return new ToolResponse(false, List.of(new TextContent(result.summary())), result.structuredContent(), Map.of());
        } catch (McpOperationException e) {
            outcome = "error";
            return error(e.code(), e.getMessage(), e.retryable(), e.stateMayHaveChanged(), e.details());
        } catch (IllegalArgumentException e) {
            outcome = "error";
            return error("INVALID_ARGUMENT", e.getMessage(), false, false, Map.of());
        } catch (Exception e) {
            outcome = "error";
            return error("INTERNAL_ERROR", "Unexpected MCP tool failure", false, false,
                    Map.of("cause", e.getClass().getSimpleName()));
        } finally {
            sample.stop(registry.timer("mock_fleet_mcp_tool_duration", "tool", toolName, "outcome", outcome));
            registry.counter("mock_fleet_mcp_calls", "tool", toolName, "outcome", outcome).increment();
        }
    }

    private ToolResponse error(String code, String message, boolean retryable, boolean stateMayHaveChanged,
            Map<String, Object> details) {
        ErrorContent error = new ErrorContent(code, message, retryable, stateMayHaveChanged, details);
        return new ToolResponse(true, List.of(new TextContent(message)), new ErrorEnvelope(error), Map.of());
    }

    static ToolResponse invalidArgument(String message) {
        ErrorContent error = new ErrorContent("INVALID_ARGUMENT", message, false, false, Map.of());
        return new ToolResponse(true, List.of(new TextContent(message)), new ErrorEnvelope(error), Map.of());
    }

    public record ToolResult(String summary, Object structuredContent) {
        public static ToolResult of(String summary, Object structuredContent) {
            return new ToolResult(summary, structuredContent);
        }
    }

    public record ErrorEnvelope(ErrorContent error) {
    }

    public record ErrorContent(String code, String message, boolean retryable, boolean stateMayHaveChanged,
            Map<String, Object> details) {
    }
}
