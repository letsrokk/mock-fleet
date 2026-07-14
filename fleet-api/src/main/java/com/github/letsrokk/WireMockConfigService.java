package com.github.letsrokk;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirements;
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder;
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
        ApplyMode applyMode = ApplyMode.from(request.applyMode());
        WireMockPodConfig mockConfig = new WireMockPodConfig(validateOptions(request.options()), toResources(request.resources()));
        updateUserConfigMap(request.resourceVersion(), current ->
                current.withMockConfig(mockId, mockConfig));
        refreshUserConfig();
        applyMode.apply(mockId, podManager);
        return view();
    }

    ConfigView deleteMockConfig(String mockId, ConfigUpdateRequest request) {
        validateMockId(mockId);
        updateUserConfigMap(request == null ? null : request.resourceVersion(), current ->
                current.withoutMockConfig(mockId));
        refreshUserConfig();
        ApplyMode.from(request == null ? null : request.applyMode()).apply(mockId, podManager);
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
        return List.of(
                flag("--verbose", "Verbose logging", "Logging and Diagnostics", "Log more detail to stdout."),
                flag("--print-all-network-traffic", "Print network traffic", "Logging and Diagnostics", "Print raw inbound and outbound network traffic."),
                flag("--disable-request-logging", "Disable request logging", "Logging and Diagnostics", "Stops requests and responses being sent to the notifier."),
                number("--logged-response-body-size-limit", "Response body log limit", "Logging and Diagnostics", "Truncates logged response bodies above this byte limit."),
                flag("--disable-banner", "Disable banner", "Logging and Diagnostics", "Prevents the WireMock logo being printed on startup."),

                flag("--no-request-journal", "Disable request journal", "Request Journal and Recording", "Turns off the in-memory journal of received requests."),
                number("--max-request-journal-entries", "Max journal entries", "Request Journal and Recording", "Sets the maximum number of request journal entries."),
                flag("--record-mappings", "Record mappings", "Request Journal and Recording", "Records incoming requests as stub mappings."),
                input("--match-headers", "Match headers", "Request Journal and Recording", "Captures the named request headers when recording."),
                input("--filename-template", "Filename template", "Request Journal and Recording", "Sets the Handlebars filename template for recorded mappings."),

                input("--proxy-all", "Proxy all", "Proxying", "Proxies all requests to the supplied base URL."),
                input("--proxy-via", "Proxy via", "Proxying", "Routes proxied traffic through another proxy server."),
                flag("--preserve-host-header", "Preserve host header", "Proxying", "Keeps the original Host header when proxying."),
                flag("--preserve-user-agent-proxy-header", "Preserve user agent", "Proxying", "Keeps the original User-Agent header when proxying."),
                input("--supported-proxy-encodings", "Proxy encodings", "Proxying", "Sets acceptable compression methods for proxy and recording traffic."),
                input("--allow-proxy-targets", "Allow proxy targets", "Proxying", "Limits proxying and recording to the supplied targets."),
                input("--deny-proxy-targets", "Deny proxy targets", "Proxying", "Blocks proxying and recording to the supplied targets."),
                number("--proxy-timeout", "Proxy timeout ms", "Proxying", "Sets the proxy request timeout in milliseconds."),
                flag("--proxy-pass-through", "Proxy pass through", "Proxying", "Allows unmatched browser proxy requests to pass through."),

                flag("--enable-browser-proxying", "Browser proxying", "Browser Proxy and Certificates", "Runs WireMock as a browser proxy."),
                input("--ca-keystore", "CA keystore", "Browser Proxy and Certificates", "Sets the CA keystore used for generated proxy certificates."),
                input("--ca-keystore-password", "CA keystore password", "Browser Proxy and Certificates", "Sets the CA keystore password."),
                input("--ca-keystore-type", "CA keystore type", "Browser Proxy and Certificates", "Sets the CA keystore type."),
                flag("--trust-all-proxy-targets", "Trust all proxy targets", "Browser Proxy and Certificates", "Trusts all remote certificates when proxying HTTPS traffic."),
                input("--trust-proxy-target", "Trust proxy target", "Browser Proxy and Certificates", "Trusts a specific remote endpoint certificate."),
                input("--https-keystore", "HTTPS keystore", "Browser Proxy and Certificates", "Sets the HTTPS keystore path."),
                input("--keystore-type", "Keystore type", "Browser Proxy and Certificates", "Sets the HTTPS keystore type."),
                input("--keystore-password", "Keystore password", "Browser Proxy and Certificates", "Sets the HTTPS keystore password."),
                input("--key-manager-password", "Key manager password", "Browser Proxy and Certificates", "Sets the key manager password."),
                input("--https-truststore", "HTTPS truststore", "Browser Proxy and Certificates", "Sets the HTTPS truststore path."),
                input("--truststore-type", "Truststore type", "Browser Proxy and Certificates", "Sets the HTTPS truststore type."),
                input("--truststore-password", "Truststore password", "Browser Proxy and Certificates", "Sets the HTTPS truststore password."),
                flag("--https-require-client-cert", "Require client cert", "Browser Proxy and Certificates", "Requires clients to authenticate with a certificate."),

                flag("--disable-http2-plain", "Disable HTTP/2 plain", "HTTP Responses", "Disables HTTP/2 over plain HTTP."),
                flag("--disable-http2-tls", "Disable HTTP/2 TLS", "HTTP Responses", "Disables HTTP/2 over HTTPS."),
                flag("--disable-gzip", "Disable gzip", "HTTP Responses", "Prevents response bodies from being gzipped."),
                flag("--enable-stub-cors", "Enable stub CORS", "HTTP Responses", "Adds automatic CORS response headers for stubs."),
                select("--use-chunked-encoding", "Chunked encoding", "HTTP Responses", "Controls when responses use Transfer-Encoding: chunked.", List.of("always", "never", "body_file")),
                flag("--disable-connection-reuse", "Disable connection reuse", "HTTP Responses", "Disables HTTP connection reuse."),
                flag("--disable-strict-http-headers", "Disable strict headers", "HTTP Responses", "Disables strict HTTP header handling."),

                flag("--global-response-templating", "Global response templating", "Templating", "Renders all response definitions with Handlebars templates."),
                flag("--local-response-templating", "Local response templating", "Templating", "Allows templating only on stub mappings that opt in."),
                flag("--disable-response-templating", "Disable response templating", "Templating", "Disables processing responses with Handlebars templates."),
                number("--max-template-cache-entries", "Max template cache entries", "Templating", "Limits compiled template fragments kept in cache."),
                input("--permitted-system-keys", "Permitted system keys", "Templating", "Sets permitted system property and environment variable names for templates."),

                input("--extensions", "Extensions", "Extensions", "Sets extension class names."),
                flag("--disable-extensions-scanning", "Disable extension scanning", "Extensions", "Prevents extensions being scanned and loaded from the classpath."),
                flag("--disable-optimize-xml-factories-loading", "Disable XML factory optimization", "Extensions", "Disables optimized XML factory loading."),

                flag("--async-response-enabled", "Async responses", "Performance and Jetty", "Enables asynchronous request processing for delayed responses."),
                number("--async-response-threads", "Async response threads", "Performance and Jetty", "Sets the number of background response threads."),
                number("--container-threads", "Container threads", "Performance and Jetty", "Sets the number of Jetty container threads."),
                number("--max-http-client-connections", "Max HTTP client connections", "Performance and Jetty", "Sets the maximum HTTP client connections."),
                number("--jetty-acceptor-threads", "Jetty acceptor threads", "Performance and Jetty", "Sets the number of Jetty acceptor threads."),
                number("--jetty-accept-queue-size", "Jetty accept queue size", "Performance and Jetty", "Sets the Jetty accepted request queue size."),
                number("--jetty-header-buffer-size", "Jetty header buffer size", "Performance and Jetty", "Sets the deprecated Jetty request header buffer size."),
                number("--jetty-header-request-size", "Jetty request header size", "Performance and Jetty", "Sets the Jetty request header buffer size."),
                number("--jetty-header-response-size", "Jetty response header size", "Performance and Jetty", "Sets the Jetty response header buffer size."),
                number("--jetty-idle-timeout", "Jetty idle timeout ms", "Performance and Jetty", "Sets the Jetty connection idle timeout in milliseconds."),
                number("--jetty-stop-timeout", "Jetty stop timeout ms", "Performance and Jetty", "Sets the Jetty stop timeout in milliseconds."),
                number("--timeout", "Timeout ms", "Performance and Jetty", "Sets the default global timeout in milliseconds."),

                number("--webhook-threadpool-size", "Webhook thread pool size", "Webhooks and WebSockets", "Sets the webhook processing thread count."),
                number("--websocket-idle-timeout", "WebSocket idle timeout ms", "Webhooks and WebSockets", "Sets the WebSocket idle timeout in milliseconds."),
                number("--websocket-max-text-message-size", "Max text message size", "Webhooks and WebSockets", "Sets the maximum WebSocket text message size in bytes."),
                number("--websocket-max-binary-message-size", "Max binary message size", "Webhooks and WebSockets", "Sets the maximum WebSocket binary message size in bytes."));
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

    enum ApplyMode {
        FUTURE_ONLY("futureOnly") {
            @Override
            void apply(String mockId, PodManager podManager) {
            }
        },
        RESTART_ACTIVE("restartActive") {
            @Override
            void apply(String mockId, PodManager podManager) {
                PodManager.DeleteMockResult result = podManager.deleteMock(mockId);
                if (result == PodManager.DeleteMockResult.FAILED) {
                    throw new WebApplicationException("Config was saved, but active mock pod restart failed.",
                            Response.Status.INTERNAL_SERVER_ERROR);
                }
            }
        };

        private final String wireValue;

        ApplyMode(String wireValue) {
            this.wireValue = wireValue;
        }

        abstract void apply(String mockId, PodManager podManager);

        static ApplyMode from(String value) {
            if (value == null || value.isBlank() || FUTURE_ONLY.wireValue.equals(value)) {
                return FUTURE_ONLY;
            }
            if (RESTART_ACTIVE.wireValue.equals(value)) {
                return RESTART_ACTIVE;
            }
            throw new WebApplicationException("Unsupported config apply mode: " + value, Response.Status.BAD_REQUEST);
        }
    }

    public record ConfigUpdateRequest(String resourceVersion, List<String> options, ResourceData resources,
                                      String applyMode) {
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
