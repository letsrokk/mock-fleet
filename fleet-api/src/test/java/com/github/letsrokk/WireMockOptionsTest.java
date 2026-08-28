package com.github.letsrokk;

import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirements;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WireMockOptionsTest {

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
                      - "{{request.method}} {{request.url}}"
                  mocks: []
                """));

        assertEquals(List.of(
                "--filename-template",
                "{{request.method}} {{request.url}}"), options.optionsFor("demo"));
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
    void podFactoryRejectsPasswordArgumentsBeforeBuildingThePod() {
        MockFleetConfig config = mock(MockFleetConfig.class);
        PodFactory podFactory = new PodFactory(config);

        jakarta.ws.rs.WebApplicationException exception = assertThrows(jakarta.ws.rs.WebApplicationException.class,
                () -> podFactory.createPodSpec("mock-fleet-demo-", "demo",
                        List.of("--key-manager-password", "direct-secret"), null));

        org.junit.jupiter.api.Assertions.assertTrue(exception.getMessage().contains("--key-manager-password"));
        org.junit.jupiter.api.Assertions.assertFalse(exception.getMessage().contains("direct-secret"));
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
