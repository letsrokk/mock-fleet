package com.github.letsrokk.mcp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ToolCapabilityRegistryTest {

    @Test
    void baselineToolsSupportWireMockThreeZero() {
        assertTrue(ToolCapabilityRegistry.supports("list_stubs", new WireMockVersion(3, 0, 0)));
        assertTrue(ToolCapabilityRegistry.maximumVersion("list_stubs").isEmpty());
    }

    @Test
    void unmatchedStubsRequireWireMockThreeThirteen() {
        assertFalse(ToolCapabilityRegistry.supports("list_unmatched_stubs", new WireMockVersion(3, 12, 9)));
        assertTrue(ToolCapabilityRegistry.supports("list_unmatched_stubs", new WireMockVersion(3, 13, 0)));
    }

    @Test
    void getBodyFileRequiresWireMockThreeSeven() {
        assertFalse(ToolCapabilityRegistry.supports("get_body_file", new WireMockVersion(3, 6, 9)));
        assertTrue(ToolCapabilityRegistry.supports("get_body_file", new WireMockVersion(3, 7, 0)));
    }
}
