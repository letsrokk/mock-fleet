package com.github.letsrokk;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record WireMockVersionCatalog(WireMockVersion defaultVersion,
                                     Map<WireMockVersion, VersionEntry> versions,
                                     String resourceVersion) {

    public WireMockVersionCatalog {
        if (defaultVersion == null || versions == null) {
            throw new IllegalArgumentException("WireMock version catalog requires a default and versions.");
        }
        versions = Collections.unmodifiableMap(new LinkedHashMap<>(versions));
    }

    public record VersionEntry(WireMockVersion version, String image, boolean selectable) {
        public VersionEntry {
            if (version == null || image == null || image.isBlank()) {
                throw new IllegalArgumentException("WireMock version catalog entries require a version and image.");
            }
        }
    }
}
