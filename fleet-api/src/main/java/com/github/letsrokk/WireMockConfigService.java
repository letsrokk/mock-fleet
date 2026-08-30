package com.github.letsrokk;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirements;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import io.quarkus.runtime.Startup;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Startup
@ApplicationScoped
public class WireMockConfigService {

    private static final Logger LOG = Logger.getLogger(WireMockConfigService.class);
    private static final Pattern VALID_MOCK_ID = Pattern.compile("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$");
    static final String MOCK_ID_VALIDATION_MESSAGE = "Mock id must contain 1-63 lowercase letters, numbers, or hyphens, and must start and end with a letter or number.";
    private static final String RESOURCE_POLICY_ANNOTATION = "helm.sh/resource-policy";
    private static final String RESOURCE_POLICY_KEEP = "keep";
    private static final long MAX_WATCH_RESTART_DELAY_SECONDS = 30;

    @Inject
    MockFleetConfig config;

    @Inject
    KubernetesClient kubernetesClient;

    @Inject
    WireMockOptions wireMockOptions;

    @Inject
    PodManager podManager;

    @Inject
    WireMockResourcePolicy resourcePolicy;

    private volatile Watch userConfigWatch;
    private final ScheduledExecutorService userConfigWatchExecutor = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "wiremock-user-config-watch");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean shuttingDown;
    private int userConfigWatchRestartAttempts;

    @PostConstruct
    void loadUserConfig() {
        ConfigMap configMap = userConfigMap();
        wireMockOptions.setUserConfig(loadUserConfig(configMap));
        startUserConfigWatch();
    }

    @PreDestroy
    void closeWatch() {
        shuttingDown = true;
        Watch local = userConfigWatch;
        if (local != null) {
            local.close();
        }
        userConfigWatchExecutor.shutdownNow();
    }

    ConfigView view() {
        ConfigMap userConfigMap = userConfigMap();
        WireMockConfigDocument userConfig = loadUserConfig(userConfigMap);
        wireMockOptions.setUserConfig(userConfig);
        WireMockConfigDocument baseline = wireMockOptions.baselineConfig();
        WireMockConfigDocument effective = wireMockOptions.effectiveConfig();

        Set<String> mockIds = new LinkedHashSet<>();
        mockIds.addAll(baseline.mockConfigs().keySet());
        mockIds.addAll(userConfig.mockConfigs().keySet());
        List<PodManager.MockPodStatus> podStatuses = podManager.listMocks();
        podStatuses.stream()
                .map(PodManager.MockPodStatus::mockId)
                .sorted()
                .forEach(mockIds::add);

        Map<String, PodManager.MockPodStatus> statuses = new LinkedHashMap<>();
        podStatuses.forEach(status -> statuses.put(status.mockId(), status));
        List<String> savedMockIds = userConfig.mockConfigs().keySet().stream()
                .sorted()
                .toList();
        WireMockVersionCatalog versionCatalog = wireMockOptions.catalog();

        List<MockConfigView> mocks = mockIds.stream()
                .sorted()
                .map(mockId -> mockConfigView(mockId, baseline, userConfig, effective,
                        statuses.getOrDefault(mockId, new PodManager.MockPodStatus(
                                mockId, null, MockLifecycleStatus.STOPPED, null)), versionCatalog))
                .toList();

        return new ConfigView(
                resourceVersion(userConfigMap),
                List.copyOf(mockIds.stream().sorted().toList()),
                savedMockIds,
                mocks,
                wireMockView(versionCatalog),
                routingView(),
                versionCatalog.defaultVersion().toString(),
                versionCatalog.versions().values().stream()
                        .map(entry -> new VersionView(entry.version().toString(), entry.image(), entry.selectable()))
                        .toList(),
                versionCatalog.resourceVersion());
    }

    private RoutingView routingView() {
        MockFleetConfig.RoutingConfig routing = config.proxy().routing();
        return new RoutingView(routing.mode().name(), routing.host());
    }

    ConfigMutationResult upsertMockConfig(String mockId, ConfigUpdateRequest request) {
        validateMockId(mockId);
        if (request == null) {
            throw ApiException.badRequest("INVALID_REQUEST", "Config update request body is required.", Map.of());
        }
        ApplyMode applyMode = ApplyMode.from(request.applyMode());
        WireMockVersionCatalog catalog = wireMockOptions.catalog();
        WireMockPodConfig mockConfig = new WireMockPodConfig(
                request.options() == null ? List.of() : request.options(),
                toResources(mockId, request.resources()), request.wireMockVersion());
        updateUserConfigMap(mockId, "saved", request.resourceVersion(), current -> {
            validateVersionSelection(mockId, request.wireMockVersion(), current, catalog);
            WireMockConfigDocument candidate = current.withMockConfig(mockId, mockConfig);
            WireMockResolvedConfig resolved = wireMockOptions.resolveFor(
                    mockId, wireMockOptions.baselineConfig(), candidate, catalog);
            List<String> normalizedOverride = WireMockOptionCatalog.validateAndNormalize(
                    mockConfig.options(), resolved.version());
            return current.withMockConfig(mockId,
                    new WireMockPodConfig(normalizedOverride, mockConfig.resources(), request.wireMockVersion()));
        });
        refreshUserConfig();
        MockLifecycleStatus lifecycle = applyMode.apply(mockId, podManager);
        return new ConfigMutationResult(view(), new ApplyResult(mockId, applyMode.wireValue, lifecycle));
    }

    ConfigMutationResult deleteMockConfig(String mockId, ConfigUpdateRequest request) {
        validateMockId(mockId);
        ApplyMode applyMode = ApplyMode.from(request == null ? null : request.applyMode());
        updateUserConfigMap(mockId, "deleted", request == null ? null : request.resourceVersion(), current ->
                current.withoutMockConfig(mockId));
        refreshUserConfig();
        MockLifecycleStatus lifecycle = applyMode.apply(mockId, podManager);
        return new ConfigMutationResult(view(), new ApplyResult(mockId, applyMode.wireValue, lifecycle));
    }

    void refreshUserConfig() {
        ConfigMap configMap = userConfigMap();
        wireMockOptions.setUserConfig(loadUserConfig(configMap));
    }

    synchronized void startUserConfigWatch() {
        if (shuttingDown) {
            return;
        }
        Optional<String> name = userConfigMapName();
        if (name.isEmpty()) {
            return;
        }

        try {
            userConfigWatch = kubernetesClient.configMaps()
                    .inNamespace(currentNamespace())
                    .withName(name.get())
                    .watch(userConfigWatcher());
            userConfigWatchRestartAttempts = 0;
        } catch (RuntimeException error) {
            LOG.warnf(error, "Failed to start WireMock user ConfigMap watch.");
            scheduleUserConfigWatchRestart();
        }
    }

    Watcher<ConfigMap> userConfigWatcher() {
        return new Watcher<>() {
            @Override
            public void eventReceived(Action action, ConfigMap resource) {
                handleUserConfigWatchEvent(action, resource);
            }

            @Override
            public void onClose(WatcherException cause) {
                userConfigWatch = null;
                if (cause != null) {
                    LOG.warnf(cause, "WireMock user ConfigMap watch closed with an error.");
                } else {
                    LOG.debug("WireMock user ConfigMap watch closed.");
                }
                scheduleUserConfigWatchRestart();
            }
        };
    }

    void handleUserConfigWatchEvent(Watcher.Action action, ConfigMap resource) {
        if (action == Watcher.Action.ERROR) {
            return;
        }
        wireMockOptions.setUserConfig(action == Watcher.Action.DELETED
                ? WireMockConfigDocument.empty()
                : loadUserConfig(resource));
    }

    synchronized void scheduleUserConfigWatchRestart() {
        if (shuttingDown) {
            return;
        }

        int attempt = Math.min(userConfigWatchRestartAttempts++, 5);
        long delaySeconds = Math.min(MAX_WATCH_RESTART_DELAY_SECONDS, 1L << attempt);
        userConfigWatchExecutor.schedule(this::startUserConfigWatch, delaySeconds, TimeUnit.SECONDS);
    }

    private MockConfigView mockConfigView(String mockId, WireMockConfigDocument baseline,
                                          WireMockConfigDocument userConfig,
                                          WireMockConfigDocument effective,
                                          PodManager.MockPodStatus status,
                                          WireMockVersionCatalog catalog) {
        String desiredVersion = wireMockOptions.desiredVersionFor(mockId, catalog).toString();
        return new MockConfigView(
                mockId,
                status.status(),
                configData(wireMockOptions.explicitVersion(baseline, mockId),
                        baseline.optionsFor(mockId), baseline.resourcesFor(mockId)),
                userConfigData(userConfig.mockConfigs().getOrDefault(mockId, new WireMockPodConfig(List.of(), null))),
                configData(desiredVersion, effective.optionsFor(mockId), effective.resourcesFor(mockId)),
                desiredVersion, status.runtimeVersion());
    }

    private ConfigData userConfigData(WireMockPodConfig config) {
        return new ConfigData(config.version(), WireMockOptionCatalog.redactSensitive(config.options()),
                config.resources() == null ? null : ResourceData.from(config.resources()));
    }

    private ConfigData configData(String version, List<String> options, ResourceRequirements resources) {
        return new ConfigData(version, WireMockOptionCatalog.redactSensitive(options), ResourceData.from(resources));
    }

    private ConfigMap userConfigMap() {
        Optional<String> name = userConfigMapName();
        if (name.isEmpty()) {
            return null;
        }
        String namespace = currentNamespace();
        ConfigMap configMap = kubernetesClient.configMaps()
                .inNamespace(namespace)
                .withName(name.get())
                .get();
        if (configMap == null) {
            throw configUnavailable(namespace, name.get());
        }
        return configMap;
    }

    private WireMockConfigDocument loadUserConfig(ConfigMap configMap) {
        if (configMap == null || configMap.getData() == null) {
            return WireMockConfigDocument.empty();
        }
        return WireMockConfigDocument.load(configMap.getData().get(config.wiremockConfigKey()));
    }

    private void updateUserConfigMap(String mockId,
                                     String action,
                                     String expectedResourceVersion,
                                     java.util.function.Function<WireMockConfigDocument, WireMockConfigDocument> update) {
        String name = userConfigMapName().orElseThrow(() -> new ApiException(
                Response.Status.SERVICE_UNAVAILABLE,
                new ApiError("CONFIG_UNAVAILABLE", "User editable WireMock ConfigMap is not configured.",
                        true, false, Map.of())));
        String namespace = currentNamespace();
        ConfigMap current = kubernetesClient.configMaps()
                .inNamespace(namespace)
                .withName(name)
                .get();
        if (current == null) {
            throw configUnavailable(namespace, name);
        }

        if (expectedResourceVersion != null
                && !Objects.equals(expectedResourceVersion, resourceVersion(current))) {
            throw configConflict(namespace, name, mockId, expectedResourceVersion, current);
        }

        LOG.infof("Persisting WireMock user ConfigMap namespace=%s name=%s mockId=%s action=%s.",
                namespace, name, mockId, action);
        WireMockConfigDocument currentConfig = loadUserConfig(current);
        WireMockConfigDocument nextConfig = update.apply(currentConfig);
        ConfigMap persisted;
        try {
            persisted = persistUserConfigMap(namespace, name, current, nextConfig);
        } catch (KubernetesClientException error) {
            if (error.getCode() != Response.Status.CONFLICT.getStatusCode()) {
                throw error;
            }
            ConfigMap latest = kubernetesClient.configMaps()
                    .inNamespace(namespace)
                    .withName(name)
                    .get();
            throw configConflict(namespace, name, mockId, expectedResourceVersion, latest);
        }
        LOG.infof("Persisted WireMock user ConfigMap namespace=%s name=%s mockId=%s action=%s resourceVersion=%s.",
                namespace, name, mockId, action, resourceVersion(persisted));
    }

    private ApiException configConflict(String namespace, String name, String mockId,
                                        String expectedResourceVersion, ConfigMap current) {
        String currentResourceVersion = resourceVersion(current);
        LOG.warnf("WireMock user ConfigMap conflict namespace=%s name=%s mockId=%s expectedResourceVersion=%s actualResourceVersion=%s.",
                namespace, name, mockId, expectedResourceVersion, currentResourceVersion);
        return new ApiException(Response.Status.CONFLICT,
                new ApiError("CONFIG_CONFLICT", "WireMock config was modified by another writer.",
                        true, false, Map.of(
                        "expectedVersion", expectedResourceVersion == null ? "" : expectedResourceVersion,
                        "currentVersion", currentResourceVersion == null ? "" : currentResourceVersion)));
    }

    private ConfigMap persistUserConfigMap(String namespace, String name, ConfigMap current,
                                           WireMockConfigDocument document) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put(config.wiremockConfigKey(), document.toYaml());
        ConfigMapBuilder builder = new ConfigMapBuilder(current)
                .editOrNewMetadata()
                .withName(name)
                .withNamespace(namespace)
                .addToAnnotations(RESOURCE_POLICY_ANNOTATION, RESOURCE_POLICY_KEEP)
                .addToLabels("app.kubernetes.io/name", "mock-fleet")
                .addToLabels("app.kubernetes.io/managed-by", "mock-fleet")
                .endMetadata()
                .withData(data);
        ConfigMap next = builder.build();
        return kubernetesClient.configMaps().inNamespace(namespace).resource(next).update();
    }

    private ApiException configUnavailable(String namespace, String name) {
        return new ApiException(Response.Status.SERVICE_UNAVAILABLE,
                new ApiError("CONFIG_UNAVAILABLE", "User editable WireMock ConfigMap is missing.",
                        true, false, Map.of("namespace", namespace, "name", name)));
    }

    private String currentNamespace() {
        String clientNamespace = kubernetesClient.getNamespace();
        if (clientNamespace != null && !clientNamespace.isBlank()) {
            return clientNamespace;
        }

        String configuredNamespace = config.namespace();
        if (configuredNamespace != null && !configuredNamespace.isBlank()) {
            return configuredNamespace;
        }

        return "mock-fleet";
    }

    private Optional<String> userConfigMapName() {
        return config.wiremockUserConfigMapName()
                .map(String::trim)
                .filter(value -> !value.isBlank());
    }

    private String resourceVersion(ConfigMap configMap) {
        return configMap == null || configMap.getMetadata() == null
                ? null
                : configMap.getMetadata().getResourceVersion();
    }

    static void validateMockId(String mockId) {
        if (mockId == null || !VALID_MOCK_ID.matcher(mockId).matches()) {
            throw ApiException.badRequest("INVALID_MOCK_ID", MOCK_ID_VALIDATION_MESSAGE,
                    Map.of("mockId", mockId == null ? "" : mockId));
        }
    }

    void validateVersionSelection(String mockId, String requestedVersion,
                                  WireMockConfigDocument currentUser,
                                  WireMockVersionCatalog catalog) {
        if (requestedVersion == null) {
            return;
        }
        WireMockVersion version;
        try {
            version = WireMockVersion.parse(requestedVersion);
        } catch (IllegalArgumentException error) {
            throw unsupportedVersion(requestedVersion);
        }
        WireMockVersionCatalog.VersionEntry entry = catalog.versions().get(version);
        boolean alreadyReferenced = requestedVersion.equals(
                wireMockOptions.explicitVersion(wireMockOptions.baselineConfig(), mockId))
                || requestedVersion.equals(wireMockOptions.explicitVersion(currentUser, mockId));
        if (entry == null || (!entry.selectable() && !alreadyReferenced)) {
            throw unsupportedVersion(requestedVersion);
        }
    }

    private ApiException unsupportedVersion(String requestedVersion) {
        return ApiException.badRequest("UNSUPPORTED_WIREMOCK_VERSION",
                "WireMock version is not selectable for this mock.",
                Map.of("version", requestedVersion == null ? "" : requestedVersion));
    }

    private ResourceRequirements toResources(String mockId, ResourceData resources) {
        ResourceRequirements baseline = wireMockOptions.baselineConfig().resourcesFor(mockId);
        if (resources == null) {
            resourcePolicy.validateEffective(baseline);
            return null;
        }
        return resourcePolicy.normalizeAndValidate(baseline, resources);
    }

    OptionCatalogView optionCatalog(String requestedVersion) {
        WireMockOptionMatrix.ResolvedCatalog catalog;
        try {
            WireMockVersion version = requestedVersion == null || requestedVersion.isBlank()
                    ? wireMockOptions.catalog().defaultVersion()
                    : WireMockVersion.parse(requestedVersion);
            catalog = WireMockOptionMatrix.loadDefault().resolve(version);
        } catch (IllegalArgumentException exception) {
            throw ApiException.badRequest("INVALID_WIREMOCK_VERSION",
                    "WireMock version must be an exact WireMock 3.x semantic version.",
                    Map.of("version", requestedVersion == null ? "" : requestedVersion));
        }
        return new OptionCatalogView(catalog.version().toString(), catalog.rangeStatus(), catalog.options().stream()
                .filter(option -> !WireMockOptionCatalog.isSensitive(option.name()))
                .map(PublicOptionDefinition::from)
                .toList());
    }

    private WireMockVersionView wireMockView(WireMockVersionCatalog versionCatalog) {
        WireMockOptionMatrix.ResolvedCatalog catalog = WireMockOptionMatrix.loadDefault()
                .resolve(versionCatalog.defaultVersion());
        return new WireMockVersionView(
                versionCatalog.versions().get(versionCatalog.defaultVersion()).image(),
                catalog.version().toString(),
                catalog.minimumSupportedVersion().toString(),
                catalog.maximumResearchedVersion().toString(),
                catalog.rangeStatus());
    }

    public record ConfigView(String resourceVersion, List<String> mockIds, List<String> savedMockIds,
                             List<MockConfigView> mocks, WireMockVersionView wireMock, RoutingView routing,
                             String defaultVersion, List<VersionView> versions, String catalogResourceVersion) {
        public ConfigView(String resourceVersion, List<String> mockIds, List<String> savedMockIds,
                          List<MockConfigView> mocks, WireMockVersionView wireMock, RoutingView routing) {
            this(resourceVersion, mockIds, savedMockIds, mocks, wireMock, routing,
                    wireMock.version(), List.of(), null);
        }
    }

    public record OptionCatalogView(String wireMockVersion, String catalogStatus,
                                    List<PublicOptionDefinition> options) {
    }

    public record PublicOptionDefinition(String name, String label, String kind, String group, String description,
                                         List<String> values, Integer minimum, Integer maximum) {
        private static PublicOptionDefinition from(WireMockOptionCatalog.OptionDefinition option) {
            return new PublicOptionDefinition(option.name(), option.label(), option.kind(), option.group(),
                    option.description(), option.values(), option.minimum(), option.maximum());
        }
    }

    public record WireMockVersionView(String configuredImage, String version, String minimumSupportedVersion,
                                      String maximumResearchedVersion, String rangeStatus) {
    }

    public record MockConfigView(String mockId, MockLifecycleStatus lifecycle, ConfigData baseline, ConfigData user,
                                 ConfigData effective, String wireMockVersion, String runtimeVersion) {
        public MockConfigView(String mockId, MockLifecycleStatus lifecycle, ConfigData baseline, ConfigData user,
                              ConfigData effective) {
            this(mockId, lifecycle, baseline, user, effective, effective.version(), null);
        }
    }

    public record ConfigData(String version, List<String> options, ResourceData resources) {
        public ConfigData(List<String> options, ResourceData resources) {
            this(null, options, resources);
        }
    }

    public record VersionView(String version, String image, boolean selectable) {
    }

    enum ApplyMode {
        FUTURE_ONLY("futureOnly") {
            @Override
            MockLifecycleStatus apply(String mockId, PodManager podManager) {
                return podManager.status(mockId).status();
            }
        },
        RESTART_ACTIVE("restartActive") {
            @Override
            MockLifecycleStatus apply(String mockId, PodManager podManager) {
                return podManager.restartActive(mockId).status();
            }
        };

        private final String wireValue;

        ApplyMode(String wireValue) {
            this.wireValue = wireValue;
        }

        abstract MockLifecycleStatus apply(String mockId, PodManager podManager);

        static ApplyMode from(String value) {
            if (value == null || value.isBlank() || FUTURE_ONLY.wireValue.equals(value)) {
                return FUTURE_ONLY;
            }
            if (RESTART_ACTIVE.wireValue.equals(value)) {
                return RESTART_ACTIVE;
            }
            throw ApiException.badRequest("INVALID_APPLY_MODE", "Unsupported config apply mode: " + value,
                    Map.of("applyMode", value));
        }
    }

    public record ConfigUpdateRequest(String resourceVersion, String wireMockVersion, List<String> options,
                                      ResourceData resources, String applyMode) {
        public ConfigUpdateRequest(String resourceVersion, List<String> options, ResourceData resources,
                                   String applyMode) {
            this(resourceVersion, null, options, resources, applyMode);
        }
    }

    public record ConfigMutationResult(ConfigView config, ApplyResult apply) {
    }

    public record ApplyResult(String mockId, String mode, MockLifecycleStatus lifecycle) {
    }

    public record ResourceData(Map<String, String> requests, Map<String, String> limits) {
        static ResourceData from(ResourceRequirements resources) {
            if (resources == null) {
                return new ResourceData(Map.of(), Map.of());
            }
            return new ResourceData(toStringMap(resources.getRequests()), toStringMap(resources.getLimits()));
        }

        private static Map<String, String> toStringMap(Map<String, Quantity> quantities) {
            if (quantities == null || quantities.isEmpty()) {
                return Map.of();
            }
            Map<String, String> result = new LinkedHashMap<>();
            quantities.forEach((key, quantity) -> result.put(key, WireMockConfigDocument.quantityToString(quantity)));
            return result;
        }
    }

    public record RoutingView(String mode, String host) {
    }
}
