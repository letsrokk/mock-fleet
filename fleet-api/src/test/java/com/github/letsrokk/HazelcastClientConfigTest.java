package com.github.letsrokk;

import com.hazelcast.core.HazelcastInstance;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

class HazelcastClientConfigTest {

    @Test
    void usesExternalHazelcastClient() {
        HazelcastInstance hazelcastClient = mock(HazelcastInstance.class);

        HazelcastClientConfig hazelcastClientConfig = new HazelcastClientConfig() {
            @Override
            HazelcastInstance createHazelcastClient() {
                return hazelcastClient;
            }
        };

        assertSame(hazelcastClient, hazelcastClientConfig.createHazelcastInstance());
    }
}
