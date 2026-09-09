package com.github.letsrokk;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.ConfigMap;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;
import java.util.Map;

@ApplicationScoped
public class WireMockVersionCatalogParser {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String IMAGE_POLICY_ANNOTATION = "mock-fleet/image-policy";
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

        defaultVersion = applyPolicy(configMap, defaultVersion, versions);
        WireMockVersionCatalog.VersionEntry defaultEntry = versions.get(defaultVersion);
        if (defaultEntry == null || !defaultEntry.selectable()) {
            throw new IllegalArgumentException("The default WireMock version must be present and selectable.");
        }
        String resourceVersion = configMap.getMetadata() == null
                ? null
                : configMap.getMetadata().getResourceVersion();
        return new WireMockVersionCatalog(defaultVersion, versions, resourceVersion);
    }
    private WireMockVersion applyPolicy(ConfigMap configMap, WireMockVersion defaultVersion,
                                       Map<WireMockVersion, WireMockVersionCatalog.VersionEntry> versions) {
        String policyText = configMap.getMetadata() == null || configMap.getMetadata().getAnnotations() == null
                ? null : configMap.getMetadata().getAnnotations().get(IMAGE_POLICY_ANNOTATION);
        if (policyText == null) {
            return defaultVersion;
        }
        JsonNode policy;
        try {
            policy = JSON.readTree(policyText);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid WireMock image policy JSON.", e);
        }
        if (policy == null || !policy.isObject() || !policy.path("defaultImage").isTextual()
                || !policy.path("allowedImages").isArray() || !policy.path("allowedVersionRange").isTextual()) {
            throw new IllegalArgumentException("WireMock image policy requires defaultImage, allowedImages and allowedVersionRange.");
        }
        String fallbackImage = policy.get("defaultImage").textValue();
        WireMockVersion fallbackVersion = WireMockVersion.parseImage(fallbackImage);
        Set<String> allowedImages = new HashSet<>();
        for (JsonNode image : policy.get("allowedImages")) {
            if (!image.isTextual()) {
                throw new IllegalArgumentException("WireMock allowedImages must contain image strings.");
            }
            WireMockVersion.parseImage(image.textValue());
            allowedImages.add(image.textValue());
        }
        String interval = policy.get("allowedVersionRange").textValue();
        if (allowedImages.isEmpty() == interval.isEmpty()) {
            throw new IllegalArgumentException("WireMock image policy requires exactly one allowed-image mode.");
        }
        Predicate<String> allowed;
        if (interval.isEmpty()) {
            allowed = allowedImages::contains;
        } else {
            WireMockAllowedVersionRange range = WireMockAllowedVersionRange.parse(interval);
            allowed = image -> range.contains(WireMockVersion.parseImage(image));
        }
        if (!allowed.test(fallbackImage)) {
            throw new IllegalArgumentException("The configured default WireMock image must satisfy its image policy.");
        }
        versions.replaceAll((version, entry) -> new WireMockVersionCatalog.VersionEntry(
                version, entry.image(), entry.selectable() && allowed.test(entry.image())));
        WireMockVersionCatalog.VersionEntry current = versions.get(defaultVersion);
        if (current != null && allowed.test(current.image())) {
            return defaultVersion;
        }
        WireMockVersionCatalog.VersionEntry fallback = versions.get(fallbackVersion);
        if (fallback == null || !fallback.image().equals(fallbackImage)) {
            throw new IllegalArgumentException("The configured default WireMock image must be present in the catalog.");
        }
        versions.put(fallbackVersion, new WireMockVersionCatalog.VersionEntry(fallbackVersion, fallbackImage, true));
        return fallbackVersion;
    }
}
