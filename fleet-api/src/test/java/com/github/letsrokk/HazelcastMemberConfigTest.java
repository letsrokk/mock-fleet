package com.github.letsrokk;

import com.hazelcast.config.Config;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HazelcastMemberConfigTest {

    @Test
    void configuresStandaloneEmbeddedMember() {
        HazelcastMemberConfig hazelcastConfig = new HazelcastMemberConfig(config(Optional.empty()));

        Config memberConfig = hazelcastConfig.memberConfig();

        assertEquals("mock-fleet-test", memberConfig.getClusterName());
        assertEquals(5701, memberConfig.getNetworkConfig().getPort());
        assertFalse(memberConfig.getNetworkConfig().isPortAutoIncrement());
        assertFalse(memberConfig.getNetworkConfig().getJoin().getAutoDetectionConfig().isEnabled());
        assertFalse(memberConfig.getNetworkConfig().getJoin().getMulticastConfig().isEnabled());
        assertFalse(memberConfig.getNetworkConfig().getJoin().getKubernetesConfig().isEnabled());
        assertEquals(1, memberConfig.getMapConfig(HazelcastMemberConfig.POD_MAP_NAME).getBackupCount());
        assertEquals(1, memberConfig.getMapConfig(HazelcastMemberConfig.POD_LIFECYCLE_MAP_NAME).getBackupCount());
        assertEquals(1, memberConfig.getMapConfig(HazelcastMemberConfig.LAST_ACCESS_MAP_NAME).getBackupCount());
        assertEquals("GRACEFUL", memberConfig.getProperty("hazelcast.shutdownhook.policy"));
        assertEquals("300", memberConfig.getProperty("hazelcast.graceful.shutdown.max.wait"));
    }

    @Test
    void configuresKubernetesDnsDiscovery() {
        HazelcastMemberConfig hazelcastConfig = new HazelcastMemberConfig(
                config(Optional.of("mock-fleet-api-hazelcast.mock-fleet.svc.cluster.local")));

        var kubernetes = hazelcastConfig.memberConfig().getNetworkConfig().getJoin().getKubernetesConfig();

        assertTrue(kubernetes.isEnabled());
        assertEquals("mock-fleet-api-hazelcast.mock-fleet.svc.cluster.local",
                kubernetes.getProperty("service-dns"));
        assertEquals("5701", kubernetes.getProperty("service-port"));
    }

    private MockFleetConfig config(Optional<String> serviceDns) {
        MockFleetConfig config = mock(MockFleetConfig.class);
        MockFleetConfig.HazelcastConfig hazelcast = mock(MockFleetConfig.HazelcastConfig.class);
        when(config.hazelcast()).thenReturn(hazelcast);
        when(hazelcast.clusterName()).thenReturn("mock-fleet-test");
        when(hazelcast.serviceDns()).thenReturn(serviceDns);
        when(hazelcast.port()).thenReturn(5701);
        when(hazelcast.backupCount()).thenReturn(1);
        when(hazelcast.gracefulShutdownMaxWaitSeconds()).thenReturn(300);
        return config;
    }
}
