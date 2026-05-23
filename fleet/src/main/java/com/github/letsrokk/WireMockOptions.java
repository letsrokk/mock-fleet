package com.github.letsrokk;

import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirements;
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class WireMockOptions {

    private static final Logger LOG = Logger.getLogger(WireMockOptions.class);

    @Inject
    MockFleetConfig config;

    private List<String> defaultOptions = List.of();
    private ResourceRequirements defaultResources;
    private Map<String, WireMockPodConfig> mockConfigs = Map.of();

    @PostConstruct
    void load() {
        Optional<String> configPath = config.wiremockConfigPath()
                .map(String::trim)
                .filter(path -> !path.isBlank());

        if (configPath.isEmpty()) {
            LOG.debug("No WireMock options config path configured.");
            return;
        }

        Path path = Path.of(configPath.get());
        try (InputStream input = Files.newInputStream(path)) {
            load(input);
            LOG.infof("Loaded WireMock options config from '%s'.", path);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read WireMock options config from '" + path + "'.", e);
        }
    }

    void load(InputStream input) {
        Object loaded = new Yaml().load(input);
        if (loaded == null) {
            this.defaultOptions = List.of();
            this.defaultResources = null;
            this.mockConfigs = Map.of();
            return;
        }

        Map<?, ?> document = requireMap(loaded, "WireMock options config");
        Object wiremockNode = document.get("wiremock");
        if (wiremockNode == null) {
            throw new IllegalStateException("WireMock options config must contain root key 'wiremock'.");
        }

        Map<?, ?> wiremock = requireMap(wiremockNode, "wiremock");
        Map<?, ?> defaults = requireMap(wiremock.get("default"), "wiremock.default");
        this.defaultOptions = List.copyOf(optionalStringList(defaults.get("options"), "wiremock.default.options"));
        this.defaultResources = optionalResources(defaults.get("resources"), "wiremock.default.resources");
        this.mockConfigs = Collections.unmodifiableMap(parseMockConfigs(wiremock.get("mocks")));
    }

    public List<String> optionsFor(String mockId) {
        List<String> options = new ArrayList<>(defaultOptions);
        options.addAll(mockConfigs.getOrDefault(mockId, new WireMockPodConfig(List.of(), null)).options());
        return List.copyOf(options);
    }

    public ResourceRequirements resourcesFor(String mockId) {
        WireMockPodConfig mockConfig = mockConfigs.get(mockId);
        if (mockConfig != null && mockConfig.resources() != null) {
            return mockConfig.resources();
        }
        return defaultResources;
    }

    private Map<String, WireMockPodConfig> parseMockConfigs(Object node) {
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

    private ResourceRequirements optionalResources(Object node, String field) {
        if (node == null) {
            return null;
        }

        Map<?, ?> resources = requireMap(node, field);
        return new ResourceRequirementsBuilder()
                .withRequests(optionalQuantityMap(resources.get("requests"), field + ".requests"))
                .withLimits(optionalQuantityMap(resources.get("limits"), field + ".limits"))
                .build();
    }

    private Map<String, Quantity> optionalQuantityMap(Object node, String field) {
        if (node == null) {
            return Map.of();
        }

        Map<?, ?> rawMap = requireMap(node, field);
        Map<String, Quantity> values = new LinkedHashMap<>();
        rawMap.forEach((key, value) ->
                values.put(requireString(key, field + " key"), new Quantity(requireQuantityValue(value, field + "." + key))));
        return values;
    }

    private String requireQuantityValue(Object node, String field) {
        if (node instanceof String value && !value.isBlank()) {
            return value;
        }
        if (node instanceof Number value) {
            return value.toString();
        }
        throw new IllegalStateException(field + " must be a non-blank string or number.");
    }

    private List<String> optionalStringList(Object node, String field) {
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

    private Map<?, ?> requireMap(Object node, String field) {
        if (node instanceof Map<?, ?> map) {
            return map;
        }
        throw new IllegalStateException(field + " must be a YAML map.");
    }

    private List<?> requireList(Object node, String field) {
        if (node instanceof List<?> list) {
            return list;
        }
        throw new IllegalStateException(field + " must be a YAML list.");
    }

    private String requireString(Object node, String field) {
        if (node instanceof String value && !value.isBlank()) {
            return value;
        }
        throw new IllegalStateException(field + " must be a non-blank string.");
    }
}
