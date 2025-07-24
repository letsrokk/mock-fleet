package com.github.letsrokk;

import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.RestClientBuilder;

import java.net.URI;

@RequestScoped
public class ProxyClientFactory {

    @Inject
    ProxyClientRequestFilter proxyClientRequestFilter;

    public ProxyClient createClient(String baseUrl) {
        return RestClientBuilder.newBuilder()
                .baseUri(URI.create(baseUrl))
                .register(proxyClientRequestFilter)
                .build(ProxyClient.class);
    }
}
