package com.github.letsrokk;

import java.net.URI;
import java.net.URISyntaxException;

record OriginFormRequestTarget(String rawPathAndQuery) {

    OriginFormRequestTarget {
        validate(rawPathAndQuery);
    }

    static OriginFormRequestTarget parse(String requestTarget) {
        return new OriginFormRequestTarget(requestTarget);
    }

    URI appendTo(URI trustedOrigin) {
        if (trustedOrigin == null || trustedOrigin.getScheme() == null || trustedOrigin.getHost() == null) {
            throw new IllegalArgumentException("Trusted origin must contain a scheme and host.");
        }

        try {
            URI origin = new URI(
                    trustedOrigin.getScheme(),
                    null,
                    trustedOrigin.getHost(),
                    trustedOrigin.getPort(),
                    null,
                    null,
                    null);
            return URI.create(origin.toASCIIString() + rawPathAndQuery);
        } catch (URISyntaxException error) {
            throw new IllegalArgumentException("Trusted origin is invalid.", error);
        }
    }

    private static void validate(String requestTarget) {
        if (requestTarget == null || !requestTarget.startsWith("/") || requestTarget.startsWith("//")) {
            throw invalidRequestTarget();
        }
        if (requestTarget.indexOf('#') >= 0 || requestTarget.indexOf('\\') >= 0) {
            throw invalidRequestTarget();
        }

        for (int index = 0; index < requestTarget.length(); index++) {
            if (requestTarget.charAt(index) != '%') {
                continue;
            }
            if (index + 2 >= requestTarget.length()
                    || Character.digit(requestTarget.charAt(index + 1), 16) < 0
                    || Character.digit(requestTarget.charAt(index + 2), 16) < 0) {
                throw invalidRequestTarget();
            }
            if (requestTarget.charAt(index + 1) == '5'
                    && Character.toLowerCase(requestTarget.charAt(index + 2)) == 'c') {
                throw invalidRequestTarget();
            }
            index += 2;
        }

        try {
            URI parsed = new URI(requestTarget);
            if (parsed.isAbsolute()
                    || parsed.getRawAuthority() != null
                    || parsed.getRawUserInfo() != null
                    || parsed.getRawFragment() != null
                    || parsed.getRawPath() == null
                    || !parsed.getRawPath().startsWith("/")) {
                throw invalidRequestTarget();
            }
        } catch (URISyntaxException error) {
            throw new IllegalArgumentException("Request target must be a valid origin-form path and query.", error);
        }
    }

    private static IllegalArgumentException invalidRequestTarget() {
        return new IllegalArgumentException("Request target must be a valid origin-form path and query.");
    }
}
