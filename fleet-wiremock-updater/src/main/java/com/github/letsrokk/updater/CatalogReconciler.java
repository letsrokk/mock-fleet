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

        Set<String> referencedVersions = new TreeSet<>();
        referencedVersions.addAll(referencedVersions(baseline, configKey));
        referencedVersions.addAll(referencedVersions(user, configKey));

        Map<String, String> currentData = requireData(catalog, catalogName);
        String currentDefault = requireStableVersion(currentData.get(DEFAULT_VERSION), "catalog defaultVersion");
        Map<String, String> nextData = buildCatalog(
                currentData, referencedVersions, repository, selection, defaultConstraint, currentDefault);

        ConfigMap update = new ConfigMapBuilder(catalog).withData(nextData).build();
        configMaps.resource(update).update();
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
        for (String version : referencedVersions) {
            if (!selectable.containsKey(version)) {
                next.put(RETAINED_PREFIX + version, requireCatalogImage(currentData, version));
            }
        }
        return next;
    }

    private Set<String> referencedVersions(ConfigMap configMap, String key) {
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
            Set<String> references = new TreeSet<>();
            for (JsonNode mock : mocks) {
                JsonNode version = requireObject(mock, "wiremock.mocks[]").get("version");
                if (version == null || version.isNull()) {
                    continue;
                }
                if (!version.isTextual()) {
                    throw new IllegalStateException("wiremock.mocks[].version must be a string or null.");
                }
                references.add(requireStableVersion(version.textValue(), "wiremock.mocks[].version"));
            }
            return references;
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
        int separator = image.lastIndexOf(':');
        WireMockTag tag = separator < 0 ? null : WireMockTag.parse(image.substring(separator + 1)).orElse(null);
        if (tag == null || !tag.version().equals(expectedVersion)) {
            throw new IllegalStateException("Catalog image does not match version " + expectedVersion + ": " + image);
        }
        return tag;
    }

    private String requireStableVersion(String version, String source) {
        if (WireMockTag.parse(version).isEmpty()) {
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
