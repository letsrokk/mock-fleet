package com.github.letsrokk.mcp;

import java.util.regex.Pattern;

public final class MockIdValidator {

    private static final String DNS_LABEL_PATTERN = "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?";
    private static final Pattern DNS_LABEL = Pattern.compile(DNS_LABEL_PATTERN);

    private MockIdValidator() {
    }

    public static String requireValid(String mockId) {
        if (mockId == null || !DNS_LABEL.matcher(mockId).matches()) {
            throw new IllegalArgumentException("mockId must be a lowercase DNS label of at most 63 characters");
        }
        return mockId;
    }

    public static String pattern() {
        return DNS_LABEL_PATTERN;
    }

    public static int maxLength() {
        return 63;
    }
}
