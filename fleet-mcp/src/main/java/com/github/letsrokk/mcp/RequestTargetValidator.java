package com.github.letsrokk.mcp;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class RequestTargetValidator {

    private RequestTargetValidator() {
    }

    public static String requireMockTraffic(String requestTarget) {
        if (requestTarget == null || requestTarget.isBlank() || requestTarget.startsWith("//") || requestTarget.contains("#")) {
            throw new IllegalArgumentException("Request target must be a non-empty relative HTTP path");
        }
        String normalized = requestTarget.startsWith("/") ? requestTarget : "/" + requestTarget;
        try {
            URI parsed = new URI("http://mock-fleet.invalid" + normalized);
            String decodedPath = parsed.getRawPath();
            for (int depth = 0; depth < 4; depth++) {
                decodedPath = URLDecoder.decode(decodedPath.replace("+", "%2B"), StandardCharsets.UTF_8);
                String canonicalPath = canonicalPath(decodedPath).toLowerCase(Locale.ROOT);
                if (isAdminPath(canonicalPath)) {
                    throw new IllegalArgumentException("send_request cannot target WireMock Admin routes");
                }
                if (!decodedPath.contains("%")) {
                    break;
                }
            }
            if (decodedPath.contains("%")) {
                throw new IllegalArgumentException("Request target is too deeply encoded");
            }
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Request target is not a valid relative HTTP path", e);
        }
        return normalized;
    }

    private static String canonicalPath(String path) throws URISyntaxException {
        if (path.contains("\\")) {
            throw new IllegalArgumentException("Request target cannot contain backslashes");
        }
        for (String segment : path.split("/", -1)) {
            if (".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException("Request target cannot contain traversal segments");
            }
        }
        String canonical = new URI(null, null, path, null).normalize().getPath();
        while (canonical.contains("//")) {
            canonical = canonical.replace("//", "/");
        }
        return canonical;
    }

    private static boolean isAdminPath(String path) {
        return path.equals("/__admin") || path.startsWith("/__admin/") || path.startsWith("/__admin;");
    }
}
