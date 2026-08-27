package com.github.letsrokk.mcp;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

public final class WireMockThreeZeroProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("mock-fleet.mcp.wiremock-image", "wiremock/wiremock:3.0.0");
    }
}
