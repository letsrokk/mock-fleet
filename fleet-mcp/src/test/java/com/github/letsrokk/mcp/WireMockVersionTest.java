package com.github.letsrokk.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class WireMockVersionTest {

    @ParameterizedTest
    @CsvSource({
            "wiremock/wiremock:3.13.2-2, 3.13.2",
            "ghcr.io/acme/wiremock:3.0.0, 3.0.0",
            "3.12.1, 3.12.1",
            "WireMock 3.13.2, 3.13.2"
    })
    void parsesLeadingWireMockThreeVersion(String input, String expected) {
        assertEquals(expected, WireMockVersion.parse(input).toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "wiremock/wiremock:latest", "wiremock/wiremock:2.35.1", "wiremock/wiremock@sha256:abc", "3", "nonsense"
    })
    void rejectsUnsupportedOrUnparseableImageReferences(String input) {
        assertThrows(IllegalArgumentException.class, () -> WireMockVersion.parse(input));
    }

    @ParameterizedTest
    @CsvSource({
            "wiremock/wiremock:3.13.2-2, 3.13.2",
            "registry.example:5000/wiremock:3.0.0, 3.0.0",
            "wiremock/wiremock:3.13.2@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa, 3.13.2"
    })
    void parsesPinnedContainerImageTags(String input, String expected) {
        assertEquals(expected, WireMockVersion.parseImage(input).toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "3.13.2", "WireMock 3.13.2", "wiremock/wiremock:latest", "wiremock/wiremock:2.35.1",
            "wiremock/wiremock@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "wiremock/wiremock:2.0-3.13.2"
    })
    void rejectsImagesWithoutLeadingPinnedThreeVersionTag(String input) {
        assertThrows(IllegalArgumentException.class, () -> WireMockVersion.parseImage(input));
    }
}
