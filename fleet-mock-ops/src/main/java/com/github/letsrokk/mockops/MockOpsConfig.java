package com.github.letsrokk.mockops;

import io.smallrye.config.ConfigMapping;
import java.util.Optional;

@ConfigMapping(prefix = "mock-fleet.mock-ops")
interface MockOpsConfig {
    String namespace();
    String catalogConfigMapName();
    String baselineConfigMapName();
    String userConfigMapName();
    String configKey();
    String registryUrl();
    String repository();
    Optional<String> imageRepository();
    String defaultVersionConstraint();
    int minorLines();
    int pageSize();
    Optional<String> registryUsername();
    Optional<String> registryPassword();
}
