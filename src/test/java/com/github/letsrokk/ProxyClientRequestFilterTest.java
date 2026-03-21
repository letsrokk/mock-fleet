package com.github.letsrokk;

import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProxyClientRequestFilterTest {

    @Test
    void filterCopiesIncomingHeadersToOutgoingRequest() throws Exception {
        HttpHeaders headers = mock(HttpHeaders.class);
        ClientRequestContext requestContext = mock(ClientRequestContext.class);
        MultivaluedMap<String, String> incoming = new MultivaluedHashMap<>();
        incoming.add("Host", "demo.example.test");
        incoming.add("X-Test", "value");
        MultivaluedMap<String, Object> outgoing = new MultivaluedHashMap<>();

        when(headers.getRequestHeaders()).thenReturn(incoming);
        when(requestContext.getHeaders()).thenReturn(outgoing);

        new ProxyClientRequestFilter(headers).filter(requestContext);

        assertEquals("demo.example.test", outgoing.getFirst("Host"));
        assertEquals("value", outgoing.getFirst("X-Test"));
    }
}
