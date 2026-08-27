package com.github.letsrokk.mcp;

import java.util.Map;

public record MockResources(Map<String, String> requests, Map<String, String> limits) {
}
