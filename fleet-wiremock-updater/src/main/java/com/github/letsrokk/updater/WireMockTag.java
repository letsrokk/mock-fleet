package com.github.letsrokk.updater;

import java.util.Comparator;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

record WireMockTag(int minor, int patch, int revision, String imageTag) {
    private static final Pattern STABLE = Pattern.compile("^3\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(?:-(0|[1-9]\\d*))?$");
    static final Comparator<WireMockTag> ORDER = Comparator.comparingInt(WireMockTag::minor)
            .thenComparingInt(WireMockTag::patch).thenComparingInt(WireMockTag::revision);

    static Optional<WireMockTag> parse(String tag) {
        Matcher matcher = STABLE.matcher(tag == null ? "" : tag);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new WireMockTag(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)),
                    matcher.group(3) == null ? 0 : Integer.parseInt(matcher.group(3)), tag));
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    String version() {
        return "3." + minor + "." + patch;
    }
}
