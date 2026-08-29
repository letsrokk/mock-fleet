package com.github.letsrokk;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record WireMockVersion(int major, int minor, int patch) implements Comparable<WireMockVersion> {

    private static final Pattern IMAGE_TAG = Pattern.compile(
            "^[^\\s@]*[^\\s:@]:(3)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(?:-\\d+)?$");

    public WireMockVersion {
        if (major != 3 || minor < 0 || patch < 0) {
            throw new IllegalArgumentException("Mock Fleet requires an exact WireMock 3.x version.");
        }
    }

    public static WireMockVersion parse(String value) {
        if (value == null || !value.matches("3\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)")) {
            throw new IllegalArgumentException("Mock Fleet requires an exact WireMock 3.x version.");
        }
        String[] components = value.split("\\.");
        return new WireMockVersion(3, Integer.parseInt(components[1]), Integer.parseInt(components[2]));
    }

    public static WireMockVersion parseImage(String image) {
        Matcher matcher = IMAGE_TAG.matcher(image == null ? "" : image);
        if (!matcher.find()) {
            throw new IllegalArgumentException("Mock Fleet requires an exact WireMock 3.x image tag.");
        }
        return new WireMockVersion(3, Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3)));
    }

    @Override
    public int compareTo(WireMockVersion other) {
        int majorComparison = Integer.compare(major, other.major);
        if (majorComparison != 0) {
            return majorComparison;
        }
        int minorComparison = Integer.compare(minor, other.minor);
        return minorComparison != 0 ? minorComparison : Integer.compare(patch, other.patch);
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }
}
