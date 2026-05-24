package com.github.letsrokk;

import io.smallrye.config.ConfigMapping;

import java.time.Duration;
import java.util.Optional;

@ConfigMapping(prefix = "mock-fleet")
public interface MockFleetConfig {

    String namespace();

    Duration inactivityThreshold();

    Duration podCreationTimeout();

    String wiremockContainerName();

    String wiremockImage();

    String wiremockImagePullPolicy();

    Optional<String> wiremockConfigPath();

    Optional<String> wiremockUserConfigMapName();

    String wiremockConfigKey();

    Optional<String> proxyDeploymentName();

    StorageConfig storage();

    interface StorageConfig {
        boolean persistent();
        String type();
        String pvcName();
        S3Config s3();
    }

    interface S3Config {
        String path();
    }

}
