package com.github.letsrokk;

import com.hazelcast.core.HazelcastInstance;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HazelcastClientConfigTest {

    @Test
    void usesEmbeddedHazelcastWhenLocalDebugEnabled() {
        MockFleetConfig config = mock(MockFleetConfig.class);
        MockFleetConfig.LocalDebugConfig localDebugConfig = mock(MockFleetConfig.LocalDebugConfig.class);
        HazelcastInstance embeddedHazelcast = mock(HazelcastInstance.class);
        HazelcastInstance hazelcastClient = mock(HazelcastInstance.class);

        HazelcastClientConfig hazelcastClientConfig = new HazelcastClientConfig() {
            @Override
            HazelcastInstance createEmbeddedHazelcastInstance() {
                return embeddedHazelcast;
            }

            @Override
            HazelcastInstance createHazelcastClient() {
                return hazelcastClient;
            }
        };
        hazelcastClientConfig.config = config;

        when(config.localDebug()).thenReturn(localDebugConfig);
        when(localDebugConfig.enabled()).thenReturn(true);

        assertSame(embeddedHazelcast, hazelcastClientConfig.createHazelcastInstance());
    }

    @Test
    void usesExternalHazelcastClientWhenLocalDebugDisabled() {
        MockFleetConfig config = mock(MockFleetConfig.class);
        MockFleetConfig.LocalDebugConfig localDebugConfig = mock(MockFleetConfig.LocalDebugConfig.class);
        HazelcastInstance embeddedHazelcast = mock(HazelcastInstance.class);
        HazelcastInstance hazelcastClient = mock(HazelcastInstance.class);

        HazelcastClientConfig hazelcastClientConfig = new HazelcastClientConfig() {
            @Override
            HazelcastInstance createEmbeddedHazelcastInstance() {
                return embeddedHazelcast;
            }

            @Override
            HazelcastInstance createHazelcastClient() {
                return hazelcastClient;
            }
        };
        hazelcastClientConfig.config = config;

        when(config.localDebug()).thenReturn(localDebugConfig);
        when(localDebugConfig.enabled()).thenReturn(false);

        assertSame(hazelcastClient, hazelcastClientConfig.createHazelcastInstance());
    }
}
