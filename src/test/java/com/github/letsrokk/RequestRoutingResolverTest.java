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
        RequestRoutingResolver resolver = resolver();

        ResolvedRequest resolvedRequest = resolver.resolveHostProxyRequest("demo.mock-fleet.localhost:8080", "/nested/path?alpha=1");

        assertEquals("demo", resolvedRequest.mockId());
        assertEquals("/nested/path?alpha=1", resolvedRequest.upstreamRequestUri());
    }

    @Test
    void hostModeNormalizesMockIdFromHost() {
        RequestRoutingResolver resolver = resolver();

        ResolvedRequest resolvedRequest = resolver.resolveHostProxyRequest("MiXeD.mock-fleet.localhost", "/anything");

        assertEquals("mixed", resolvedRequest.mockId());
    }

    @Test
    void hostModeRejectsInvalidCharactersInsteadOfNormalizingToAnotherMock() {
        RequestRoutingResolver resolver = resolver();

        assertThrows(MockIdNotFound.class, () -> resolver.resolveHostProxyRequest("demo_.mock-fleet.localhost", "/anything"));
    }

    @Test
    void hostModeRejectsMockIdsLongerThanDnsLabelLimit() {
        RequestRoutingResolver resolver = resolver();

        assertThrows(MockIdNotFound.class,
                () -> resolver.resolveHostProxyRequest("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.mock-fleet.localhost",
                        "/anything"));
    }

    @Test
    void hostModeRejectsMissingHost() {
        RequestRoutingResolver resolver = resolver();

        assertThrows(MockIdNotFound.class, () -> resolver.resolveHostProxyRequest("   ", "/anything"));
    }

    @Test
    void hostModeRejectsUnrelatedHosts() {
        RequestRoutingResolver resolver = resolver();

        assertThrows(MockIdNotFound.class, () -> resolver.resolveHostProxyRequest("localhost:8080", "/anything"));
    }

    @Test
    void hostModeRejectsHostsOutsideFleetSubdomainSpace() {
        RequestRoutingResolver resolver = resolver();

        assertThrows(MockIdNotFound.class, () -> resolver.resolveHostProxyRequest("demo.other.localhost", "/anything"));
    }

    @Test
    void hostModeRejectsNestedSubdomains() {
        RequestRoutingResolver resolver = resolver();

        assertThrows(MockIdNotFound.class, () -> resolver.resolveHostProxyRequest("alpha.beta.mock-fleet.localhost", "/anything"));
    }

    @Test
    void hostModeRecognizesFleetHost() {
        RequestRoutingResolver resolver = resolver();

        assertTrue(resolver.isFleetHost("mock-fleet.localhost"));
        assertTrue(resolver.isFleetHost("MOCK-FLEET.LOCALHOST:8080"));
        assertFalse(resolver.isFleetHost("demo.mock-fleet.localhost"));
    }

    @Test
    void recognizesFleetSubdomainHosts() {
        RequestRoutingResolver resolver = resolver();

        assertTrue(resolver.isFleetSubdomain("demo.mock-fleet.localhost"));
        assertTrue(resolver.isFleetSubdomain("alpha.beta.mock-fleet.localhost"));
        assertFalse(resolver.isFleetSubdomain("mock-fleet.localhost"));
        assertFalse(resolver.isFleetSubdomain("other.localhost"));
    }

    @Test
    void pathModeExtractsMockIdAndStripsPrefix() {
        RequestRoutingResolver resolver = resolver();

        ResolvedRequest resolvedRequest = resolver.resolvePathProxyRequest("/demo/nested/path?alpha=1&beta=two");

        assertEquals("demo", resolvedRequest.mockId());
        assertEquals("/nested/path?alpha=1&beta=two", resolvedRequest.upstreamRequestUri());
    }

    @Test
    void pathModeForwardsRootWhenOnlyMockIdIsPresent() {
        RequestRoutingResolver resolver = resolver();

        ResolvedRequest resolvedRequest = resolver.resolvePathProxyRequest("/demo");

        assertEquals("demo", resolvedRequest.mockId());
        assertEquals("/", resolvedRequest.upstreamRequestUri());
    }

    @Test
    void pathModeRejectsMissingFirstSegment() {
        RequestRoutingResolver resolver = resolver();

        assertThrows(MockIdNotFound.class, () -> resolver.resolvePathProxyRequest("/"));
    }

    @Test
    void pathModeRejectsInvalidCharactersInsteadOfNormalizingToAnotherMock() {
        RequestRoutingResolver resolver = resolver();

        assertThrows(MockIdNotFound.class, () -> resolver.resolvePathProxyRequest("/demo_/anything"));
    }

    private RequestRoutingResolver resolver() {
        MockFleetConfig.RoutingConfig routingConfig = mock(MockFleetConfig.RoutingConfig.class);
        MockFleetConfig config = mock(MockFleetConfig.class);
        when(config.routing()).thenReturn(routingConfig);
        when(routingConfig.host()).thenReturn("mock-fleet.localhost");

        RequestRoutingResolver resolver = new RequestRoutingResolver();
        resolver.config = config;
        return resolver;
    }
}
