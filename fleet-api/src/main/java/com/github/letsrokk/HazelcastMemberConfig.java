package com.github.letsrokk;

import com.hazelcast.config.Config;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

@ApplicationScoped
public class HazelcastMemberConfig {

    static final String POD_MAP_NAME = "mock-pod-name-map";
    static final String POD_LIFECYCLE_MAP_NAME = "mock-pod-lifecycle-map";
    static final String LAST_ACCESS_MAP_NAME = "last-access-time-map";

    private final MockFleetConfig config;

    @Inject
    public HazelcastMemberConfig(MockFleetConfig config) {
        this.config = config;
    }

    @Produces
    @ApplicationScoped
    public HazelcastInstance createHazelcastInstance() {
        return createEmbeddedHazelcast(memberConfig());
    }

    void closeHazelcastInstance(@Disposes HazelcastInstance hazelcastInstance) {
        hazelcastInstance.shutdown();
    }

    Config memberConfig() {
        MockFleetConfig.HazelcastConfig hazelcast = config.hazelcast();
        Config memberConfig = new Config();
        memberConfig.setClusterName(hazelcast.clusterName());
        memberConfig.setProperty("hazelcast.shutdownhook.policy", "GRACEFUL");
        memberConfig.setProperty("hazelcast.graceful.shutdown.max.wait",
                Integer.toString(hazelcast.gracefulShutdownMaxWaitSeconds()));
        memberConfig.getMapConfig(POD_MAP_NAME).setBackupCount(hazelcast.backupCount());
        memberConfig.getMapConfig(POD_LIFECYCLE_MAP_NAME).setBackupCount(hazelcast.backupCount());
        memberConfig.getMapConfig(LAST_ACCESS_MAP_NAME).setBackupCount(hazelcast.backupCount());

        var network = memberConfig.getNetworkConfig();
        network.setPort(hazelcast.port()).setPortAutoIncrement(false);
        var join = network.getJoin();
        join.getAutoDetectionConfig().setEnabled(false);
        join.getMulticastConfig().setEnabled(false);
        join.getTcpIpConfig().setEnabled(false);
        hazelcast.serviceDns()
                .filter(serviceDns -> !serviceDns.isBlank())
                .ifPresent(serviceDns -> join.getKubernetesConfig()
                        .setEnabled(true)
                        .setProperty("service-dns", serviceDns)
                        .setProperty("service-port", Integer.toString(hazelcast.port())));

        return memberConfig;
    }

    HazelcastInstance createEmbeddedHazelcast(Config memberConfig) {
        return Hazelcast.newHazelcastInstance(memberConfig);
    }
}
