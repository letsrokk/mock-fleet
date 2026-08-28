package com.github.letsrokk;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.ConfigMapList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NamespaceableResource;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WireMockOptionValidationTest {

    @Test
    void normalizesSplitCombinedQuotedAndEqualsOptionSyntaxBeforePersistence() {
        Fixture fixture = fixture();

        fixture.service.upsertMockConfig("demo", request(List.of(
                "--verbose",
                "--proxy-timeout=1000",
                "--filename-template \"a b\"")));

        ArgumentCaptor<ConfigMap> persisted = ArgumentCaptor.forClass(ConfigMap.class);
        verify(fixture.namespacedConfigMaps).resource(persisted.capture());
        assertEquals(List.of("--verbose", "--proxy-timeout", "1000", "--filename-template", "a b"),
                WireMockConfigDocument.load(persisted.getValue().getData().get("wiremock-options.yaml"))
                        .mockConfigs().get("demo").options());
    }

    @Test
    void rejectsUnknownOptionsBeforePersistence() {
        assertInvalid(List.of("--not-advertised"), "Unknown WireMock option: --not-advertised");
    }

    @Test
    void rejectsDuplicateOptionsBeforePersistence() {
        assertInvalid(List.of("--verbose", "--verbose"), "Duplicate WireMock option: --verbose");
    }

    @Test
    void rejectsStrayValuesBeforePersistence() {
        assertInvalid(List.of("stray"), "Unexpected WireMock option value: stray");
    }

    @Test
    void rejectsMissingOptionValuesBeforePersistence() {
        assertInvalid(List.of("--proxy-timeout"), "WireMock option requires a value: --proxy-timeout");
    }

    @Test
    void rejectsWrongNumberTypesBeforePersistence() {
        assertInvalid(List.of("--proxy-timeout", "soon"),
                "WireMock option requires an integer from 1 to 3600000: --proxy-timeout");
    }

    @Test
    void rejectsUnsupportedSelectValuesBeforePersistence() {
        assertInvalid(List.of("--use-chunked-encoding=occasionally"),
                "Unsupported value for --use-chunked-encoding: occasionally");
    }

    @Test
    void rejectsPasswordOptionsInSplitAndEqualsFormsWithoutDisclosingValues() {
        List.of(
                "--ca-keystore-password",
                "--keystore-password",
                "--key-manager-password",
                "--truststore-password"
        ).forEach(option -> {
            assertSensitiveInvalid(List.of(option, "split-secret-" + option), option,
                    "split-secret-" + option);
            assertSensitiveInvalid(List.of(option + "=inline-secret-" + option), option,
                    "inline-secret-" + option);
        });
    }

    @Test
    void rejectsNonIntegralAndOutOfRangeNumericOptionsBeforePersistence() {
        Stream.of(
                new InvalidNumber("--async-response-threads", "-1", 1, 256),
                new InvalidNumber("--async-response-threads", "1.5", 1, 256),
                new InvalidNumber("--async-response-threads", "0", 1, 256),
                new InvalidNumber("--async-response-threads", "257", 1, 256),
                new InvalidNumber("--max-request-journal-entries", "100001", 0, 100000),
                new InvalidNumber("--jetty-header-buffer-size", "1048577", 1, 1048576),
                new InvalidNumber("--websocket-max-text-message-size", "16777217", 1, 16777216),
                new InvalidNumber("--proxy-timeout", "3600001", 1, 3600000)
        ).forEach(number -> assertInvalid(
                List.of(number.option(), number.value()),
                "WireMock option requires an integer from " + number.minimum() + " to " + number.maximum()
                        + ": " + number.option()));
    }

    @Test
    void acceptsZeroForCacheJournalAndLogLimits() {
        Fixture fixture = fixture();

        fixture.service.upsertMockConfig("demo", request(List.of(
                "--max-template-cache-entries", "0",
                "--max-request-journal-entries", "0",
                "--logged-response-body-size-limit", "0")));

        ArgumentCaptor<ConfigMap> persisted = ArgumentCaptor.forClass(ConfigMap.class);
        verify(fixture.namespacedConfigMaps).resource(persisted.capture());
        assertEquals(List.of(
                        "--max-template-cache-entries", "0",
                        "--max-request-journal-entries", "0",
                        "--logged-response-body-size-limit", "0"),
                WireMockConfigDocument.load(persisted.getValue().getData().get("wiremock-options.yaml"))
                        .mockConfigs().get("demo").options());
    }

    @Test
    void publishesIntegralBoundsFromTheOptionCatalog() {
        Map<String, WireMockOptionCatalog.OptionDefinition> definitions = WireMockOptionCatalog.definitions().stream()
                .collect(java.util.stream.Collectors.toMap(
                        WireMockOptionCatalog.OptionDefinition::name,
                        definition -> definition));
        Map<String, NumericBounds> expected = Map.ofEntries(
                Map.entry("--logged-response-body-size-limit", new NumericBounds(0, 16777216)),
                Map.entry("--max-request-journal-entries", new NumericBounds(0, 100000)),
                Map.entry("--proxy-timeout", new NumericBounds(1, 3600000)),
                Map.entry("--max-template-cache-entries", new NumericBounds(0, 100000)),
                Map.entry("--async-response-threads", new NumericBounds(1, 256)),
                Map.entry("--container-threads", new NumericBounds(1, 512)),
                Map.entry("--max-http-client-connections", new NumericBounds(1, 10000)),
                Map.entry("--jetty-acceptor-threads", new NumericBounds(1, 256)),
                Map.entry("--jetty-accept-queue-size", new NumericBounds(1, 10000)),
                Map.entry("--jetty-header-buffer-size", new NumericBounds(1, 1048576)),
                Map.entry("--jetty-header-request-size", new NumericBounds(1, 1048576)),
                Map.entry("--jetty-header-response-size", new NumericBounds(1, 1048576)),
                Map.entry("--jetty-idle-timeout", new NumericBounds(1, 3600000)),
                Map.entry("--jetty-stop-timeout", new NumericBounds(1, 3600000)),
                Map.entry("--timeout", new NumericBounds(1, 3600000)),
                Map.entry("--webhook-threadpool-size", new NumericBounds(1, 256)),
                Map.entry("--websocket-idle-timeout", new NumericBounds(1, 3600000)),
                Map.entry("--websocket-max-text-message-size", new NumericBounds(1, 16777216)),
                Map.entry("--websocket-max-binary-message-size", new NumericBounds(1, 16777216)));

        assertEquals(expected.size(), definitions.values().stream()
                .filter(definition -> "number".equals(definition.kind())).count());
        expected.forEach((name, bounds) -> {
            assertEquals(bounds.minimum(), definitions.get(name).minimum(), name);
            assertEquals(bounds.maximum(), definitions.get(name).maximum(), name);
        });
    }

    private void assertInvalid(List<String> options, String expectedMessage) {
        Fixture fixture = fixture();

        WebApplicationException exception = assertThrows(WebApplicationException.class,
                () -> fixture.service.upsertMockConfig("demo", request(options)));

        assertEquals(400, exception.getResponse().getStatus());
        assertEquals(expectedMessage, exception.getMessage());
        verify(fixture.namespacedConfigMaps, never()).resource(any());
    }

    private void assertSensitiveInvalid(List<String> options, String option, String submittedValue) {
        Fixture fixture = fixture();

        WebApplicationException exception = assertThrows(WebApplicationException.class,
                () -> fixture.service.upsertMockConfig("demo", request(options)));

        assertEquals(400, exception.getResponse().getStatus());
        org.junit.jupiter.api.Assertions.assertTrue(exception.getMessage().contains(option));
        org.junit.jupiter.api.Assertions.assertFalse(exception.getMessage().contains(submittedValue));
        org.junit.jupiter.api.Assertions.assertFalse(exception.getResponse().getEntity().toString().contains(submittedValue));
        verify(fixture.namespacedConfigMaps, never()).resource(any());
    }

    private WireMockConfigService.ConfigUpdateRequest request(List<String> options) {
        return new WireMockConfigService.ConfigUpdateRequest(
                "42", options, null, "futureOnly");
    }

    @SuppressWarnings("unchecked")
    private Fixture fixture() {
        KubernetesClient kubernetesClient = mock(KubernetesClient.class);
        MixedOperation<ConfigMap, ConfigMapList, Resource<ConfigMap>> configMaps = mock(MixedOperation.class);
        NonNamespaceOperation<ConfigMap, ConfigMapList, Resource<ConfigMap>> namespacedConfigMaps =
                mock(NonNamespaceOperation.class);
        Resource<ConfigMap> configMapResource = mock(Resource.class);
        NamespaceableResource<ConfigMap> updatedConfigMap = mock(NamespaceableResource.class);
        ConfigMap existing = new ConfigMapBuilder()
                .withNewMetadata().withName("user-config").withNamespace("test").withResourceVersion("42").endMetadata()
                .withData(Map.of("wiremock-options.yaml", "wiremock:\n  default:\n    options: []\n  mocks: []\n"))
                .build();
        when(kubernetesClient.getNamespace()).thenReturn("test");
        when(kubernetesClient.configMaps()).thenReturn(configMaps);
        when(configMaps.inNamespace("test")).thenReturn(namespacedConfigMaps);
        when(namespacedConfigMaps.withName("user-config")).thenReturn(configMapResource);
        when(configMapResource.get()).thenReturn(existing);
        when(namespacedConfigMaps.resource(any())).thenReturn(updatedConfigMap);

        MockFleetConfig config = mock(MockFleetConfig.class);
        when(config.wiremockUserConfigMapName()).thenReturn(Optional.of("user-config"));
        when(config.wiremockConfigKey()).thenReturn("wiremock-options.yaml");
        MockFleetConfig.ProxyConfig proxy = mock(MockFleetConfig.ProxyConfig.class);
        MockFleetConfig.RoutingConfig routing = mock(MockFleetConfig.RoutingConfig.class);
        when(config.proxy()).thenReturn(proxy);
        when(proxy.routing()).thenReturn(routing);
        when(routing.mode()).thenReturn(MockFleetConfig.RoutingMode.HOST);
        WireMockConfigService service = new WireMockConfigService();
        service.kubernetesClient = kubernetesClient;
        service.config = config;
        service.wireMockOptions = new WireMockOptions();
        service.wireMockOptions.load(new ByteArrayInputStream("""
                wiremock:
                  default:
                    options: []
                    resources:
                      requests:
                        cpu: "0.5"
                        memory: 512Mi
                      limits:
                        cpu: "1"
                        memory: 1Gi
                  mocks: []
                """.getBytes(StandardCharsets.UTF_8)));
        service.resourcePolicy = new WireMockResourcePolicy(config);
        service.podManager = mock(PodManager.class);
        when(service.podManager.listMocks()).thenReturn(List.of());
        when(service.podManager.status("demo")).thenReturn(new PodManager.MockPodStatus(
                "demo", null, MockLifecycleStatus.STOPPED, null));
        return new Fixture(service, namespacedConfigMaps);
    }

    private record Fixture(
            WireMockConfigService service,
            NonNamespaceOperation<ConfigMap, ConfigMapList, Resource<ConfigMap>> namespacedConfigMaps) {
    }

    private record InvalidNumber(String option, String value, int minimum, int maximum) {
    }

    private record NumericBounds(int minimum, int maximum) {
    }
}
