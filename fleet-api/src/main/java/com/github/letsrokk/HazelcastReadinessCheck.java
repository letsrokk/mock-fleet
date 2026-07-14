package com.github.letsrokk;

import com.hazelcast.cluster.Cluster;
import com.hazelcast.cluster.ClusterState;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.LifecycleService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Readiness;

@Readiness
@ApplicationScoped
public class HazelcastReadinessCheck implements HealthCheck {

    @Inject
    HazelcastInstance hazelcastInstance;

    @Inject
    MockFleetConfig config;

    @Override
    public HealthCheckResponse call() {
        LifecycleService lifecycleService = hazelcastInstance.getLifecycleService();
        Cluster cluster = hazelcastInstance.getCluster();
        boolean lifecycleRunning = lifecycleService != null && lifecycleService.isRunning();
        ClusterState clusterState = cluster == null ? null : cluster.getClusterState();
        boolean active = lifecycleRunning && clusterState == ClusterState.ACTIVE;

        HealthCheckResponseBuilder response = HealthCheckResponse.named("hazelcast")
                .withData("lifecycleRunning", lifecycleRunning)
                .withData("clusterState", clusterState == null ? "unknown" : clusterState.name());

        if (cluster != null) {
            response
                    .withData("clusterName", config.hazelcast().clusterName())
                    .withData("memberCount", cluster.getMembers().size());
            if (cluster.getLocalMember() != null) {
                response.withData("localMemberUuid", cluster.getLocalMember().getUuid().toString());
            }
        }

        return active ? response.up().build() : response.down().build();
    }
}
