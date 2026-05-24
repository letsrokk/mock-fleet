package com.github.letsrokk;

import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirements;
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class WireMockConfigDocument {

    static final String ROOT_KEY = "wiremock";
    static final String DEFAULT_KEY = "default";
    static final String MOCKS_KEY = "mocks";

    private final List<String> defaultOptions;
    private final ResourceRequirements defaultResources;
    private final Map<String, WireMockPodConfig> mockConfigs;

    private WireMockConfigDocument(List<String> defaultOptions, ResourceRequirements defaultResources,
                                   Map<String, WireMockPodConfig> mockConfigs) {
        this.defaultOptions = List.copyOf(defaultOptions);
        this.defaultResources = defaultResources;
        this.mockConfigs = Collections.unmodifiableMap(new LinkedHashMap<>(mockConfigs));
    }

    static WireMockConfigDocument empty() {
        return new WireMockConfigDocument(List.of(), null, Map.of());
    }

    static WireMockConfigDocument of(List<String> defaultOptions, ResourceRequirements defaultResources,
                                     Map<String, WireMockPodConfig> mockConfigs) {
        return new WireMockConfigDocument(defaultOptions, defaultResources, mockConfigs);
    }

    static WireMockConfigDocument load(InputStream input) {
        return fromLoadedYaml(new Yaml().load(input));
    }

    static WireMockConfigDocument load(String yaml) {
        if (yaml == null || yaml.isBlank()) {
            return empty();
        }
        return fromLoadedYaml(new Yaml().load(yaml));
    }

    private static WireMockConfigDocument fromLoadedYaml(Object loaded) {
        if (loaded == null) {
            return empty();
        }

        Map<?, ?> document = requireMap(loaded, "WireMock options config");
        Object wiremockNode = document.get(ROOT_KEY);
        if (wiremockNode == null) {
            throw new IllegalStateException("WireMock options config must contain root key 'wiremock'.");
        }

        Map<?, ?> wiremock = requireMap(wiremockNode, ROOT_KEY);
        Map<?, ?> defaults = requireMap(wiremock.get(DEFAULT_KEY), "wiremock.default");
        List<String> defaultOptions = List.copyOf(optionalStringList(defaults.get("options"), "wiremock.default.options"));
        ResourceRequirements defaultResources = optionalResources(defaults.get("resources"), "wiremock.default.resources");
        Map<String, WireMockPodConfig> mockConfigs = parseMockConfigs(wiremock.get(MOCKS_KEY));
        return new WireMockConfigDocument(defaultOptions, defaultResources, mockConfigs);
    }

    WireMockConfigDocument merge(WireMockConfigDocument overrides) {
        Map<String, WireMockPodConfig> mergedMocks = new LinkedHashMap<>(mockConfigs);
        overrides.mockConfigs.forEach((mockId, override) -> {
            WireMockPodConfig base = mergedMocks.getOrDefault(mockId, new WireMockPodConfig(List.of(), null));
            ResourceRequirements resources = override.resources() == null ? base.resources() : override.resources();
            List<String> options = new ArrayList<>(base.options());
            options.addAll(override.options());
            mergedMocks.put(mockId, new WireMockPodConfig(List.copyOf(options), resources));
        });

        List<String> mergedDefaults = new ArrayList<>(defaultOptions);
        mergedDefaults.addAll(overrides.defaultOptions);
        ResourceRequirements mergedDefaultResources = overrides.defaultResources == null
                ? defaultResources
                : overrides.defaultResources;
        return new WireMockConfigDocument(mergedDefaults, mergedDefaultResources, mergedMocks);
    }

    List<String> optionsFor(String mockId) {
        List<String> options = new ArrayList<>(defaultOptions);
        options.addAll(mockConfigs.getOrDefault(mockId, new WireMockPodConfig(List.of(), null)).options());
        return List.copyOf(options);
    }

    ResourceRequirements resourcesFor(String mockId) {
        WireMockPodConfig mockConfig = mockConfigs.get(mockId);
        if (mockConfig != null && mockConfig.resources() != null) {
            return mockConfig.resources();
        }
        return defaultResources;
    }

    List<String> defaultOptions() {
        return defaultOptions;
    }

    ResourceRequirements defaultResources() {
        return defaultResources;
    }

    Map<String, WireMockPodConfig> mockConfigs() {
        return mockConfigs;
    }

    WireMockConfigDocument withMockConfig(String mockId, WireMockPodConfig mockConfig) {
        Map<String, WireMockPodConfig> nextMocks = new LinkedHashMap<>(mockConfigs);
        nextMocks.put(mockId, mockConfig);
        return new WireMockConfigDocument(defaultOptions, defaultResources, nextMocks);
    }

    WireMockConfigDocument withoutMockConfig(String mockId) {
        Map<String, WireMockPodConfig> nextMocks = new LinkedHashMap<>(mockConfigs);
        nextMocks.remove(mockId);
        return new WireMockConfigDocument(defaultOptions, defaultResources, nextMocks);
    }

    String toYaml() {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setIndent(2);
        return new Yaml(options).dump(toMap());
    }

    Map<String, Object> toMap() {
        Map<String, Object> wiremock = new LinkedHashMap<>();
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("options", defaultOptions);
        if (defaultResources != null) {
            defaults.put("resources", resourcesToMap(defaultResources));
        }
        wiremock.put(DEFAULT_KEY, defaults);

        List<Map<String, Object>> mocks = new ArrayList<>();
        mockConfigs.forEach((mockId, mockConfig) -> {
            Map<String, Object> mock = new LinkedHashMap<>();
            mock.put("id", mockId);
            mock.put("options", mockConfig.options());
            if (mockConfig.resources() != null) {
                mock.put("resources", resourcesToMap(mockConfig.resources()));
            }
            mocks.add(mock);
        });
        wiremock.put(MOCKS_KEY, mocks);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put(ROOT_KEY, wiremock);
        return root;
    }

    static Map<String, Object> resourcesToMap(ResourceRequirements resources) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (resources.getRequests() != null && !resources.getRequests().isEmpty()) {
            result.put("requests", quantitiesToMap(resources.getRequests()));
        }
        if (resources.getLimits() != null && !resources.getLimits().isEmpty()) {
            result.put("limits", quantitiesToMap(resources.getLimits()));
        }
        return result;
    }

    private static Map<String, String> quantitiesToMap(Map<String, Quantity> quantities) {
        Map<String, String> result = new LinkedHashMap<>();
        quantities.forEach((key, value) -> result.put(key, quantityToString(value)));
        return result;
    }

    static String quantityToString(Quantity quantity) {
        if (quantity == null) {
            return null;
        }
        String format = quantity.getFormat();
        return quantity.getAmount() + (format == null ? "" : format);
    }

    private static Map<String, WireMockPodConfig> parseMockConfigs(Object node) {
        if (node == null) {
            return Map.of();
        }

        List<?> mocks = requireList(node, "wiremock.mocks");
        Map<String, WireMockPodConfig> configs = new LinkedHashMap<>();
        for (int index = 0; index < mocks.size(); index++) {
            Map<?, ?> mock = requireMap(mocks.get(index), "wiremock.mocks[" + index + "]");
            String id = requireString(mock.get("id"), "wiremock.mocks[" + index + "].id");
            if (configs.containsKey(id)) {
                throw new IllegalStateException("Duplicate WireMock options entry for mock id '" + id + "'.");
            }
            List<String> options = List.copyOf(optionalStringList(mock.get("options"), "wiremock.mocks[" + index + "].options"));
            ResourceRequirements resources = optionalResources(mock.get("resources"), "wiremock.mocks[" + index + "].resources");
            configs.put(id, new WireMockPodConfig(options, resources));
        }
        return configs;
    }

    private static ResourceRequirements optionalResources(Object node, String field) {
        if (node == null) {
            return null;
        }

        Map<?, ?> resources = requireMap(node, field);
        return new ResourceRequirementsBuilder()
                .withRequests(optionalQuantityMap(resources.get("requests"), field + ".requests"))
                .withLimits(optionalQuantityMap(resources.get("limits"), field + ".limits"))
                .build();
    }

    private static Map<String, Quantity> optionalQuantityMap(Object node, String field) {
        if (node == null) {
            return Map.of();
        }

        Map<?, ?> rawMap = requireMap(node, field);
        Map<String, Quantity> values = new LinkedHashMap<>();
        rawMap.forEach((key, value) ->
                values.put(requireString(key, field + " key"), new Quantity(requireQuantityValue(value, field + "." + key))));
        return values;
    }

    private static String requireQuantityValue(Object node, String field) {
        if (node instanceof String value && !value.isBlank()) {
            return value;
        }
        if (node instanceof Number value) {
            return value.toString();
        }
        throw new IllegalStateException(field + " must be a non-blank string or number.");
    }

    private static List<String> optionalStringList(Object node, String field) {
        if (node == null) {
            return List.of();
        }

        List<?> rawList = requireList(node, field);
        List<String> values = new ArrayList<>(rawList.size());
        for (int index = 0; index < rawList.size(); index++) {
            values.add(requireString(rawList.get(index), field + "[" + index + "]"));
        }
        return values;
    }

    private static Map<?, ?> requireMap(Object node, String field) {
        if (node instanceof Map<?, ?> map) {
            return map;
        }
        throw new IllegalStateException(field + " must be a YAML map.");
    }

    private static List<?> requireList(Object node, String field) {
        if (node instanceof List<?> list) {
            return list;
        }
        throw new IllegalStateException(field + " must be a YAML list.");
    }

    private static String requireString(Object node, String field) {
        if (node instanceof String value && !value.isBlank()) {
            return value;
        }
        throw new IllegalStateException(field + " must be a non-blank string.");
    }
}
