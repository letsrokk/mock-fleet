package com.github.letsrokk;

import io.smallrye.config.ConfigMapping;

import java.time.Duration;
import java.util.Optional;

@ConfigMapping(prefix = "mock-fleet")
public interface MockFleetConfig {

    String namespace();

    Duration inactivityThreshold();

    Duration podCreationTimeout();

    String wiremockPodNamePrefix();

    String wiremockContainerName();

    String wiremockImage();

    String wiremockImagePullPolicy();

    Optional<String> wiremockServiceAccountName();

    Optional<String> wiremockConfigPath();

    Optional<String> wiremockUserConfigMapName();

    String wiremockConfigKey();

    Optional<String> proxyDeploymentName();

    StorageConfig storage();

    HazelcastConfig hazelcast();

    interface StorageConfig {
        boolean persistent();
        String type();
        String pvcName();
        String mappingsPath();
        S3Config s3();
    }

    interface S3Config {
        String path();
    }

    interface HazelcastConfig {
        String clusterName();
        Optional<String> serviceDns();
        int port();
        int backupCount();
        int gracefulShutdownMaxWaitSeconds();
    }

}
