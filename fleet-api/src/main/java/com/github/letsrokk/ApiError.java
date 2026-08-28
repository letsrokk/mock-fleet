package com.github.letsrokk;

import java.util.Map;

public record ApiError(String code, String message, boolean retryable, boolean stateMayHaveChanged,
                       Map<String, Object> details) {

    public ApiError {
        details = details == null ? Map.of() : Map.copyOf(details);
    }
}
