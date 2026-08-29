package com.github.letsrokk;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WireMockVersionTest {

    @ParameterizedTest
    @CsvSource({
            "wiremock/wiremock:3.0.0, 3.0.0",
            "wiremock/wiremock:3.13.2-2, 3.13.2",
            "wiremock/wiremock:3.13.2-02, 3.13.2",
            "registry.example.test/team/wiremock:3.14.0-1, 3.14.0"
    })
    void parsesExactWireMockThreeImageTags(String image, String expected) {
        assertEquals(expected, WireMockVersion.parseImage(image).toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "wiremock/wiremock:latest",
            "wiremock/wiremock:3",
            "wiremock/wiremock:3.13",
            "wiremock/wiremock:2.35.0",
            "wiremock/wiremock:4.0.0",
            "wiremock/wiremock:3.14.0-beta.1",
            "wiremock/wiremock@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    })
    void rejectsImagesOutsideThePinnedWireMockThreeContract(String image) {
        assertThrows(IllegalArgumentException.class, () -> WireMockVersion.parseImage(image));
    }
}
