package com.github.letsrokk.mcp;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Collectors;

public final class BodyFileName {

    private BodyFileName() {
    }

    public static String requireValid(String value) {
        if (value == null || value.isBlank() || value.startsWith("/") || value.contains("\\") || value.contains("//")) {
            throw new IllegalArgumentException("Body file name must be a non-empty relative path");
        }
        for (String segment : value.split("/", -1)) {
            if (segment.isBlank() || ".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException("Body file name cannot contain empty or traversal segments");
            }
        }
        return value;
    }

    public static String toUrlPath(String value) {
        requireValid(value);
        return Arrays.stream(value.split("/"))
                .map(segment -> URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20"))
                .collect(Collectors.joining("/"));
    }
}
