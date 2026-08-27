package com.github.letsrokk.mcp;

import java.util.regex.Pattern;

public final class MockIdValidator {

    private static final Pattern DNS_LABEL = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");

    private MockIdValidator() {
    }

    public static String requireValid(String mockId) {
        if (mockId == null || !DNS_LABEL.matcher(mockId).matches()) {
            throw new IllegalArgumentException("mockId must be a lowercase DNS label of at most 63 characters");
        }
        return mockId;
    }
}
