package com.github.letsrokk;

import com.hazelcast.cluster.Cluster;
import com.hazelcast.cluster.ClusterState;
import com.hazelcast.cluster.Member;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.LifecycleService;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HazelcastReadinessCheckTest {

    @Test
    void reportsUpWhenHazelcastLifecycleRunsAndClusterIsActive() {
        HazelcastReadinessCheck check = check(true, ClusterState.ACTIVE);

        HealthCheckResponse response = check.call();

        assertTrue(response.getStatus() == HealthCheckResponse.Status.UP);
        assertTrue(response.getData().orElseThrow().containsKey("memberCount"));
    }

    @Test
    void reportsDownWhenLifecycleIsStopped() {
        HazelcastReadinessCheck check = check(false, ClusterState.ACTIVE);

        HealthCheckResponse response = check.call();

        assertTrue(response.getStatus() == HealthCheckResponse.Status.DOWN);
    }

    @Test
    void reportsDownWhenClusterIsNotActive() {
        HazelcastReadinessCheck check = check(true, ClusterState.PASSIVE);

        HealthCheckResponse response = check.call();

        assertTrue(response.getStatus() == HealthCheckResponse.Status.DOWN);
    }

    @Test
    void reportsDownWhenClusterIsMissing() {
        HazelcastInstance hazelcastInstance = mock(HazelcastInstance.class);
        LifecycleService lifecycleService = mock(LifecycleService.class);
        HazelcastReadinessCheck check = new HazelcastReadinessCheck();
        check.hazelcastInstance = hazelcastInstance;
        check.config = config();
        when(hazelcastInstance.getLifecycleService()).thenReturn(lifecycleService);
        when(lifecycleService.isRunning()).thenReturn(true);

        HealthCheckResponse response = check.call();

        assertTrue(response.getStatus() == HealthCheckResponse.Status.DOWN);
        assertFalse(response.getData().orElseThrow().containsKey("memberCount"));
    }

    private HazelcastReadinessCheck check(boolean running, ClusterState clusterState) {
        HazelcastInstance hazelcastInstance = mock(HazelcastInstance.class);
        LifecycleService lifecycleService = mock(LifecycleService.class);
        Cluster cluster = mock(Cluster.class);
        Member member = mock(Member.class);
        HazelcastReadinessCheck check = new HazelcastReadinessCheck();
        UUID memberUuid = UUID.fromString("11111111-1111-1111-1111-111111111111");

        check.hazelcastInstance = hazelcastInstance;
        check.config = config();
        when(hazelcastInstance.getLifecycleService()).thenReturn(lifecycleService);
        when(hazelcastInstance.getCluster()).thenReturn(cluster);
        when(lifecycleService.isRunning()).thenReturn(running);
        when(cluster.getClusterState()).thenReturn(clusterState);
        when(cluster.getMembers()).thenReturn(Set.of(member));
        when(cluster.getLocalMember()).thenReturn(member);
        when(member.getUuid()).thenReturn(memberUuid);
        return check;
    }

    private MockFleetConfig config() {
        MockFleetConfig config = mock(MockFleetConfig.class);
        MockFleetConfig.HazelcastConfig hazelcast = mock(MockFleetConfig.HazelcastConfig.class);
        when(config.hazelcast()).thenReturn(hazelcast);
        when(hazelcast.clusterName()).thenReturn("mock-fleet-test");
        return config;
    }
}
