package com.github.letsrokk.mockops;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

final class CatalogSelection {
    private CatalogSelection() {
    }

    static Selection select(String repository, List<String> tags, int minorLines, AllowedVersionRange range) {
        if (minorLines < 1 || minorLines > 50) {
            throw new IllegalArgumentException("minorLines must be from 1 to 50.");
        }
        List<WireMockTag> candidates = tags.stream()
                .map(WireMockTag::parse)
                .flatMap(java.util.Optional::stream)
                .filter(range::contains)
                .distinct()
                .sorted(WireMockTag.ORDER.reversed().thenComparing(WireMockTag::imageTag))
                .toList();
        Map<Integer, WireMockTag> latestByMinor = candidates.stream()
                .collect(Collectors.toMap(WireMockTag::minor, tag -> tag,
                        CatalogSelection::newer, LinkedHashMap::new));
        Map<String, String> selectable = latestByMinor.values().stream()
                .sorted(WireMockTag.ORDER.reversed())
                .limit(minorLines)
                .collect(Collectors.toMap(WireMockTag::version,
                        tag -> repository + ":" + tag.imageTag(),
                        (left, right) -> left, LinkedHashMap::new));
        return new Selection(selectable, candidates);
    }

    private static WireMockTag newer(WireMockTag left, WireMockTag right) {
        int comparison = WireMockTag.ORDER.compare(left, right);
        if (comparison != 0) {
            return comparison > 0 ? left : right;
        }
        return left.imageTag().compareTo(right.imageTag()) >= 0 ? left : right;
    }

    record Selection(Map<String, String> selectable, List<WireMockTag> candidates) {
        Selection {
            selectable = Collections.unmodifiableMap(new LinkedHashMap<>(selectable));
            candidates = List.copyOf(candidates);
        }
    }
}
