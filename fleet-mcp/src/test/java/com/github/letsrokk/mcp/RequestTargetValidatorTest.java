package com.github.letsrokk.mcp;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RequestTargetValidatorTest {

    @ParameterizedTest
    @ValueSource(strings = { "/orders", "/orders?state=open", "orders/1" })
    void acceptsOrdinaryMockTraffic(String target) {
        assertDoesNotThrow(() -> RequestTargetValidator.requireMockTraffic(target));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/__admin", "/__admin/", "/__admin;ignored", "/%5f%5fadmin/mappings",
            "//__admin/mappings", "/a/../__admin", "/safe/%2e%2e/__admin/mappings",
            "/%2f__admin/mappings", "/__admin%2fmappings", "/%252e%252e/%255f%255fadmin",
            "/%252525255f%252525255fadmin"
    })
    void rejectsAdminAndAmbiguousTargets(String target) {
        assertThrows(IllegalArgumentException.class, () -> RequestTargetValidator.requireMockTraffic(target));
    }
}
