package com.github.letsrokk.updater;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

final class CatalogSelection {
    private CatalogSelection() { }
    static Map<String, String> select(String repository, List<String> tags, int minorLines) {
        if (minorLines < 1 || minorLines > 50) throw new IllegalArgumentException("minorLines must be from 1 to 50.");
        Map<Integer, WireMockTag> latestByMinor = tags.stream().map(WireMockTag::parse).flatMap(java.util.Optional::stream)
                .collect(Collectors.toMap(WireMockTag::minor, tag -> tag,
                        (left, right) -> WireMockTag.ORDER.compare(left, right) >= 0 ? left : right));
        return latestByMinor.values().stream()
                .sorted(WireMockTag.ORDER.reversed()).collect(Collectors.toMap(WireMockTag::version,
                        tag -> repository + ":" + tag.version() + (tag.revision() == 0 ? "" : "-" + tag.revision()),
                        (left, right) -> left, LinkedHashMap::new));
    }
    static boolean matchesConstraint(String constraint, String version) {
        if ("3.x".equals(constraint)) return WireMockTag.parse(version).isPresent();
        if (constraint != null && constraint.matches("3\\.(0|[1-9]\\d*)\\.x"))
            return WireMockTag.parse(version).map(tag -> constraint.equals("3." + tag.minor() + ".x")).orElse(false);
        throw new IllegalArgumentException("defaultVersionConstraint must be 3.x or 3.N.x.");
    }
}
