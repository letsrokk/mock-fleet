package com.github.letsrokk.mcp;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BodyFileNameTest {

    @ParameterizedTest
    @ValueSource(strings = { "response.json", "orders/response-1.json", "assets/logo.png" })
    void acceptsRelativeBodyFileNames(String value) {
        assertDoesNotThrow(() -> BodyFileName.requireValid(value));
    }

    @ParameterizedTest
    @ValueSource(strings = { "", "/absolute", "../secret", "orders/../secret", "orders\\secret", ".", "orders//file" })
    void rejectsTraversalAndAmbiguousNames(String value) {
        assertThrows(IllegalArgumentException.class, () -> BodyFileName.requireValid(value));
    }

    @ParameterizedTest
    @ValueSource(strings = { "orders/response one.json" })
    void encodesEachUrlPathSegment(String value) {
        assertEquals("orders/response%20one.json", BodyFileName.toUrlPath(value));
    }
}
