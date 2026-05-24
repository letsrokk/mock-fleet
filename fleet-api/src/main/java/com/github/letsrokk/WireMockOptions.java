package com.github.letsrokk;

import io.fabric8.kubernetes.api.model.ResourceRequirements;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class WireMockOptions {

    private static final Logger LOG = Logger.getLogger(WireMockOptions.class);

    @Inject
    MockFleetConfig config;

    private WireMockConfigDocument baselineConfig = WireMockConfigDocument.empty();
    private WireMockConfigDocument userConfig = WireMockConfigDocument.empty();
    private WireMockConfigDocument effectiveConfig = WireMockConfigDocument.empty();

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
        this.baselineConfig = WireMockConfigDocument.load(input);
        rebuildEffectiveConfig();
    }

    synchronized void setUserConfig(WireMockConfigDocument userConfig) {
        this.userConfig = userConfig == null ? WireMockConfigDocument.empty() : userConfig;
        rebuildEffectiveConfig();
    }

    synchronized WireMockConfigDocument baselineConfig() {
        return baselineConfig;
    }

    synchronized WireMockConfigDocument userConfig() {
        return userConfig;
    }

    synchronized WireMockConfigDocument effectiveConfig() {
        return effectiveConfig;
    }

    public synchronized List<String> optionsFor(String mockId) {
        return effectiveConfig.optionsFor(mockId);
    }

    public synchronized ResourceRequirements resourcesFor(String mockId) {
        return effectiveConfig.resourcesFor(mockId);
    }

    private void rebuildEffectiveConfig() {
        this.effectiveConfig = baselineConfig.merge(userConfig);
    }
}
