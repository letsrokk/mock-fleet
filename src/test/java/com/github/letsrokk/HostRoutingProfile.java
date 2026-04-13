package com.github.letsrokk;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

public class HostRoutingProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "mock-fleet.routing.mode", "HOST");
    }
}
