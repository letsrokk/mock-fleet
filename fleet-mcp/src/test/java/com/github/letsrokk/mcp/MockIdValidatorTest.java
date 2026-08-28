package com.github.letsrokk.mcp;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.regex.Pattern;
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

    @ParameterizedTest
    @ValueSource(strings = { "a", "orders", "order-api-12", "a1" })
    void publishedSchemaPatternMatchesValidDnsLabels(String mockId) {
        assertTrue(Pattern.compile(MockIdValidator.pattern()).matcher(mockId).find());
    }

    @ParameterizedTest
    @ValueSource(strings = { "Orders", "orders_", "!orders!", "two.parts", "two/parts" })
    void publishedSchemaPatternDoesNotSubstringMatchInvalidDnsLabels(String mockId) {
        assertFalse(Pattern.compile(MockIdValidator.pattern()).matcher(mockId).find());
    }
}
