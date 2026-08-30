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
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@ApplicationScoped
public class WireMockOptions {

    private static final Logger LOG = Logger.getLogger(WireMockOptions.class);

    @Inject
    MockFleetConfig config;

    @Inject
    WireMockVersionCatalogService catalogService;

    private WireMockConfigDocument baselineConfig = WireMockConfigDocument.empty();
    private WireMockConfigDocument userConfig = WireMockConfigDocument.empty();
    private WireMockConfigDocument effectiveConfig = WireMockConfigDocument.empty();

    @PostConstruct
    void load() {
        configuredVersion();
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
        return resolveFor(mockId).options();
    }

    public synchronized ResourceRequirements resourcesFor(String mockId) {
        return effectiveConfig.resourcesFor(mockId);
    }

    public synchronized WireMockResolvedConfig resolveFor(String mockId) {
        return resolveFor(mockId, baselineConfig, userConfig, currentCatalog());
    }

    synchronized WireMockResolvedConfig resolveFor(String mockId, WireMockConfigDocument baseline,
                                                    WireMockConfigDocument user,
                                                    WireMockVersionCatalog catalog) {
        WireMockConfigDocument effective = baseline.merge(user);
        WireMockPodConfig effectiveMock = effective.mockConfigs().get(mockId);
        WireMockVersion version = effectiveMock == null || effectiveMock.version() == null
                ? catalog.defaultVersion()
                : parseDesiredVersion(effectiveMock.version());
        WireMockVersionCatalog.VersionEntry entry = catalog.versions().get(version);
        if (entry == null) {
            throw unsupportedVersion(version.toString());
        }

        List<String> effectiveOptions = effective.optionsFor(mockId);
        rejectVersionConflicts(effectiveOptions, version);
        validateSourceOptions(baseline, mockId, version);
        validateSourceOptions(user, mockId, version);
        List<String> normalized = WireMockOptionCatalog.validateAndNormalize(effectiveOptions, version);
        return new WireMockResolvedConfig(version, entry.image(), normalized, effective.resourcesFor(mockId));
    }

    synchronized String explicitVersion(WireMockConfigDocument document, String mockId) {
        WireMockPodConfig mock = document.mockConfigs().get(mockId);
        return mock == null ? null : mock.version();
    }

    synchronized WireMockVersionCatalog catalog() {
        return currentCatalog();
    }

    synchronized WireMockVersion desiredVersionFor(String mockId) {
        return desiredVersionFor(mockId, currentCatalog());
    }

    synchronized WireMockVersion desiredVersionFor(String mockId, WireMockVersionCatalog catalog) {
        WireMockPodConfig effectiveMock = effectiveConfig.mockConfigs().get(mockId);
        WireMockVersion version = effectiveMock == null || effectiveMock.version() == null
                ? catalog.defaultVersion()
                : parseDesiredVersion(effectiveMock.version());
        if (!catalog.versions().containsKey(version)) {
            throw unsupportedVersion(version.toString());
        }
        return version;
    }

    private void rebuildEffectiveConfig() {
        this.effectiveConfig = baselineConfig.merge(userConfig);
    }

    private void validateSourceOptions(WireMockConfigDocument document, String mockId, WireMockVersion version) {
        WireMockOptionCatalog.validateAndNormalize(document.defaultOptions(), version);
        WireMockPodConfig mockConfig = document.mockConfigs().get(mockId);
        if (mockConfig != null) {
            WireMockOptionCatalog.validateAndNormalize(mockConfig.options(), version);
        }
    }

    private void rejectVersionConflicts(List<String> options, WireMockVersion version) {
        Set<String> supported = new LinkedHashSet<>();
        WireMockOptionCatalog.definitions(version).forEach(option -> supported.add(option.name()));
        Set<String> known = new LinkedHashSet<>();
        WireMockOptionCatalog.baseDefinitions().forEach(option -> known.add(option.name()));
        Set<String> conflicts = new LinkedHashSet<>();
        WireMockOptionCatalog.tokenize(options).stream()
                .filter(token -> token.startsWith("--"))
                .map(token -> token.contains("=") ? token.substring(0, token.indexOf('=')) : token)
                .filter(name -> known.contains(name) && !supported.contains(name))
                .forEach(conflicts::add);
        if (!conflicts.isEmpty()) {
            throw ApiException.badRequest("UNSUPPORTED_WIREMOCK_OPTION",
                    "WireMock options are not supported by version " + version + ".",
                    Map.of("version", version.toString(), "options", List.copyOf(conflicts)));
        }
    }

    private WireMockVersion parseDesiredVersion(String value) {
        try {
            return WireMockVersion.parse(value);
        } catch (IllegalArgumentException error) {
            throw unsupportedVersion(value);
        }
    }

    private ApiException unsupportedVersion(String value) {
        return ApiException.badRequest("UNSUPPORTED_WIREMOCK_VERSION",
                "WireMock version is not present in the version catalog.", Map.of("version", value));
    }

    private WireMockVersionCatalog currentCatalog() {
        if (catalogService != null) {
            return catalogService.catalog();
        }
        WireMockVersion version = configuredVersion();
        String configuredImage = config == null ? null : config.wiremockImage();
        String image = configuredImage == null || configuredImage.isBlank()
                ? "wiremock/wiremock:" + version
                : configuredImage;
        return new WireMockVersionCatalog(version, Map.of(version,
                new WireMockVersionCatalog.VersionEntry(version, image, true)), null);
    }

    private WireMockVersion configuredVersion() {
        String image = config == null ? null : config.wiremockImage();
        return image == null || image.isBlank()
                ? new WireMockVersion(3, 13, 2)
                : WireMockVersion.parseImage(image);
    }
}
