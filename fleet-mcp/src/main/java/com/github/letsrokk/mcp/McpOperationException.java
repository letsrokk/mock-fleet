package com.github.letsrokk.mcp;

import java.util.Map;

public final class McpOperationException extends RuntimeException {

    private final String code;
    private final boolean retryable;
    private final boolean stateMayHaveChanged;
    private final Map<String, Object> details;

    public McpOperationException(String code, String message, boolean retryable, Map<String, Object> details) {
        this(code, message, retryable, false, details);
    }

    public McpOperationException(String code, String message, boolean retryable, boolean stateMayHaveChanged,
            Map<String, Object> details) {
        super(message);
        this.code = code;
        this.retryable = retryable;
        this.stateMayHaveChanged = stateMayHaveChanged;
        this.details = details == null ? Map.of() : Map.copyOf(details);
    }

    public String code() {
        return code;
    }

    public boolean retryable() {
        return retryable;
    }

    public boolean stateMayHaveChanged() {
        return stateMayHaveChanged;
    }

    public Map<String, Object> details() {
        return details;
    }
}
