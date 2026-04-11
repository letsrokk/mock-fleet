package com.github.letsrokk;

import com.hazelcast.core.Hazelcast;
import com.hazelcast.client.HazelcastClient;
import com.hazelcast.core.HazelcastInstance;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

@ApplicationScoped
public class HazelcastClientConfig {

    @Inject
    MockFleetConfig config;

    @Produces
    public HazelcastInstance createHazelcastInstance() {
        if (config.localDebug().enabled()) {
            return createEmbeddedHazelcastInstance();
        }
        return createHazelcastClient();
    }

    void closeHazelcastInstance(@Disposes HazelcastInstance hazelcastInstance) {
        hazelcastInstance.shutdown();
    }

    HazelcastInstance createEmbeddedHazelcastInstance() {
        return Hazelcast.newHazelcastInstance();
    }

    HazelcastInstance createHazelcastClient() {
        return HazelcastClient.newHazelcastClient();
    }

}
