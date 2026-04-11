package com.github.letsrokk;

import com.github.letsrokk.exceptions.MockIdNotFound;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RequestRoutingResolverTest {

    @Test
    void hostModeExtractsMockIdFromHost() {
        RequestRoutingResolver resolver = resolver(MockFleetConfig.RoutingMode.HOST);

        ResolvedRequest resolvedRequest = resolver.resolve("demo.example.test:8080", "/nested/path?alpha=1");

        assertEquals("demo", resolvedRequest.mockId());
        assertEquals("/nested/path?alpha=1", resolvedRequest.upstreamRequestUri());
    }

    @Test
    void hostModeNormalizesMockIdFromHost() {
        RequestRoutingResolver resolver = resolver(MockFleetConfig.RoutingMode.HOST);

        ResolvedRequest resolvedRequest = resolver.resolve("MiXeD_Name.example.test", "/anything");

        assertEquals("mixedname", resolvedRequest.mockId());
    }

    @Test
    void hostModeRejectsMissingHost() {
        RequestRoutingResolver resolver = resolver(MockFleetConfig.RoutingMode.HOST);

        assertThrows(MockIdNotFound.class, () -> resolver.resolve("   ", "/anything"));
    }

    @Test
    void hostModeRejectsSingleLabelHosts() {
        RequestRoutingResolver resolver = resolver(MockFleetConfig.RoutingMode.HOST);

        assertThrows(MockIdNotFound.class, () -> resolver.resolve("localhost:8080", "/anything"));
    }

    @Test
    void pathModeExtractsMockIdAndStripsPrefix() {
        RequestRoutingResolver resolver = resolver(MockFleetConfig.RoutingMode.PATH);

        ResolvedRequest resolvedRequest = resolver.resolve("mock-fleet.localhost", "/demo/nested/path?alpha=1&beta=two");

        assertEquals("demo", resolvedRequest.mockId());
        assertEquals("/nested/path?alpha=1&beta=two", resolvedRequest.upstreamRequestUri());
    }

    @Test
    void pathModeForwardsRootWhenOnlyMockIdIsPresent() {
        RequestRoutingResolver resolver = resolver(MockFleetConfig.RoutingMode.PATH);

        ResolvedRequest resolvedRequest = resolver.resolve("mock-fleet.localhost", "/demo");

        assertEquals("demo", resolvedRequest.mockId());
        assertEquals("/", resolvedRequest.upstreamRequestUri());
    }

    @Test
    void pathModeRejectsMissingFirstSegment() {
        RequestRoutingResolver resolver = resolver(MockFleetConfig.RoutingMode.PATH);

        assertThrows(MockIdNotFound.class, () -> resolver.resolve("mock-fleet.localhost", "/"));
    }

    private RequestRoutingResolver resolver(MockFleetConfig.RoutingMode mode) {
        MockFleetConfig.RoutingConfig routingConfig = mock(MockFleetConfig.RoutingConfig.class);
        MockFleetConfig config = mock(MockFleetConfig.class);
        when(config.routing()).thenReturn(routingConfig);
        when(routingConfig.mode()).thenReturn(mode);

        RequestRoutingResolver resolver = new RequestRoutingResolver();
        resolver.config = config;
        return resolver;
    }
}
