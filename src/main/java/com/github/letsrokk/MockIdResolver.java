package com.github.letsrokk;

import com.github.letsrokk.exceptions.MockIdNotFound;

import java.util.Locale;
import java.util.regex.Pattern;

final class MockIdResolver {

    private static final Pattern VALID_MOCK_ID = Pattern.compile("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$");

    private MockIdResolver() {
    }

    static String extractFromHost(String host) {
        if (host == null || host.isBlank()) {
            throw new MockIdNotFound("Host header is missing or empty.");
        }

        String normalizedHost = host.trim();
        int portSeparator = normalizedHost.indexOf(':');
        if (portSeparator >= 0) {
            normalizedHost = normalizedHost.substring(0, portSeparator);
        }
        if (normalizedHost.isBlank()) {
            throw new MockIdNotFound(String.format("Unable to extract mock id from host '%s'.", host));
        }
        if (!normalizedHost.contains(".")) {
            throw new MockIdNotFound(String.format("Unable to extract mock id from host '%s'.", host));
        }

        String[] parts = normalizedHost.split("\\.");
        return normalize(parts[0], String.format("Unable to extract mock id from host '%s'.", host));
    }

    static ResolvedRequest extractFromPath(String requestUri) {
        if (requestUri == null || requestUri.isBlank()) {
            throw new MockIdNotFound("Request path is missing or empty.");
        }

        int queryStart = requestUri.indexOf('?');
        String path = queryStart >= 0 ? requestUri.substring(0, queryStart) : requestUri;
        String query = queryStart >= 0 ? requestUri.substring(queryStart) : "";

        int segmentStart = 0;
        while (segmentStart < path.length() && path.charAt(segmentStart) == '/') {
            segmentStart++;
        }
        if (segmentStart >= path.length()) {
            throw new MockIdNotFound(String.format("Unable to extract mock id from path '%s'.", path));
        }

        int segmentEnd = segmentStart;
        while (segmentEnd < path.length() && path.charAt(segmentEnd) != '/') {
            segmentEnd++;
        }

        String mockId = normalize(path.substring(segmentStart, segmentEnd),
                String.format("Unable to extract mock id from path '%s'.", path));
        String remainderPath = segmentEnd >= path.length() ? "/" : path.substring(segmentEnd);
        return new ResolvedRequest(mockId, remainderPath + query);
    }

    static String normalize(String candidate, String errorMessage) {
        String normalized = candidate.toLowerCase(Locale.ROOT);
        if (!VALID_MOCK_ID.matcher(normalized).matches()) {
            throw new MockIdNotFound(errorMessage);
        }
        return normalized;
    }
}
