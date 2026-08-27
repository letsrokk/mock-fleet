package com.github.letsrokk.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.net.URI;
import org.junit.jupiter.api.Test;

class FleetProxyRequestFactoryTest {

    @Test
    void pathModeUsesProxyServiceAndPrefixesMockId() {
        var factory = new FleetProxyRequestFactory(
                URI.create("http://release-proxy.team.svc.corp.internal"), RoutingMode.PATH, "fleet.example.test");

        var request = factory.create("orders", "/__admin/mappings?limit=50");

        assertEquals("http://release-proxy.team.svc.corp.internal/orders/__admin/mappings?limit=50",
                request.uri().toString());
        assertNull(request.hostHeader());
    }

    @Test
    void hostModeUsesProxyServiceAndSelectsMockWithHostHeader() {
        var factory = new FleetProxyRequestFactory(
                URI.create("http://release-proxy.team.svc.corp.internal"), RoutingMode.HOST, "fleet.example.test");

        var request = factory.create("orders", "/__admin/mappings?limit=50");

        assertEquals("http://release-proxy.team.svc.corp.internal/__admin/mappings?limit=50",
                request.uri().toString());
        assertEquals("orders.fleet.example.test", request.hostHeader());
    }

    @Test
    void baseUrlOverrideMayContainAPathPrefix() {
        var factory = new FleetProxyRequestFactory(
                URI.create("http://localhost:8080/proxy"), RoutingMode.PATH, "fleet.example.test");

        assertEquals("http://localhost:8080/proxy/orders/status",
                factory.create("orders", "/status").uri().toString());
    }
}
