package com.github.letsrokk;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirements;
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@ApplicationScoped
public class WireMockConfigService {

    private static final Logger LOG = Logger.getLogger(WireMockConfigService.class);
    private static final Pattern VALID_MOCK_ID = Pattern.compile("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$");
    static final String MOCK_ID_VALIDATION_MESSAGE = "Mock id must contain 1-63 lowercase letters, numbers, or hyphens, and must start and end with a letter or number.";
    private static final String RESOURCE_POLICY_ANNOTATION = "helm.sh/resource-policy";
    private static final String RESOURCE_POLICY_KEEP = "keep";

    @Inject
    MockFleetConfig config;

    @Inject
    KubernetesClient kubernetesClient;

    @Inject
    WireMockOptions wireMockOptions;

    @Inject
    PodManager podManager;

    private volatile Watch userConfigWatch;

    @PostConstruct
    void loadUserConfig() {
        refreshUserConfig();
        startUserConfigWatch();
    }

    @PreDestroy
    void closeWatch() {
        Watch local = userConfigWatch;
        if (local != null) {
            local.close();
        }
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
        podManager.listActiveMocks().stream()
                .map(PodManager.ActiveMockPod::mockId)
                .sorted()
                .forEach(mockIds::add);

        Set<String> activeIds = new LinkedHashSet<>(podManager.listActiveMocks().stream()
                .map(PodManager.ActiveMockPod::mockId)
                .toList());

        List<MockConfigView> mocks = mockIds.stream()
                .sorted()
                .map(mockId -> mockConfigView(mockId, baseline, userConfig, effective, activeIds.contains(mockId)))
                .toList();

        return new ConfigView(
                resourceVersion(userConfigMap),
                List.copyOf(mockIds.stream().sorted().toList()),
                mocks,
                optionDefinitions());
    }

    ConfigView upsertMockConfig(String mockId, ConfigUpdateRequest request) {
        validateMockId(mockId);
        if (request == null) {
            throw new WebApplicationException("Config update request body is required.", Response.Status.BAD_REQUEST);
        }
        WireMockPodConfig mockConfig = new WireMockPodConfig(validateOptions(request.options()), toResources(request.resources()));
        updateUserConfigMap(request.resourceVersion(), current ->
                current.withMockConfig(mockId, mockConfig));
        refreshUserConfig();
        reloadProxy();
        return view();
    }

    ConfigView deleteMockConfig(String mockId, ConfigUpdateRequest request) {
        validateMockId(mockId);
        updateUserConfigMap(request == null ? null : request.resourceVersion(), current ->
                current.withoutMockConfig(mockId));
        refreshUserConfig();
        reloadProxy();
        return view();
    }

    void refreshUserConfig() {
        ConfigMap configMap = userConfigMap();
        wireMockOptions.setUserConfig(loadUserConfig(configMap));
    }

    private void startUserConfigWatch() {
        Optional<String> name = userConfigMapName();
        if (name.isEmpty()) {
            return;
        }

        userConfigWatch = kubernetesClient.configMaps()
                .inNamespace(currentNamespace())
                .withName(name.get())
                .watch(new Watcher<>() {
                    @Override
                    public void eventReceived(Action action, ConfigMap resource) {
                        if (action == Action.ERROR) {
                            return;
                        }
                        wireMockOptions.setUserConfig(action == Action.DELETED
                                ? WireMockConfigDocument.empty()
                                : loadUserConfig(resource));
                        reloadProxy();
                    }

                    @Override
                    public void onClose(WatcherException cause) {
                        if (cause != null) {
                            LOG.warnf(cause, "WireMock user ConfigMap watch closed with an error.");
                        }
                    }
                });
    }

    private MockConfigView mockConfigView(String mockId, WireMockConfigDocument baseline,
                                          WireMockConfigDocument userConfig,
                                          WireMockConfigDocument effective,
                                          boolean active) {
        return new MockConfigView(
                mockId,
                active,
                configData(baseline.optionsFor(mockId), baseline.resourcesFor(mockId)),
                configData(userConfig.mockConfigs().getOrDefault(mockId, new WireMockPodConfig(List.of(), null))),
                configData(effective.optionsFor(mockId), effective.resourcesFor(mockId)));
    }

    private ConfigData configData(WireMockPodConfig config) {
        return configData(config.options(), config.resources());
    }

    private ConfigData configData(List<String> options, ResourceRequirements resources) {
        return new ConfigData(options == null ? List.of() : List.copyOf(options), ResourceData.from(resources));
    }

    private ConfigMap userConfigMap() {
        Optional<String> name = userConfigMapName();
        if (name.isEmpty()) {
            return null;
        }
        return kubernetesClient.configMaps()
                .inNamespace(currentNamespace())
                .withName(name.get())
                .get();
    }

    private WireMockConfigDocument loadUserConfig(ConfigMap configMap) {
        if (configMap == null || configMap.getData() == null) {
            return WireMockConfigDocument.empty();
        }
        return WireMockConfigDocument.load(configMap.getData().get(config.wiremockConfigKey()));
    }

    private void updateUserConfigMap(String expectedResourceVersion,
                                     java.util.function.Function<WireMockConfigDocument, WireMockConfigDocument> update) {
        String name = userConfigMapName().orElseThrow(() -> new WebApplicationException(
                "User editable WireMock ConfigMap is not configured.",
                Response.Status.SERVICE_UNAVAILABLE));
        String namespace = currentNamespace();
        ConfigMap current = kubernetesClient.configMaps()
                .inNamespace(namespace)
                .withName(name)
                .get();

        if (current != null && expectedResourceVersion != null
                && !Objects.equals(expectedResourceVersion, resourceVersion(current))) {
            throw new WebApplicationException("WireMock config was modified by another writer.", Response.Status.CONFLICT);
        }

        WireMockConfigDocument currentConfig = loadUserConfig(current);
        WireMockConfigDocument nextConfig = update.apply(currentConfig);
        Map<String, String> data = new LinkedHashMap<>();
        data.put(config.wiremockConfigKey(), nextConfig.toYaml());

        ConfigMapBuilder builder = new ConfigMapBuilder(current == null ? new ConfigMap() : current)
                .editOrNewMetadata()
                .withName(name)
                .withNamespace(namespace)
                .addToAnnotations(RESOURCE_POLICY_ANNOTATION, RESOURCE_POLICY_KEEP)
                .addToLabels("app.kubernetes.io/name", "mock-fleet")
                .addToLabels("app.kubernetes.io/managed-by", "mock-fleet")
                .endMetadata()
                .withData(data);
        ConfigMap next = builder.build();

        if (current == null) {
            kubernetesClient.configMaps().inNamespace(namespace).resource(next).create();
            return;
        }
        kubernetesClient.configMaps().inNamespace(namespace).resource(next).update();
    }

    private void reloadProxy() {
        Optional<String> deploymentName = config.proxyDeploymentName()
                .map(String::trim)
                .filter(value -> !value.isBlank());
        if (deploymentName.isEmpty()) {
            return;
        }

        String namespace = currentNamespace();
        String annotationValue = Instant.now().toString();
        try {
            kubernetesClient.apps()
                    .deployments()
                    .inNamespace(namespace)
                    .withName(deploymentName.get())
                    .edit(deployment -> new DeploymentBuilder(deployment)
                            .editSpec()
                                .editTemplate()
                                    .editOrNewMetadata()
                                        .addToAnnotations("mock-fleet/wiremock-user-config-reloaded-at", annotationValue)
                                    .endMetadata()
                                .endTemplate()
                            .endSpec()
                            .build());
        } catch (RuntimeException e) {
            LOG.warnf(e, "Failed to trigger proxy reload after WireMock user ConfigMap change.");
        }
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

    private List<String> validateOptions(List<String> options) {
        if (options == null) {
            return List.of();
        }
        List<String> validated = new ArrayList<>();
        for (String option : options) {
            if (option == null || option.isBlank()) {
                throw new WebApplicationException("WireMock options must be non-blank strings.", Response.Status.BAD_REQUEST);
            }
            validated.add(option.trim());
        }
        return List.copyOf(validated);
    }

    private void validateMockId(String mockId) {
        if (mockId == null || !VALID_MOCK_ID.matcher(mockId).matches()) {
            throw new WebApplicationException(MOCK_ID_VALIDATION_MESSAGE, Response.Status.BAD_REQUEST);
        }
    }

    private ResourceRequirements toResources(ResourceData resources) {
        if (resources == null) {
            return null;
        }
        return new ResourceRequirementsBuilder()
                .withRequests(toQuantities(resources.requests()))
                .withLimits(toQuantities(resources.limits()))
                .build();
    }

    private Map<String, Quantity> toQuantities(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, Quantity> quantities = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (key != null && !key.isBlank() && value != null && !value.isBlank()) {
                quantities.put(key, new Quantity(value.trim()));
            }
        });
        return quantities;
    }

    private List<OptionDefinition> optionDefinitions() {
        List<OptionDefinition> options = List.of(
                flag("--verbose", "Verbose logging", "Logging", "Log more detail to the console."),
                flag("--disable-request-logging", "Disable request logging", "Logging", "Stops requests and responses being sent to the notifier."),
                flag("--no-request-journal", "Disable request journal", "Logging", "Turns off the in-memory journal of received requests."),
                number("--logged-response-body-size-limit", "Response body log limit", "Logging", "Truncates logged response bodies above this byte limit."),

                flag("--global-response-templating", "Global response templating", "Templating", "Renders all response definitions with Handlebars templates."),
                flag("--local-response-templating", "Local response templating", "Templating", "Allows templating only on stub mappings that opt in."),
                number("--max-template-cache-entries", "Max template cache entries", "Templating", "Limits compiled template fragments kept in cache."),

                input("--proxy-all", "Proxy all", "Proxying and Recording", "Proxies unmatched requests to the supplied base URL."),
                input("--proxy-via", "Proxy via", "Proxying and Recording", "Routes proxied traffic through another proxy server."),
                flag("--record-mappings", "Record mappings", "Proxying and Recording", "Records proxied traffic as stub mappings."),
                flag("--preserve-host-header", "Preserve host header", "Proxying and Recording", "Keeps the original Host header when proxying."),

                flag("--disable-gzip", "Disable gzip", "HTTP Responses", "Prevents response bodies from being gzipped."),
                flag("--enable-stub-cors", "Enable stub CORS", "HTTP Responses", "Adds automatic CORS response headers for stubs."),
                select("--use-chunked-encoding", "Chunked encoding", "HTTP Responses", "Controls when responses use Transfer-Encoding: chunked.", List.of("always", "never", "body_file")),

                flag("--async-response-enabled", "Async responses", "Performance", "Enables asynchronous request processing for delayed responses."),
                number("--async-response-threads", "Async response threads", "Performance", "Sets the number of background response threads."),
                number("--container-threads", "Container threads", "Performance", "Sets the number of Jetty container threads."),
                number("--timeout", "Timeout ms", "Performance", "Sets the default global timeout in milliseconds."),

                flag("--disable-banner", "Disable banner", "Startup", "Prevents the WireMock logo being printed on startup."));
        return options;
    }

    private OptionDefinition flag(String name, String label, String group, String description) {
        return new OptionDefinition(name, label, "flag", group, description, List.of());
    }

    private OptionDefinition input(String name, String label, String group, String description) {
        return new OptionDefinition(name, label, "input", group, description, List.of());
    }

    private OptionDefinition number(String name, String label, String group, String description) {
        return new OptionDefinition(name, label, "number", group, description, List.of());
    }

    private OptionDefinition select(String name, String label, String group, String description, List<String> values) {
        return new OptionDefinition(name, label, "select", group, description, values);
    }

    public record ConfigView(String resourceVersion, List<String> mockIds, List<MockConfigView> mocks,
                             List<OptionDefinition> options) {
    }

    public record MockConfigView(String mockId, boolean active, ConfigData baseline, ConfigData user,
                                 ConfigData effective) {
    }

    public record ConfigData(List<String> options, ResourceData resources) {
    }

    public record ConfigUpdateRequest(String resourceVersion, List<String> options, ResourceData resources) {
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

    public record OptionDefinition(String name, String label, String kind, String group, String description,
                                   List<String> values) {
    }
}
