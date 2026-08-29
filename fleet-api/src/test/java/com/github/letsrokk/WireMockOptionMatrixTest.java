package com.github.letsrokk;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
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
    void resolvesKnownLatestRegressionsWithoutHidingOptions() {
        WireMockOptionMatrix.ResolvedCatalog catalog = matrix.resolve(new WireMockVersion(3, 13, 2));
        Map<String, WireMockOptionCatalog.OptionDefinition> options = catalog.options().stream()
                .collect(Collectors.toMap(WireMockOptionCatalog.OptionDefinition::name, Function.identity()));

        assertEquals("flag", options.get("--timeout").kind());
        assertEquals("known_broken", options.get("--trust-all-proxy-targets").compatibility());
        assertEquals("unsupported", options.get("--disable-optimize-xml-factories-loading").compatibility());
        assertEquals("unsupported", options.get("--websocket-idle-timeout").compatibility());
        assertEquals("unsupported", options.get("--websocket-max-text-message-size").compatibility());
        assertEquals("unsupported", options.get("--websocket-max-binary-message-size").compatibility());
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
    void resolvesStableReleaseChangePointsFromTaggedWireMockSource() {
        Map<String, WireMockOptionCatalog.OptionDefinition> versionThreeZero = matrix
                .resolve(new WireMockVersion(3, 0, 0)).options().stream()
                .collect(Collectors.toMap(WireMockOptionCatalog.OptionDefinition::name, Function.identity()));
        Map<String, WireMockOptionCatalog.OptionDefinition> versionThreeSeven = matrix
                .resolve(new WireMockVersion(3, 7, 0)).options().stream()
                .collect(Collectors.toMap(WireMockOptionCatalog.OptionDefinition::name, Function.identity()));

        assertEquals("unsupported", versionThreeZero.get("--disable-extensions-scanning").compatibility());
        assertEquals("unsupported", versionThreeZero.get("--disable-http2-plain").compatibility());
        assertEquals("unsupported", versionThreeZero.get("--supported-proxy-encodings").compatibility());
        assertEquals("unsupported", versionThreeZero.get("--disable-connection-reuse").compatibility());
        assertEquals("supported", versionThreeSeven.get("--disable-connection-reuse").compatibility());
        assertEquals("select", versionThreeSeven.get("--disable-connection-reuse").kind());
        assertEquals("flag", versionThreeZero.get("--timeout").kind());
        assertEquals("known_broken", versionThreeZero.get("--timeout").compatibility());
        assertTrue(versionThreeZero.get("--websocket-idle-timeout").versionRanges().isEmpty());
    }

    @Test
    void futureWireMockThreeVersionsUseLatestShapesAndUnknownCompatibility() {
        WireMockOptionMatrix.ResolvedCatalog catalog = matrix.resolve(new WireMockVersion(3, 14, 0));

        assertEquals("newer_unresearched", catalog.rangeStatus());
        assertTrue(catalog.options().stream().allMatch(option -> "unknown".equals(option.compatibility())));
        assertEquals("flag", catalog.options().stream()
                .filter(option -> "--timeout".equals(option.name()))
                .findFirst().orElseThrow().kind());
    }

    @Test
    void packagedMatrixContainsEveryStableReleaseAndCatalogOptionExactlyOnce() {
        assertEquals(30, matrix.stableReleases().size());
        assertEquals(WireMockOptionCatalog.baseDefinitions().size(),
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
