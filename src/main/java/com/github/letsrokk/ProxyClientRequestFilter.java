package com.github.letsrokk;

import jakarta.enterprise.context.RequestScoped;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedMap;

import java.io.IOException;

@RequestScoped
public class ProxyClientRequestFilter implements ClientRequestFilter {

    private final HttpHeaders headers;

    public ProxyClientRequestFilter(@Context HttpHeaders headers) {
        this.headers = headers;
    }

    @SuppressWarnings("unchecked")
    @Override
    public void filter(ClientRequestContext requestContext) throws IOException {
        if (headers == null) {
            return;
        }
        MultivaluedMap<String, Object> incomingHeaders =
                (MultivaluedMap<String, Object>) (MultivaluedMap<?, ?>) headers.getRequestHeaders();
        requestContext.getHeaders().putAll(incomingHeaders);
    }
}
