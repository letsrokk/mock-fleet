package com.github.letsrokk.mcp;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record WireMockVersion(int major, int minor, int patch) implements Comparable<WireMockVersion> {

    private static final Pattern VERSION = Pattern.compile("(?<![0-9])3\\.(\\d+)\\.(\\d+)(?![0-9])");
    private static final Pattern IMAGE = Pattern.compile(
            "^[^\\s]+:3\\.(\\d+)\\.(\\d+)(?:-\\d+)?(?:@sha256:[a-fA-F0-9]{64})?$");

    public WireMockVersion {
        if (major != 3 || minor < 0 || patch < 0) {
            throw new IllegalArgumentException("Only WireMock 3.x.y versions are supported");
        }
    }

    public static WireMockVersion parse(String value) {
        Objects.requireNonNull(value, "value");
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.equalsIgnoreCase("latest") || normalized.endsWith(":latest")) {
            throw new IllegalArgumentException("WireMock version must be pinned to a 3.x.y tag");
        }
        if (normalized.contains("@sha256:") && !normalized.substring(0, normalized.indexOf("@sha256:")).matches(".*:3\\.\\d+\\.\\d+.*")) {
            throw new IllegalArgumentException("Digest-only WireMock image references are not supported");
        }
        Matcher matcher = VERSION.matcher(normalized);
        if (!matcher.find()) {
            throw new IllegalArgumentException("Unable to parse a supported WireMock 3.x.y version from: " + value);
        }
        return new WireMockVersion(3, Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)));
    }

    public static WireMockVersion parseImage(String value) {
        Objects.requireNonNull(value, "value");
        Matcher matcher = IMAGE.matcher(value.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("WireMock image must use a pinned 3.x.y tag: " + value);
        }
        return new WireMockVersion(3, Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)));
    }

    @Override
    public int compareTo(WireMockVersion other) {
        int minorComparison = Integer.compare(minor, other.minor);
        return minorComparison != 0 ? minorComparison : Integer.compare(patch, other.patch);
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }
}
