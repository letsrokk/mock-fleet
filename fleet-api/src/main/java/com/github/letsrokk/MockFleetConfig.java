package com.github.letsrokk;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithName;

import java.time.Duration;
import java.util.Optional;

@ConfigMapping(prefix = "mock-fleet")
public interface MockFleetConfig {

    String namespace();

    Duration inactivityThreshold();

    Duration podCreationTimeout();

    int maxActiveMocks();

    int maxConcurrentStarts();

    int queuedStartCapacity();

    String wiremockPodNamePrefix();

    String wiremockContainerName();

    String wiremockImage();

    String wiremockImagePullPolicy();

    long wiremockTerminationGracePeriodSeconds();

    Optional<String> wiremockServiceAccountName();

    Optional<String> wiremockConfigPath();

    Optional<String> wiremockUserConfigMapName();

    @WithName("wiremock-version-catalog-config-map-name")
    Optional<String> wiremockVersionCatalogConfigMapName();

    String wiremockConfigKey();

    Optional<String> proxyDeploymentName();

    WireMockResourcePolicyConfig wiremockResourcePolicy();

    MappingsConfig mappings();

    ProxyConfig proxy();

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

    interface ProxyConfig {
        RoutingConfig routing();
    }

    interface MappingsConfig {
        int maxDepth();
        int maxEntries();
    }

    interface RoutingConfig {
        RoutingMode mode();
        String host();
    }

    enum RoutingMode {
        HOST,
        PATH
    }

    interface HazelcastConfig {
        String clusterName();
        Optional<String> serviceDns();
        int port();
        int backupCount();
        int gracefulShutdownMaxWaitSeconds();
    }

    interface WireMockResourcePolicyConfig {
        ResourceValues requestFloor();
        ResourceValues limitCeiling();
    }

    interface ResourceValues {
        String cpu();
        String memory();
    }

}
