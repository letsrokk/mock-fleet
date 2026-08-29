package com.github.letsrokk;

import io.fabric8.kubernetes.api.model.ConfigMap;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.LinkedHashMap;
import java.util.Map;

@ApplicationScoped
public class WireMockVersionCatalogParser {

    private static final String DEFAULT_VERSION_KEY = "defaultVersion";
    private static final String SELECTABLE_PREFIX = "selectable.";
    private static final String RETAINED_PREFIX = "retained.";

    WireMockVersionCatalog parse(ConfigMap configMap) {
        if (configMap == null || configMap.getData() == null) {
            throw new IllegalArgumentException("WireMock version catalog ConfigMap data is required.");
        }

        Map<String, String> data = configMap.getData();
        WireMockVersion defaultVersion = WireMockVersion.parse(data.get(DEFAULT_VERSION_KEY));
        Map<WireMockVersion, WireMockVersionCatalog.VersionEntry> versions = new LinkedHashMap<>();

        for (Map.Entry<String, String> value : data.entrySet()) {
            if (DEFAULT_VERSION_KEY.equals(value.getKey())) {
                continue;
            }
            boolean selectable;
            String versionText;
            if (value.getKey().startsWith(SELECTABLE_PREFIX)) {
                selectable = true;
                versionText = value.getKey().substring(SELECTABLE_PREFIX.length());
            } else if (value.getKey().startsWith(RETAINED_PREFIX)) {
                selectable = false;
                versionText = value.getKey().substring(RETAINED_PREFIX.length());
            } else {
                throw new IllegalArgumentException("Unknown WireMock version catalog key: " + value.getKey());
            }

            WireMockVersion version = WireMockVersion.parse(versionText);
            WireMockVersion imageVersion = WireMockVersion.parseImage(value.getValue());
            if (!version.equals(imageVersion)) {
                throw new IllegalArgumentException("WireMock catalog image tag must match its version key.");
            }
            WireMockVersionCatalog.VersionEntry previous = versions.putIfAbsent(version,
                    new WireMockVersionCatalog.VersionEntry(version, value.getValue(), selectable));
            if (previous != null) {
                throw new IllegalArgumentException("WireMock catalog versions must occur in exactly one section.");
            }
        }

        WireMockVersionCatalog.VersionEntry defaultEntry = versions.get(defaultVersion);
        if (defaultEntry == null || !defaultEntry.selectable()) {
            throw new IllegalArgumentException("The default WireMock version must be present and selectable.");
        }
        String resourceVersion = configMap.getMetadata() == null
                ? null
                : configMap.getMetadata().getResourceVersion();
        return new WireMockVersionCatalog(defaultVersion, versions, resourceVersion);
    }
}
