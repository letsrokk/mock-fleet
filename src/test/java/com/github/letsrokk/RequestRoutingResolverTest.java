package com.github.letsrokk;

import com.github.letsrokk.exceptions.MockIdNotFound;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RequestRoutingResolverTest {

    @Test
    void hostModeExtractsMockIdFromHost() {
        RequestRoutingResolver resolver = resolver(MockFleetConfig.RoutingMode.HOST);

        ResolvedRequest resolvedRequest = resolver.resolve("demo.mock-fleet.localhost:8080", "/nested/path?alpha=1");

        assertEquals("demo", resolvedRequest.mockId());
        assertEquals("/nested/path?alpha=1", resolvedRequest.upstreamRequestUri());
    }

    @Test
    void hostModeNormalizesMockIdFromHost() {
        RequestRoutingResolver resolver = resolver(MockFleetConfig.RoutingMode.HOST);

        ResolvedRequest resolvedRequest = resolver.resolve("MiXeD.mock-fleet.localhost", "/anything");

        assertEquals("mixed", resolvedRequest.mockId());
    }

    @Test
    void hostModeRejectsInvalidCharactersInsteadOfNormalizingToAnotherMock() {
        RequestRoutingResolver resolver = resolver(MockFleetConfig.RoutingMode.HOST);

        assertThrows(MockIdNotFound.class, () -> resolver.resolve("demo_.mock-fleet.localhost", "/anything"));
    }

    @Test
    void hostModeRejectsMockIdsLongerThanDnsLabelLimit() {
        RequestRoutingResolver resolver = resolver(MockFleetConfig.RoutingMode.HOST);

        assertThrows(MockIdNotFound.class,
                () -> resolver.resolve("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.mock-fleet.localhost",
                        "/anything"));
    }

    @Test
    void hostModeRejectsMissingHost() {
        RequestRoutingResolver resolver = resolver(MockFleetConfig.RoutingMode.HOST);

        assertThrows(MockIdNotFound.class, () -> resolver.resolve("   ", "/anything"));
    }

    @Test
    void hostModeRejectsUnrelatedHosts() {
        RequestRoutingResolver resolver = resolver(MockFleetConfig.RoutingMode.HOST);

        assertThrows(MockIdNotFound.class, () -> resolver.resolve("localhost:8080", "/anything"));
    }

    @Test
    void hostModeRejectsHostsOutsideFleetSubdomainSpace() {
        RequestRoutingResolver resolver = resolver(MockFleetConfig.RoutingMode.HOST);

        assertThrows(MockIdNotFound.class, () -> resolver.resolve("demo.other.localhost", "/anything"));
    }

    @Test
    void hostModeRejectsNestedSubdomains() {
        RequestRoutingResolver resolver = resolver(MockFleetConfig.RoutingMode.HOST);

        assertThrows(MockIdNotFound.class, () -> resolver.resolve("alpha.beta.mock-fleet.localhost", "/anything"));
    }

    @Test
    void hostModeRecognizesFleetHost() {
        RequestRoutingResolver resolver = resolver(MockFleetConfig.RoutingMode.HOST);

        assertTrue(resolver.isFleetHost("mock-fleet.localhost"));
        assertTrue(resolver.isFleetHost("MOCK-FLEET.LOCALHOST:8080"));
        assertFalse(resolver.isFleetHost("demo.mock-fleet.localhost"));
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

    @Test
    void pathModeRejectsInvalidCharactersInsteadOfNormalizingToAnotherMock() {
        RequestRoutingResolver resolver = resolver(MockFleetConfig.RoutingMode.PATH);

        assertThrows(MockIdNotFound.class, () -> resolver.resolve("mock-fleet.localhost", "/demo_/anything"));
    }

    private RequestRoutingResolver resolver(MockFleetConfig.RoutingMode mode) {
        MockFleetConfig.RoutingConfig routingConfig = mock(MockFleetConfig.RoutingConfig.class);
        MockFleetConfig config = mock(MockFleetConfig.class);
        when(config.routing()).thenReturn(routingConfig);
        when(routingConfig.mode()).thenReturn(mode);
        when(routingConfig.host()).thenReturn("mock-fleet.localhost");

        RequestRoutingResolver resolver = new RequestRoutingResolver();
        resolver.config = config;
        return resolver;
    }
}
