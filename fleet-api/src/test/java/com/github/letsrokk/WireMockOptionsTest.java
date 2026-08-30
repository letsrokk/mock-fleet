package com.github.letsrokk;

import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirements;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WireMockOptionsTest {

    @Test
    void userEntryWithoutVersionClearsBaselinePinWhileAbsentEntryPreservesIt() {
        WireMockConfigDocument baseline = WireMockConfigDocument.load("""
                wiremock:
                  default:
                    options: []
                  mocks:
                    - id: cleared
                      version: 3.12.1
                      options: []
                    - id: preserved
                      version: 3.11.0
                      options: []
                """);
        WireMockConfigDocument user = WireMockConfigDocument.load("""
                wiremock:
                  default:
                    options: []
                  mocks:
                    - id: cleared
                      options: []
                """);

        Map<String, WireMockPodConfig> effective = baseline.merge(user).mockConfigs();

        assertEquals(null, effective.get("cleared").version());
        assertEquals("3.11.0", effective.get("preserved").version());
    }

    @Test
    void configDocumentRoundTripsExplicitMockVersionAndOmitsInheritedVersion() {
        WireMockConfigDocument document = WireMockConfigDocument.load("""
                wiremock:
                  default:
                    options: []
                  mocks:
                    - id: pinned
                      version: 3.12.1
                      options: []
                    - id: inherited
                      version: null
                      options: []
                """);

        String rendered = document.toYaml();

        assertEquals("3.12.1", WireMockConfigDocument.load(rendered).mockConfigs().get("pinned").version());
        assertEquals(null, WireMockConfigDocument.load(rendered).mockConfigs().get("inherited").version());
        org.junit.jupiter.api.Assertions.assertFalse(rendered.contains("version: null"));
    }

    @Test
    void resolvesVersionImageOptionsAndResourcesFromOneCatalogSnapshot() {
        WireMockOptions options = optionsWithCatalog(catalog());
        options.load(input("""
                wiremock:
                  default:
                    options: [--verbose]
                  mocks:
                    - id: demo
                      version: 3.12.1
                      options: []
                """));

        WireMockResolvedConfig resolved = options.resolveFor("demo");

        assertEquals("3.12.1", resolved.version().toString());
        assertEquals("wiremock/wiremock:3.12.1-2", resolved.image());
        assertEquals(List.of("--verbose"), resolved.options());
    }

    @Test
    void inheritedVersionUsesCatalogDefaultInsteadOfConfiguredCompatibilityImage() {
        WireMockOptions options = optionsWithCatalog(catalog());

        WireMockResolvedConfig resolved = options.resolveFor("demo");

        assertEquals("3.13.2", resolved.version().toString());
        assertEquals("wiremock/wiremock:3.13.2-7", resolved.image());
    }

    @Test
    void reportsEveryOptionThatConflictsWithTheDesiredVersion() {
        WireMockOptions options = optionsWithCatalog(new WireMockVersionCatalog(
                WireMockVersion.parse("3.0.0"),
                Map.of(WireMockVersion.parse("3.0.0"), new WireMockVersionCatalog.VersionEntry(
                        WireMockVersion.parse("3.0.0"), "wiremock/wiremock:3.0.0-1", true)),
                "18"));
        options.load(input("""
                wiremock:
                  default:
                    options:
                      - --disable-http2-plain
                      - --version
                  mocks: []
                """));

        ApiException exception = assertThrows(ApiException.class, () -> options.resolveFor("demo"));

        assertEquals("UNSUPPORTED_WIREMOCK_OPTION", ((ApiError) exception.getResponse().getEntity()).code());
        assertEquals(List.of("--disable-http2-plain", "--version"),
                ((ApiError) exception.getResponse().getEntity()).details().get("options"));
    }

    private WireMockOptions optionsWithCatalog(WireMockVersionCatalog catalog) {
        WireMockVersionCatalogService catalogService = org.mockito.Mockito.mock(WireMockVersionCatalogService.class);
        org.mockito.Mockito.when(catalogService.catalog()).thenReturn(catalog);
        WireMockOptions options = new WireMockOptions();
        options.catalogService = catalogService;
        return options;
    }

    private WireMockVersionCatalog catalog() {
        WireMockVersion defaultVersion = WireMockVersion.parse("3.13.2");
        WireMockVersion retainedVersion = WireMockVersion.parse("3.12.1");
        return new WireMockVersionCatalog(defaultVersion, Map.of(
                defaultVersion, new WireMockVersionCatalog.VersionEntry(
                        defaultVersion, "wiremock/wiremock:3.13.2-7", true),
                retainedVersion, new WireMockVersionCatalog.VersionEntry(
                        retainedVersion, "wiremock/wiremock:3.12.1-2", false)), "17");
    }

    @Test
    void loadWithoutConfiguredPathKeepsOptionsEmpty() {
        MockFleetConfig config = mock(MockFleetConfig.class);
        WireMockOptions options = new WireMockOptions();
        options.config = config;

        when(config.wiremockConfigPath()).thenReturn(Optional.empty());

        options.load();

        assertEquals(List.of(), options.optionsFor("demo"));
        assertEquals(null, options.resourcesFor("demo"));
    }

    @Test
    void rejectsMutableWireMockImageAtStartup() {
        MockFleetConfig config = mock(MockFleetConfig.class);
        WireMockOptions options = new WireMockOptions();
        options.config = config;
        when(config.wiremockImage()).thenReturn("wiremock/wiremock:latest");

        assertThrows(IllegalArgumentException.class, options::load);
    }

    @Test
    void loadMergesDefaultAndMatchingMockOptions() {
        WireMockOptions options = new WireMockOptions();

        options.load(input("""
                wiremock:
                  default:
                    options:
                      - --global-response-templating
                    resources:
                      requests:
                        cpu: "0.5"
                        memory: 512Mi
                      limits:
                        cpu: "1"
                        memory: 1Gi
                  mocks:
                    - id: demo
                      options:
                        - --verbose
                      resources:
                        requests:
                          cpu: "2"
                          memory: 2Gi
                        limits:
                          cpu: "3"
                          memory: 3Gi
                    - id: empty-options
                      options: []
                """));

        assertEquals(List.of("--global-response-templating", "--verbose"), options.optionsFor("demo"));
        assertEquals(List.of("--global-response-templating"), options.optionsFor("unknown"));
        assertEquals(List.of("--global-response-templating"), options.optionsFor("empty-options"));
        assertResourceValue("2", options.resourcesFor("demo"), false, "cpu");
        assertResourceValue("512Mi", options.resourcesFor("unknown"), false, "memory");
        assertResourceValue("1", options.resourcesFor("empty-options"), true, "cpu");
    }

    @Test
    void perMockOptionsReplaceDefaultsWithSameCliOptionName() {
        WireMockOptions options = new WireMockOptions();

        options.load(input("""
                wiremock:
                  default:
                    options:
                      - --verbose
                      - --max-request-journal-entries
                      - "20"
                      - --use-chunked-encoding=always
                  mocks:
                    - id: demo
                      options:
                        - --max-request-journal-entries
                        - "10"
                        - --use-chunked-encoding
                        - never
                        - --disable-banner
                """));

        assertEquals(List.of(
                "--verbose",
                "--max-request-journal-entries", "10",
                "--use-chunked-encoding", "never",
                "--disable-banner"), options.optionsFor("demo"));
    }

    @Test
    void userConfigOptionsReplaceBaselineOptionsWithSameCliOptionName() {
        WireMockOptions options = new WireMockOptions();

        options.load(input("""
                wiremock:
                  default:
                    options:
                      - --max-request-journal-entries
                      - "20"
                  mocks:
                    - id: demo
                      options:
                        - --proxy-timeout=1000
                """));
        options.setUserConfig(WireMockConfigDocument.load("""
                wiremock:
                  default:
                    options: []
                  mocks:
                    - id: demo
                      options:
                        - --max-request-journal-entries
                        - "10"
                        - --proxy-timeout
                        - "2000"
                """));

        assertEquals(List.of(
                "--max-request-journal-entries", "10",
                "--proxy-timeout", "2000"), options.optionsFor("demo"));
    }

    @Test
    void userConfigOptionsReplaceCombinedBaselineOptionsWithSameCliOptionName() {
        WireMockOptions options = new WireMockOptions();

        options.load(input("""
                wiremock:
                  default:
                    options:
                      - --verbose --max-request-journal-entries 15 --disable-request-logging --logged-response-body-size-limit 300
                  mocks: []
                """));
        options.setUserConfig(WireMockConfigDocument.load("""
                wiremock:
                  default:
                    options: []
                  mocks:
                    - id: demo
                      options:
                        - --max-request-journal-entries
                        - "20"
                """));

        assertEquals(List.of(
                "--verbose",
                "--max-request-journal-entries", "20",
                "--disable-request-logging",
                "--logged-response-body-size-limit", "300"), options.optionsFor("demo"));
    }

    @Test
    void loadKeepsSeparateValueTokensWithSpaces() {
        WireMockOptions options = new WireMockOptions();

        options.load(input("""
                wiremock:
                  default:
                    options:
                      - --filename-template
                      - "{{{method}}}-{{{url}}}.json"
                  mocks: []
                """));

        assertEquals(List.of(
                "--filename-template",
                "{{{method}}}-{{{url}}}.json"), options.optionsFor("demo"));
    }

    @Test
    void loadAllowsEmptyDefaultAndMocks() {
        WireMockOptions options = new WireMockOptions();

        options.load(input("""
                wiremock:
                  default:
                    options: []
                  mocks: []
                """));

        assertEquals(List.of(), options.optionsFor("demo"));
        assertEquals(null, options.resourcesFor("demo"));
    }

    @Test
    void loadRejectsDuplicateMockIds() {
        WireMockOptions options = new WireMockOptions();

        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> options.load(input("""
                wiremock:
                  default:
                    options: []
                  mocks:
                    - id: demo
                      options: []
                    - id: demo
                      options:
                        - --verbose
                """)));

        assertEquals("Duplicate WireMock options entry for mock id 'demo'.", exception.getMessage());
    }

    @Test
    void loadAllowsNumericResourceValues() {
        WireMockOptions options = new WireMockOptions();

        options.load(input("""
                wiremock:
                  default:
                    options: []
                    resources:
                      requests:
                        cpu: 1
                  mocks: []
                """));

        assertResourceValue("1", options.resourcesFor("demo"), false, "cpu");
    }

    @Test
    void userResourceOverridesInheritOmittedBaselineKeys() {
        WireMockOptions options = new WireMockOptions();
        options.load(input("""
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
                """));
        options.setUserConfig(WireMockConfigDocument.load("""
                wiremock:
                  default:
                    options: []
                  mocks:
                    - id: demo
                      options: []
                      resources:
                        requests:
                          cpu: "0.75"
                        limits: {}
                """));

        ResourceRequirements effective = options.resourcesFor("demo");
        assertResourceValue("0.75", effective, false, "cpu");
        assertResourceValue("512Mi", effective, false, "memory");
        assertResourceValue("1", effective, true, "cpu");
        assertResourceValue("1Gi", effective, true, "memory");
    }

    @Test
    void rejectsLegacyPasswordOptionsBeforeReturningStartupArguments() {
        WireMockOptions options = new WireMockOptions();
        String secret = "legacy-startup-secret";
        options.load(input("""
                wiremock:
                  default:
                    options:
                      - --truststore-password=%s
                  mocks: []
                """.formatted(secret)));

        jakarta.ws.rs.WebApplicationException exception = assertThrows(jakarta.ws.rs.WebApplicationException.class,
                () -> options.optionsFor("demo"));

        org.junit.jupiter.api.Assertions.assertTrue(exception.getMessage().contains("--truststore-password"));
        org.junit.jupiter.api.Assertions.assertFalse(exception.getMessage().contains(secret));
    }

    @Test
    void rejectsInvalidRetainedBaselineOptionsAtStartup() {
        List<List<String>> invalidOptions = List.of(
                List.of("--not-advertised"),
                List.of("--disable-optimize-xml-factories-loading"),
                List.of("--websocket-idle-timeout", "1000"),
                List.of("--proxy-timeout", "soon"),
                List.of("--async-response-threads", "1.5"),
                List.of("--async-response-threads", "-1"),
                List.of("--verbose", "--verbose"),
                List.of("--proxy-timeout", "100", "--proxy-timeout", "200"));

        invalidOptions.forEach(options -> {
            WireMockOptions wireMockOptions = new WireMockOptions();
            wireMockOptions.load(input(WireMockConfigDocument.of(options, null, Map.of()).toYaml()));

            jakarta.ws.rs.WebApplicationException exception = assertThrows(
                    jakarta.ws.rs.WebApplicationException.class,
                    () -> wireMockOptions.optionsFor("demo"), options.toString());

            assertEquals(400, exception.getResponse().getStatus(), options.toString());
        });
    }

    @Test
    void rejectsInvalidRetainedUserConfigOptionsAtStartup() {
        WireMockOptions options = new WireMockOptions();
        options.setUserConfig(WireMockConfigDocument.of(List.of(), null, Map.of(
                "demo", new WireMockPodConfig(
                        List.of("--proxy-timeout", "100", "--proxy-timeout", "200"), null))));

        jakarta.ws.rs.WebApplicationException exception = assertThrows(
                jakarta.ws.rs.WebApplicationException.class, () -> options.optionsFor("demo"));

        assertEquals(400, exception.getResponse().getStatus());
        org.junit.jupiter.api.Assertions.assertTrue(exception.getMessage().contains("Duplicate WireMock option"));
    }

    @Test
    void normalizesValidatedEffectiveOptionsAtStartup() {
        WireMockOptions options = new WireMockOptions();
        options.load(input(WireMockConfigDocument.of(
                List.of("--verbose --proxy-timeout=100"), null, Map.of()).toYaml()));

        assertEquals(List.of("--verbose", "--proxy-timeout", "100"), options.optionsFor("demo"));
    }

    @Test
    void podFactoryRejectsPasswordArgumentsBeforeBuildingThePod() {
        MockFleetConfig config = mock(MockFleetConfig.class);
        PodFactory podFactory = new PodFactory(config);

        jakarta.ws.rs.WebApplicationException exception = assertThrows(jakarta.ws.rs.WebApplicationException.class,
                () -> podFactory.createPodSpec("mock-fleet-demo-", "demo",
                        List.of("--key-manager-password", "direct-secret"), null));

        org.junit.jupiter.api.Assertions.assertTrue(exception.getMessage().contains("--key-manager-password"));
        org.junit.jupiter.api.Assertions.assertFalse(exception.getMessage().contains("direct-secret"));
    }

    @Test
    void podFactoryRejectsInvalidArgumentsBeforeBuildingThePod() {
        MockFleetConfig config = mock(MockFleetConfig.class);
        PodFactory podFactory = new PodFactory(config);

        jakarta.ws.rs.WebApplicationException exception = assertThrows(jakarta.ws.rs.WebApplicationException.class,
                () -> podFactory.createPodSpec("mock-fleet-demo-", "demo",
                        List.of("--async-response-threads", "1.5"), null));

        assertEquals(400, exception.getResponse().getStatus());
        org.junit.jupiter.api.Assertions.assertTrue(exception.getMessage().contains("requires an integer"));
    }

    private void assertResourceValue(String expected, ResourceRequirements resources, boolean limit, String key) {
        Quantity quantity = limit ? resources.getLimits().get(key) : resources.getRequests().get(key);
        Quantity expectedQuantity = new Quantity(expected);
        assertEquals(expectedQuantity.getAmount(), quantity.getAmount());
        assertEquals(expectedQuantity.getFormat(), quantity.getFormat());
    }

    private ByteArrayInputStream input(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }
}
