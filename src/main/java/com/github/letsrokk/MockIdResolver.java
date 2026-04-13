package com.github.letsrokk;

import com.github.letsrokk.exceptions.MockIdNotFound;

import java.util.Locale;
import java.util.regex.Pattern;

final class MockIdResolver {

    private static final Pattern VALID_MOCK_ID = Pattern.compile("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$");

    private MockIdResolver() {
    }

    static String extractFromHost(String host, String fleetHost) {
        String normalizedHost = normalizeHost(host);
        String normalizedFleetHost = normalizeConfiguredHost(fleetHost);
        String errorMessage = String.format("Unable to extract mock id from host '%s'.", host);

        if (normalizedHost.equals(normalizedFleetHost)) {
            throw new MockIdNotFound(errorMessage);
        }

        String requiredSuffix = "." + normalizedFleetHost;
        if (!normalizedHost.endsWith(requiredSuffix)) {
            throw new MockIdNotFound(errorMessage);
        }

        String candidate = normalizedHost.substring(0, normalizedHost.length() - requiredSuffix.length());
        if (candidate.isBlank() || candidate.contains(".")) {
            throw new MockIdNotFound(errorMessage);
        }

        return normalize(candidate, errorMessage);
    }

    static boolean isFleetHost(String host, String fleetHost) {
        return normalizeHost(host).equals(normalizeConfiguredHost(fleetHost));
    }

    static boolean isFleetSubdomain(String host, String fleetHost) {
        String normalizedHost = normalizeHost(host);
        String normalizedFleetHost = normalizeConfiguredHost(fleetHost);
        return !normalizedHost.equals(normalizedFleetHost)
                && normalizedHost.endsWith("." + normalizedFleetHost);
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

    private static String normalizeHost(String host) {
        if (host == null || host.isBlank()) {
            throw new MockIdNotFound("Host header is missing or empty.");
        }

        String normalizedHost = host.trim().toLowerCase(Locale.ROOT);
        int portSeparator = normalizedHost.indexOf(':');
        if (portSeparator >= 0) {
            normalizedHost = normalizedHost.substring(0, portSeparator);
        }
        if (normalizedHost.isBlank()) {
            throw new MockIdNotFound(String.format("Unable to extract mock id from host '%s'.", host));
        }
        return normalizedHost;
    }

    private static String normalizeConfiguredHost(String fleetHost) {
        if (fleetHost == null || fleetHost.isBlank()) {
            throw new IllegalStateException("mock-fleet.routing.host must not be blank.");
        }
        String normalizedFleetHost = fleetHost.trim().toLowerCase(Locale.ROOT);
        int portSeparator = normalizedFleetHost.indexOf(':');
        if (portSeparator >= 0) {
            normalizedFleetHost = normalizedFleetHost.substring(0, portSeparator);
        }
        if (normalizedFleetHost.isBlank()) {
            throw new IllegalStateException("mock-fleet.routing.host must not be blank.");
        }
        return normalizedFleetHost;
    }

    static String normalize(String candidate, String errorMessage) {
        String normalized = candidate.toLowerCase(Locale.ROOT);
        if (!VALID_MOCK_ID.matcher(normalized).matches()) {
            throw new MockIdNotFound(errorMessage);
        }
        return normalized;
    }
}
