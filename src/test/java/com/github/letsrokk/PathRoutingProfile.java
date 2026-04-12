package com.github.letsrokk;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

public class PathRoutingProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "mock-fleet.routing.mode", "PATH",
                "quarkus.quinoa", "true");
    }
}
