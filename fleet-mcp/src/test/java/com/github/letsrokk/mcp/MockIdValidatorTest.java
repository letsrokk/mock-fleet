package com.github.letsrokk.mcp;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MockIdValidatorTest {

    @ParameterizedTest
    @ValueSource(strings = { "a", "orders", "order-api-12", "a1" })
    void acceptsDnsLabels(String mockId) {
        assertDoesNotThrow(() -> MockIdValidator.requireValid(mockId));
    }

    @ParameterizedTest
    @ValueSource(strings = { "", "A", "-orders", "orders-", "two.parts", "two/parts",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" })
    void rejectsValuesThatAreNotDnsLabels(String mockId) {
        assertThrows(IllegalArgumentException.class, () -> MockIdValidator.requireValid(mockId));
    }
}
