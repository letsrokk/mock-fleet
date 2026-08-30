package com.github.letsrokk.updater;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.ConfigMapList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.Resource;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

final class CatalogReconciler {
    private static final String DEFAULT_VERSION = "defaultVersion";
    private static final String SELECTABLE_PREFIX = "selectable.";
    private static final String RETAINED_PREFIX = "retained.";

    private final KubernetesClient kubernetes;
    private final ObjectMapper yaml;

    CatalogReconciler(KubernetesClient kubernetes, ObjectMapper yaml) {
        this.kubernetes = kubernetes;
        this.yaml = yaml;
    }

    void reconcile(String namespace,
                   String catalogName,
                   String baselineName,
                   String userName,
                   String configKey,
                   String repository,
                   CatalogSelection.Selection selection,
                   String defaultConstraint) {
        NonNamespaceOperation<ConfigMap, ConfigMapList, Resource<ConfigMap>> configMaps =
                kubernetes.configMaps().inNamespace(namespace);
        ConfigMap catalog = requireConfigMap(configMaps, catalogName);
        ConfigMap baseline = requireConfigMap(configMaps, baselineName);
        ConfigMap user = requireConfigMap(configMaps, userName);

        Map<String, String> effectiveVersions = configuredVersions(baseline, configKey);
        effectiveVersions.putAll(configuredVersions(user, configKey));
        Set<String> referencedVersions = new TreeSet<>();
        effectiveVersions.values().stream()
                .filter(version -> version != null)
                .forEach(referencedVersions::add);

        Map<String, String> currentData = requireData(catalog, catalogName);
        String currentDefault = validateCatalog(currentData);
        Map<String, String> nextData = buildCatalog(
                currentData, referencedVersions, repository, selection, defaultConstraint, currentDefault);

        ConfigMap update = new ConfigMapBuilder(catalog).withData(nextData).build();
        configMaps.resource(update).update();
    }

    private String validateCatalog(Map<String, String> catalog) {
        String defaultVersion = requireStableVersion(catalog.get(DEFAULT_VERSION), "catalog defaultVersion");
        Set<String> selectable = new TreeSet<>();
        Set<String> retained = new TreeSet<>();
        for (Map.Entry<String, String> entry : catalog.entrySet()) {
            String key = entry.getKey();
            if (DEFAULT_VERSION.equals(key)) {
                continue;
            }
            Set<String> destination;
            String version;
            if (key.startsWith(SELECTABLE_PREFIX)) {
                destination = selectable;
                version = key.substring(SELECTABLE_PREFIX.length());
            } else if (key.startsWith(RETAINED_PREFIX)) {
                destination = retained;
                version = key.substring(RETAINED_PREFIX.length());
            } else {
                throw new IllegalStateException("Unknown WireMock version catalog key: " + key);
            }
            version = requireStableVersion(version, "catalog key " + key);
            tagFromImage(entry.getValue(), version);
            destination.add(version);
        }
        Set<String> duplicateVersions = new TreeSet<>(selectable);
        duplicateVersions.retainAll(retained);
        if (!duplicateVersions.isEmpty()) {
            throw new IllegalStateException(
                    "WireMock catalog versions cannot be both selectable and retained: " + duplicateVersions);
        }
        if (!selectable.contains(defaultVersion)) {
            throw new IllegalStateException("Catalog defaultVersion must have a selectable entry: " + defaultVersion);
        }
        return defaultVersion;
    }

    private Map<String, String> buildCatalog(Map<String, String> currentData,
                                             Set<String> referencedVersions,
                                             String repository,
                                             CatalogSelection.Selection selection,
                                             String defaultConstraint,
                                             String currentDefault) {
        String currentDefaultImage = requireCatalogImage(currentData, currentDefault);
        WireMockTag currentDefaultTag = tagFromImage(currentDefaultImage, currentDefault);
        CatalogSelection.matchesConstraint(defaultConstraint, currentDefault);
        WireMockTag candidate = selection.candidates().stream()
                .filter(tag -> CatalogSelection.matchesConstraint(defaultConstraint, tag.version()))
                .max(WireMockTag.ORDER)
                .orElse(null);
        boolean advancesDefault = candidate != null && WireMockTag.ORDER.compare(candidate, currentDefaultTag) > 0;
        WireMockTag nextDefaultTag = advancesDefault ? candidate : currentDefaultTag;
        String nextDefault = nextDefaultTag.version();

        Map<String, String> selectable = new LinkedHashMap<>(selection.selectable());
        selectable.computeIfAbsent(nextDefault, ignored -> advancesDefault
                ? repository + ":" + nextDefaultTag.imageTag()
                : currentDefaultImage);

        Map<String, String> next = new LinkedHashMap<>();
        next.put(DEFAULT_VERSION, nextDefault);
        selectable.forEach((version, image) -> next.put(SELECTABLE_PREFIX + version, image));
        Map<String, String> retained = new TreeMap<>();
        currentData.forEach((key, image) -> {
            if (key.startsWith(SELECTABLE_PREFIX)) {
                String version = key.substring(SELECTABLE_PREFIX.length());
                if (!selectable.containsKey(version)) {
                    retained.put(version, image);
                }
            }
        });
        for (String version : referencedVersions) {
            if (!selectable.containsKey(version)) {
                retained.put(version, requireCatalogImage(currentData, version));
            }
        }
        retained.forEach((version, image) -> next.put(RETAINED_PREFIX + version, image));
        return next;
    }

    private Map<String, String> configuredVersions(ConfigMap configMap, String key) {
        Map<String, String> data = requireData(configMap, configMap.getMetadata().getName());
        String document = data.get(key);
        if (document == null) {
            throw new IllegalStateException("Required ConfigMap key is missing: " + key);
        }
        try {
            JsonNode root = yaml.readTree(document);
            JsonNode wiremock = requireObject(root, "wiremock config").get("wiremock");
            JsonNode mocks = requireObject(wiremock, "wiremock").get("mocks");
            if (mocks == null || !mocks.isArray()) {
                throw new IllegalStateException("wiremock.mocks must be an array.");
            }
            Map<String, String> versions = new LinkedHashMap<>();
            for (JsonNode mock : mocks) {
                JsonNode row = requireObject(mock, "wiremock.mocks[]");
                JsonNode id = row.get("id");
                if (id == null || !id.isTextual() || id.textValue().isBlank()) {
                    throw new IllegalStateException("wiremock.mocks[].id must be a non-empty string.");
                }
                JsonNode version = row.get("version");
                String configuredVersion = null;
                if (version != null && !version.isNull()) {
                    if (!version.isTextual()) {
                        throw new IllegalStateException("wiremock.mocks[].version must be a string or null.");
                    }
                    configuredVersion = requireStableVersion(version.textValue(), "wiremock.mocks[].version");
                }
                if (versions.containsKey(id.textValue())) {
                    throw new IllegalStateException("wiremock.mocks[] contains a duplicate id: " + id.textValue());
                }
                versions.put(id.textValue(), configuredVersion);
            }
            return versions;
        } catch (JsonProcessingException error) {
            throw new IllegalStateException(
                    "Invalid WireMock YAML in ConfigMap '" + configMap.getMetadata().getName() + "'.", error);
        }
    }

    private JsonNode requireObject(JsonNode node, String path) {
        if (node == null || !node.isObject()) {
            throw new IllegalStateException(path + " must be an object.");
        }
        return node;
    }

    private String requireCatalogImage(Map<String, String> catalog, String version) {
        String image = catalog.get(SELECTABLE_PREFIX + version);
        if (image == null) {
            image = catalog.get(RETAINED_PREFIX + version);
        }
        if (image == null || image.isBlank()) {
            throw new IllegalStateException("Referenced WireMock version is missing from the catalog: " + version);
        }
        return image;
    }

    private WireMockTag tagFromImage(String image, String expectedVersion) {
        if (image == null || image.isBlank() || image.indexOf('@') >= 0
                || image.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalStateException("Catalog image is not an exact full image for version "
                    + expectedVersion + ": " + image);
        }
        int separator = image.lastIndexOf(':');
        WireMockTag tag = separator < 1 || separator == image.length() - 1
                ? null : WireMockTag.parse(image.substring(separator + 1)).orElse(null);
        if (tag == null || !tag.version().equals(expectedVersion)) {
            throw new IllegalStateException("Catalog image does not match version " + expectedVersion + ": " + image);
        }
        return tag;
    }

    private String requireStableVersion(String version, String source) {
        WireMockTag tag = WireMockTag.parse(version).orElse(null);
        if (tag == null || !tag.imageTag().equals(tag.version())) {
            throw invalidVersion(source, version);
        }
        return version;
    }

    private IllegalStateException invalidVersion(String source, String version) {
        return new IllegalStateException(source + " must be an exact stable WireMock 3.x version: " + version);
    }

    private ConfigMap requireConfigMap(
            NonNamespaceOperation<ConfigMap, ConfigMapList, Resource<ConfigMap>> configMaps,
            String name) {
        ConfigMap configMap = configMaps.withName(name).get();
        if (configMap == null) {
            throw new IllegalStateException("Required ConfigMap is missing: " + name);
        }
        return configMap;
    }

    private Map<String, String> requireData(ConfigMap configMap, String name) {
        if (configMap.getData() == null) {
            throw new IllegalStateException("Required ConfigMap has no data: " + name);
        }
        return configMap.getData();
    }
}
