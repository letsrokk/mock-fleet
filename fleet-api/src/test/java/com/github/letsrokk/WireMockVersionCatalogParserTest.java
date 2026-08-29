package com.github.letsrokk;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WireMockVersionCatalogParserTest {

    private final WireMockVersionCatalogParser parser = new WireMockVersionCatalogParser();

    @Test
    void parsesSelectableAndRetainedEntriesIntoAnImmutableResourceVersionedSnapshot() {
        WireMockVersionCatalog catalog = parser.parse(configMap("42", Map.of(
                "defaultVersion", "3.13.2",
                "selectable.3.13.2", "wiremock/wiremock:3.13.2-2",
                "selectable.3.12.1", "registry.example.test/team/wiremock:3.12.1",
                "retained.3.11.0", "wiremock/wiremock:3.11.0-1")));

        assertEquals(new WireMockVersion(3, 13, 2), catalog.defaultVersion());
        assertEquals("42", catalog.resourceVersion());
        assertEquals("wiremock/wiremock:3.13.2-2",
                catalog.versions().get(new WireMockVersion(3, 13, 2)).image());
        assertTrue(catalog.versions().get(new WireMockVersion(3, 13, 2)).selectable());
        assertEquals(false, catalog.versions().get(new WireMockVersion(3, 11, 0)).selectable());
        assertThrows(UnsupportedOperationException.class,
                () -> catalog.versions().clear());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "latest",
            "3",
            "3.13",
            "3.13.02",
            "3.13.2-beta.1",
            "2.35.1",
            "4.0.0"
    })
    void rejectsMalformedDefaultVersions(String defaultVersion) {
        assertThrows(IllegalArgumentException.class, () -> parser.parse(configMap("42", Map.of(
                "defaultVersion", defaultVersion,
                "selectable.3.13.2", "wiremock/wiremock:3.13.2-2"))));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "wiremock/wiremock:latest",
            "wiremock/wiremock:3.13",
            "wiremock/wiremock:3.13.2-beta.1",
            "wiremock/wiremock:3.13.2-alpine",
            "wiremock/wiremock:2.35.1",
            "wiremock/wiremock:4.0.0",
            "wiremock/wiremock:3.13.2@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "3.13.2"
    })
    void rejectsImagesWithoutAnExactStableWireMockThreeTag(String image) {
        assertThrows(IllegalArgumentException.class, () -> parser.parse(configMap("42", Map.of(
                "defaultVersion", "3.13.2",
                "selectable.3.13.2", image))));
    }

    @Test
    void rejectsAnImageWhoseTagDoesNotMatchItsVersionKey() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse(configMap("42", Map.of(
                "defaultVersion", "3.13.2",
                "selectable.3.13.2", "wiremock/wiremock:3.12.1-2"))));
    }

    @Test
    void rejectsTheSameSemanticVersionAcrossSelectableAndRetainedSections() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse(configMap("42", Map.of(
                "defaultVersion", "3.13.2",
                "selectable.3.13.2", "wiremock/wiremock:3.13.2-2",
                "retained.3.13.2", "wiremock/wiremock:3.13.2-1"))));
    }

    @Test
    void requiresTheDefaultVersionToBePresentAndSelectable() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse(configMap("42", Map.of(
                "selectable.3.13.2", "wiremock/wiremock:3.13.2-2"))));
        assertThrows(IllegalArgumentException.class, () -> parser.parse(configMap("42", Map.of(
                "defaultVersion", "3.13.2",
                "retained.3.13.2", "wiremock/wiremock:3.13.2-2"))));
        assertThrows(IllegalArgumentException.class, () -> parser.parse(configMap("42", Map.of(
                "defaultVersion", "3.13.2",
                "selectable.3.12.1", "wiremock/wiremock:3.12.1-2"))));
    }

    @Test
    void rejectsUnknownOrMalformedCatalogKeys() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse(configMap("42", Map.of(
                "defaultVersion", "3.13.2",
                "selectable.3.13.2", "wiremock/wiremock:3.13.2-2",
                "other.3.12.1", "wiremock/wiremock:3.12.1-2"))));
        assertThrows(IllegalArgumentException.class, () -> parser.parse(configMap("42", Map.of(
                "defaultVersion", "3.13.2",
                "selectable.3.13.02", "wiremock/wiremock:3.13.2-2"))));
    }

    private ConfigMap configMap(String resourceVersion, Map<String, String> data) {
        return new ConfigMapBuilder()
                .withNewMetadata()
                    .withName("wiremock-version-catalog")
                    .withResourceVersion(resourceVersion)
                .endMetadata()
                .withData(new LinkedHashMap<>(data))
                .build();
    }
}
