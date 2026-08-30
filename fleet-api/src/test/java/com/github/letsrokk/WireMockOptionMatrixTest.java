package com.github.letsrokk;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WireMockOptionMatrixTest {

    private final WireMockOptionMatrix matrix = WireMockOptionMatrix.loadDefault();

    @Test
    void declaresTheResearchedWireMockThreeRange() {
        assertEquals("3.0.0", matrix.minimumSupportedVersion().toString());
        assertEquals("3.13.2", matrix.maximumResearchedVersion().toString());
    }

    @Test
    void exposesAuditedWireMockControlShapes() {
        Map<String, Set<String>> optionsByKind = WireMockOptionCatalog.baseDefinitions().stream()
                .collect(Collectors.groupingBy(
                        WireMockOptionCatalog.OptionDefinition::kind,
                        Collectors.mapping(WireMockOptionCatalog.OptionDefinition::name, Collectors.toSet())));

        assertEquals(Set.of(
                "--admin-api-require-https", "--help", "--version", "--disable-http", "--verbose",
                "--print-all-network-traffic", "--disable-request-logging", "--disable-banner",
                "--no-request-journal", "--record-mappings", "--preserve-host-header",
                "--preserve-user-agent-proxy-header", "--enable-browser-proxying", "--trust-all-proxy-targets",
                "--https-require-client-cert", "--disable-http2-plain", "--disable-http2-tls", "--disable-gzip",
                "--enable-stub-cors", "--disable-strict-http-headers", "--global-response-templating",
                "--local-response-templating", "--disable-response-templating", "--disable-extensions-scanning",
                "--disable-optimize-xml-factories-loading"), optionsByKind.get("flag"));
        assertEquals(Set.of(
                "--admin-api-basic-auth", "--bind-address", "--root-dir", "--load-resources-from-classpath",
                "--match-headers", "--filename-template", "--proxy-all", "--proxy-via",
                "--supported-proxy-encodings", "--allow-proxy-targets", "--deny-proxy-targets", "--ca-keystore",
                "--ca-keystore-password", "--ca-keystore-type", "--trust-proxy-target", "--https-keystore",
                "--keystore-type", "--keystore-password", "--key-manager-password", "--https-truststore",
                "--truststore-type", "--truststore-password", "--extensions", "--timeout"),
                optionsByKind.get("input"));
        assertEquals(Set.of(
                "--port", "--https-port", "--logged-response-body-size-limit", "--max-request-journal-entries",
                "--proxy-timeout", "--async-response-threads", "--container-threads",
                "--max-http-client-connections", "--jetty-acceptor-threads", "--jetty-accept-queue-size",
                "--jetty-header-buffer-size", "--jetty-header-request-size", "--jetty-header-response-size",
                "--jetty-idle-timeout", "--jetty-stop-timeout", "--webhook-threadpool-size"),
                optionsByKind.get("number"));
        assertEquals(Set.of(
                "--proxy-pass-through", "--use-chunked-encoding", "--disable-connection-reuse",
                "--async-response-enabled"), optionsByKind.get("select"));
        assertEquals(Set.of("--max-template-cache-entries"), optionsByKind.get("optional_number"));
        assertEquals(Set.of("--permitted-system-keys"), optionsByKind.get("optional_input"));
    }

    @Test
    void publishesOnlyOptionsAvailableInThePinnedVersion() {
        WireMockOptionMatrix.ResolvedCatalog catalog = matrix.resolve(new WireMockVersion(3, 13, 2));
        Map<String, WireMockOptionCatalog.OptionDefinition> options = catalog.options().stream()
                .collect(Collectors.toMap(WireMockOptionCatalog.OptionDefinition::name, Function.identity()));

        assertEquals("input", options.get("--timeout").kind());
        assertEquals("supported", options.get("--timeout").compatibility());
        assertEquals("supported", options.get("--trust-all-proxy-targets").compatibility());
        assertEquals(null, options.get("--trust-all-proxy-targets").compatibilityMessage());
        assertFalse(options.containsKey("--disable-optimize-xml-factories-loading"));
        assertFalse(options.containsKey("--websocket-idle-timeout"));
        assertFalse(options.containsKey("--websocket-max-text-message-size"));
        assertFalse(options.containsKey("--websocket-max-binary-message-size"));
        assertTrue(options.values().stream().allMatch(option -> "supported".equals(option.compatibility())));
        assertTrue(options.values().stream().allMatch(option -> option.compatibilityMessage() == null));
    }

    @Test
    void publishesDirectCredentialOptionsAsUnavailable() {
        Map<String, WireMockOptionCatalog.OptionDefinition> options = matrix.resolve(new WireMockVersion(3, 13, 2))
                .options().stream()
                .collect(Collectors.toMap(WireMockOptionCatalog.OptionDefinition::name, Function.identity()));

        for (String name : new String[]{
                "--ca-keystore-password",
                "--keystore-password",
                "--key-manager-password",
                "--truststore-password",
                "--admin-api-basic-auth"
        }) {
            assertFalse(options.get(name).available(), name);
            assertEquals("SECRET_STORAGE_REQUIRED", options.get(name).unavailableReason(), name);
        }
        assertTrue(options.get("--proxy-via").available());
    }

    @Test
    void marksTimeoutUnavailableBecauseWireMockCannotAcceptItsRequiredValue() {
        WireMockOptionCatalog.OptionDefinition timeout = matrix.resolve(new WireMockVersion(3, 13, 2))
                .options().stream()
                .filter(option -> "--timeout".equals(option.name()))
                .findFirst().orElseThrow();

        assertFalse(timeout.available());
        assertEquals("INCONSISTENT_VALUE_HANDLING", timeout.unavailableReason());
    }

    @Test
    void resolvesStableReleaseChangePointsFromTaggedWireMockSource() {
        Map<String, WireMockOptionCatalog.OptionDefinition> versionThreeZero = matrix
                .resolve(new WireMockVersion(3, 0, 0)).options().stream()
                .collect(Collectors.toMap(WireMockOptionCatalog.OptionDefinition::name, Function.identity()));
        Map<String, WireMockOptionCatalog.OptionDefinition> versionThreeSeven = matrix
                .resolve(new WireMockVersion(3, 7, 0)).options().stream()
                .collect(Collectors.toMap(WireMockOptionCatalog.OptionDefinition::name, Function.identity()));

        assertFalse(versionThreeZero.containsKey("--disable-extensions-scanning"));
        assertFalse(versionThreeZero.containsKey("--disable-http2-plain"));
        assertFalse(versionThreeZero.containsKey("--supported-proxy-encodings"));
        assertFalse(versionThreeZero.containsKey("--disable-connection-reuse"));
        assertEquals("supported", versionThreeSeven.get("--disable-connection-reuse").compatibility());
        assertEquals("select", versionThreeSeven.get("--disable-connection-reuse").kind());
        assertEquals("input", versionThreeZero.get("--timeout").kind());
        assertEquals("supported", versionThreeZero.get("--timeout").compatibility());
    }

    @Test
    void removesOptionsAfterTheirLastSupportedVersion() {
        Map<String, WireMockOptionCatalog.OptionDefinition> versionThreeTwelveOne = matrix
                .resolve(new WireMockVersion(3, 12, 1)).options().stream()
                .collect(Collectors.toMap(WireMockOptionCatalog.OptionDefinition::name, Function.identity()));
        Map<String, WireMockOptionCatalog.OptionDefinition> versionThreeThirteen = matrix
                .resolve(new WireMockVersion(3, 13, 0)).options().stream()
                .collect(Collectors.toMap(WireMockOptionCatalog.OptionDefinition::name, Function.identity()));

        assertTrue(versionThreeTwelveOne.containsKey("--disable-optimize-xml-factories-loading"));
        assertFalse(versionThreeThirteen.containsKey("--disable-optimize-xml-factories-loading"));
    }

    @Test
    void futureWireMockThreeVersionsUseLatestShapesAndUnknownCompatibility() {
        WireMockOptionMatrix.ResolvedCatalog catalog = matrix.resolve(new WireMockVersion(3, 14, 0));

        assertEquals("newer_unresearched", catalog.rangeStatus());
        assertTrue(catalog.options().stream().allMatch(option -> "unknown".equals(option.compatibility())));
        assertEquals("input", catalog.options().stream()
                .filter(option -> "--timeout".equals(option.name()))
                .findFirst().orElseThrow().kind());
        assertFalse(catalog.options().stream()
                .anyMatch(option -> "--disable-optimize-xml-factories-loading".equals(option.name())));
    }

    @Test
    void packagedMatrixContainsEveryStableReleaseAndFiltersTheLatestCatalog() {
        assertEquals(30, matrix.stableReleases().size());
        assertEquals(WireMockOptionCatalog.baseDefinitions().size() - 1,
                matrix.resolve(new WireMockVersion(3, 13, 2)).options().size());
    }

    @Test
    void rejectsMalformedApplicationOwnedMatrix() {
        String malformed = """
                {"minimumSupportedVersion":"3.0.0","maximumResearchedVersion":"3.13.2",
                 "stableReleases":["3.0.0","3.13.2"],
                 "options":[{"name":"--help"},{"name":"--help"}]}
                """;

        assertThrows(IllegalStateException.class, () -> WireMockOptionMatrix.load(
                new ByteArrayInputStream(malformed.getBytes(StandardCharsets.UTF_8))));
    }
}
