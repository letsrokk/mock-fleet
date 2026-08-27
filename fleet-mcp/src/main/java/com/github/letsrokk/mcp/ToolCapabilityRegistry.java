package com.github.letsrokk.mcp;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class ToolCapabilityRegistry {

    private static final WireMockVersion BASELINE = new WireMockVersion(3, 0, 0);
    private static final ToolCapability BASELINE_CAPABILITY = new ToolCapability(BASELINE, Optional.empty());
    private static final Map<String, ToolCapability> CAPABILITIES = Map.of(
            "get_body_file", new ToolCapability(new WireMockVersion(3, 7, 0), Optional.empty()),
            "list_unmatched_stubs", new ToolCapability(new WireMockVersion(3, 13, 0), Optional.empty()));
    private static final Set<String> WIREMOCK_TOOLS = Set.of(
            "list_stubs", "list_unmatched_stubs", "get_stub", "create_stub", "update_stub", "delete_stub",
            "persist_stub", "unpersist_stub", "send_request", "find_requests", "count_requests",
            "list_unmatched_requests", "get_near_misses", "reset_request_journal", "start_recording",
            "recording_status", "stop_recording", "snapshot_requests", "list_body_files", "get_body_file",
            "put_body_file", "delete_body_file", "list_scenarios", "reset_scenarios");

    private ToolCapabilityRegistry() {
    }

    public static boolean supports(String toolName, WireMockVersion version) {
        ToolCapability capability = capability(toolName);
        return version.compareTo(capability.minimum()) >= 0
                && capability.maximum().map(maximum -> version.compareTo(maximum) <= 0).orElse(true);
    }

    public static WireMockVersion minimumVersion(String toolName) {
        return capability(toolName).minimum();
    }

    public static Optional<WireMockVersion> maximumVersion(String toolName) {
        return capability(toolName).maximum();
    }

    public static boolean isWireMockTool(String toolName) {
        return WIREMOCK_TOOLS.contains(toolName);
    }

    private static ToolCapability capability(String toolName) {
        return CAPABILITIES.getOrDefault(toolName, BASELINE_CAPABILITY);
    }

    private record ToolCapability(WireMockVersion minimum, Optional<WireMockVersion> maximum) {
    }
}
