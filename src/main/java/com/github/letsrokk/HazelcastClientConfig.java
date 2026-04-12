package com.github.letsrokk;

import com.hazelcast.client.HazelcastClient;
import com.hazelcast.core.HazelcastInstance;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;

@ApplicationScoped
public class HazelcastClientConfig {

    @Produces
    public HazelcastInstance createHazelcastInstance() {
        return createHazelcastClient();
    }

    void closeHazelcastInstance(@Disposes HazelcastInstance hazelcastInstance) {
        hazelcastInstance.shutdown();
    }

    HazelcastInstance createHazelcastClient() {
        return HazelcastClient.newHazelcastClient();
    }

}
