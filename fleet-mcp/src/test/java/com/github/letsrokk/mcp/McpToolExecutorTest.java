package com.github.letsrokk.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import org.junit.jupiter.api.Test;

class McpToolExecutorTest {

    private final McpToolExecutor executor = new McpToolExecutor(new SimpleMeterRegistry());

    @Test
    void returnsTypedStructuredSuccess() {
        var response = executor.execute("list_mocks",
                () -> McpToolExecutor.ToolResult.of("Listed active mocks.", Map.of("count", 2)));

        assertFalse(response.isError());
        assertEquals(Map.of("count", 2), response.structuredContent());
    }

    @Test
    void returnsTypedWorkflowErrorWithoutThrowingProtocolFailure() {
        var response = executor.execute("list_stubs", () -> {
            throw new McpOperationException("UPSTREAM_UNAVAILABLE", "Proxy is unavailable", true,
                    Map.of("service", "proxy"));
        });

        assertTrue(response.isError());
        var envelope = assertInstanceOf(McpToolExecutor.ErrorEnvelope.class, response.structuredContent());
        var error = envelope.error();
        assertEquals("UPSTREAM_UNAVAILABLE", error.code());
        assertEquals("Proxy is unavailable", error.message());
        assertTrue(error.retryable());
        assertFalse(error.stateMayHaveChanged());
        assertEquals(Map.of("service", "proxy"), error.details());
    }

    @Test
    void preservesWhetherAFailedMutationMayHaveChangedState() {
        var response = executor.execute("update_stub", () -> {
            throw new McpOperationException("PERSISTENT_UPDATE_INCOMPLETE", "Recovery is required", true, true,
                    Map.of("stubId", "server-id"));
        });

        var error = assertInstanceOf(McpToolExecutor.ErrorEnvelope.class, response.structuredContent()).error();
        assertTrue(response.isError());
        assertTrue(error.stateMayHaveChanged());
    }
}
