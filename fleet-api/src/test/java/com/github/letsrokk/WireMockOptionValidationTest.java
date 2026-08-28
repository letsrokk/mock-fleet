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

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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
        assertInvalid(List.of("--proxy-timeout", "soon"), "WireMock option requires a number: --proxy-timeout");
    }

    @Test
    void rejectsUnsupportedSelectValuesBeforePersistence() {
        assertInvalid(List.of("--use-chunked-encoding=occasionally"),
                "Unsupported value for --use-chunked-encoding: occasionally");
    }

    private void assertInvalid(List<String> options, String expectedMessage) {
        Fixture fixture = fixture();

        WebApplicationException exception = assertThrows(WebApplicationException.class,
                () -> fixture.service.upsertMockConfig("demo", request(options)));

        assertEquals(400, exception.getResponse().getStatus());
        assertEquals(expectedMessage, exception.getMessage());
    }

    private WireMockConfigService.ConfigUpdateRequest request(List<String> options) {
        return new WireMockConfigService.ConfigUpdateRequest(
                "42", options, new WireMockConfigService.ResourceData(Map.of(), Map.of()), "futureOnly");
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
}
