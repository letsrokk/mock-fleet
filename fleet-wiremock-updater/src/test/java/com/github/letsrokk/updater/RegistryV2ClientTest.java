package com.github.letsrokk.updater;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RegistryV2ClientTest {
    private final List<HttpServer> servers = new CopyOnWriteArrayList<>();

    @AfterEach void stopServers() {
        servers.forEach(server -> server.stop(0));
    }

    @Test void listsAnonymousTagsAcrossRelativeLinkPages() throws Exception {
        HttpServer server = server();
        server.createContext("/v2/wiremock/wiremock/tags/list", exchange -> {
            assertNull(exchange.getRequestHeaders().getFirst("Authorization"));
            if (exchange.getRequestURI().getQuery().contains("last=3.14.0")) {
                json(exchange, 200, "{\"tags\":[\"3.14.1\"]}");
            } else {
                exchange.getResponseHeaders().add("Link",
                        "</v2/wiremock/wiremock/tags/list?n=2&last=3.14.0>; rel=\"next\"");
                json(exchange, 200, "{\"tags\":[\"3.13.0\",\"3.14.0\"]}");
            }
        });
        server.start();

        assertEquals(List.of("3.13.0", "3.14.0", "3.14.1"), client(null).tags(base(server), "wiremock/wiremock", 2));
    }

    @Test void sendsInitialBasicAuthenticationWhenConfigured() throws Exception {
        HttpServer server = server();
        server.createContext("/v2/acme/image/tags/list", exchange -> {
            assertEquals("Basic dXNlcjpwYXNz", exchange.getRequestHeaders().getFirst("Authorization"));
            json(exchange, 200, "{\"tags\":[\"3.1.0\"]}");
        });
        server.start();

        assertEquals(List.of("3.1.0"), client(new RegistryV2Client.Credentials("user", "pass"))
                .tags(base(server), "acme/image", 10));
    }

    @Test void exchangesBearerChallengeWithServiceAndScope() throws Exception {
        HttpServer server = server();
        server.createContext("/token", exchange -> {
            assertEquals("service=registry.test&scope=repository%3Aacme%2Fimage%3Apull", exchange.getRequestURI().getRawQuery());
            assertNull(exchange.getRequestHeaders().getFirst("Authorization"));
            json(exchange, 200, "{\"token\":\"registry-token\"}");
        });
        server.createContext("/v2/acme/image/tags/list", exchange -> {
            if (!"Bearer registry-token".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                exchange.getResponseHeaders().add("WWW-Authenticate", "Bearer realm=\"" + base(server) + "/token\",service=\"registry.test\",scope=\"repository:acme/image:pull\"");
                json(exchange, 401, "{}");
            } else {
                json(exchange, 200, "{\"tags\":[\"3.2.0\"]}");
            }
        });
        server.start();

        assertEquals(List.of("3.2.0"), client(null).tags(base(server), "acme/image", 10));
    }

    @Test void acceptsAccessTokenAndSendsOptionalBasicCredentialsToTokenEndpoint() throws Exception {
        HttpServer server = server();
        server.createContext("/token", exchange -> {
            assertEquals("Basic dXNlcjpwYXNz", exchange.getRequestHeaders().getFirst("Authorization"));
            assertEquals("service=registry.test&scope=repository%3Aacme%2Fimage%3Apull", exchange.getRequestURI().getRawQuery());
            json(exchange, 200, "{\"access_token\":\"access-token\"}");
        });
        server.createContext("/v2/acme/image/tags/list", exchange -> {
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            if (!"Bearer access-token".equals(authorization)) {
                assertEquals("Basic dXNlcjpwYXNz", authorization);
                exchange.getResponseHeaders().add("WWW-Authenticate", "Bearer realm=\"" + base(server) + "/token\",service=\"registry.test\"");
                json(exchange, 401, "{}");
            } else {
                json(exchange, 200, "{\"tags\":[\"3.2.1\"]}");
            }
        });
        server.start();

        assertEquals(List.of("3.2.1"), client(new RegistryV2Client.Credentials("user", "pass"))
                .tags(base(server), "acme/image", 10));
    }

    @Test void treatsWellFormedUnrelatedLinkRelationsAsPaginationComplete() throws Exception {
        HttpServer server = server();
        server.createContext("/v2/acme/image/tags/list", exchange -> {
            exchange.getResponseHeaders().add("Link", "</next>; rel=\"previous\"");
            json(exchange, 200, "{\"tags\":[\"3.1.0\"]}");
        });
        server.start();

        assertEquals(List.of("3.1.0"), client(null).tags(base(server), "acme/image", 10));
    }

    @Test void rejectsMalformedInvalidAndAmbiguousPaginationLinks() throws Exception {
        assertRejectedLink("not-a-link", "Malformed");
        assertRejectedLink("<http://[invalid>; rel=next", "invalid next target");
        assertRejectedLink("</next-a>; rel=next, </next-b>; rel=next", "multiple next");
    }

    @Test void rejectsOffOriginPaginationBeforeLeakingAuthorization() throws Exception {
        HttpServer registry = server();
        HttpServer attacker = server();
        AtomicInteger attackerRequests = new AtomicInteger();
        registry.createContext("/token", exchange -> json(exchange, 200, "{\"token\":\"do-not-leak\"}"));
        attacker.createContext("/steal", exchange -> {
            attackerRequests.incrementAndGet();
            json(exchange, 200, "{\"tags\":[]}");
        });
        registry.createContext("/v2/acme/image/tags/list", exchange -> {
            if (!"Bearer do-not-leak".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                exchange.getResponseHeaders().add("WWW-Authenticate",
                        "Bearer realm=\"" + base(registry) + "/token\",service=\"registry.test\"");
                json(exchange, 401, "{}");
            } else {
                exchange.getResponseHeaders().add("Link", "<" + base(attacker) + "/steal>; rel=\"next\"");
                json(exchange, 200, "{\"tags\":[\"3.1.0\"]}");
            }
        });
        attacker.start();
        registry.start();

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> client(new RegistryV2Client.Credentials("user", "pass"))
                        .tags(base(registry), "acme/image", 10));
        assertTrue(error.getMessage().contains("origin"));
        assertEquals(0, attackerRequests.get());
    }

    @Test void boundsTagBodyBytesBeforeJsonParsing() throws Exception {
        HttpServer server = server();
        server.createContext("/v2/acme/image/tags/list", exchange -> json(exchange, 200, "{\"tags\":[\"3.123456789.0\"]}"));
        server.start();

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> client(null, new RegistryV2Client.Limits(2, 10, 16, 64)).tags(base(server), "acme/image", 10));
        assertTrue(error.getMessage().contains("body byte limit"));
    }

    @Test void boundsBearerTokenBodyBytesBeforeJsonParsing() throws Exception {
        HttpServer server = server();
        server.createContext("/token", exchange -> json(exchange, 200, "{\"token\":\"this-is-too-large\"}"));
        server.createContext("/v2/acme/image/tags/list", exchange -> {
            exchange.getResponseHeaders().add("WWW-Authenticate", "Bearer realm=\"" + base(server) + "/token\",service=\"registry.test\"");
            json(exchange, 401, "{}");
        });
        server.start();

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> client(null, new RegistryV2Client.Limits(2, 10, 64, 16)).tags(base(server), "acme/image", 10));
        assertTrue(error.getMessage().contains("token body byte limit"));
    }

    @Test void boundsPageCountAndTagCount() throws Exception {
        HttpServer pageServer = server();
        pageServer.createContext("/v2/acme/image/tags/list", exchange -> {
            exchange.getResponseHeaders().add("Link", "</v2/acme/image/tags/list?n=1&last=3.1.0>; rel=next");
            json(exchange, 200, "{\"tags\":[\"3.1.0\"]}");
        });
        pageServer.start();
        assertTrue(assertThrows(IllegalStateException.class,
                () -> client(null, new RegistryV2Client.Limits(1, 10, 64, 64))
                        .tags(base(pageServer), "acme/image", 1)).getMessage().contains("page limit"));

        HttpServer tagServer = server();
        tagServer.createContext("/v2/acme/image/tags/list", exchange -> json(exchange, 200, "{\"tags\":[\"3.1.0\",\"3.1.0\"]}"));
        tagServer.start();
        assertTrue(assertThrows(IllegalStateException.class,
                () -> client(null, new RegistryV2Client.Limits(2, 1, 64, 64))
                        .tags(base(tagServer), "acme/image", 10)).getMessage().contains("tag limit"));
    }

    @Test void validatesRepositoryBeforeMakingARequestAndReportsParseAndAuthFailures() throws Exception {
        HttpServer server = server();
        AtomicInteger requests = new AtomicInteger();
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            json(exchange, 200, "not-json");
        });
        server.start();

        assertThrows(IllegalArgumentException.class, () -> client(null).tags(base(server), "../token", 10));
        assertEquals(0, requests.get());
        assertTrue(assertThrows(IllegalStateException.class,
                () -> client(null).tags(base(server), "acme/image", 10)).getMessage().contains("Invalid registry tag response"));

        HttpServer authServer = server();
        authServer.createContext("/", exchange -> json(exchange, 401, "{}"));
        authServer.start();
        assertTrue(assertThrows(IllegalStateException.class,
                () -> client(null).tags(base(authServer), "acme/image", 10)).getMessage().contains("Bearer challenge"));

        HttpServer malformedTokenServer = server();
        malformedTokenServer.createContext("/token", exchange -> json(exchange, 200, "not-json"));
        malformedTokenServer.createContext("/v2/acme/image/tags/list", exchange -> {
            exchange.getResponseHeaders().add("WWW-Authenticate",
                    "Bearer realm=\"" + base(malformedTokenServer) + "/token\",service=\"registry.test\"");
            json(exchange, 401, "{}");
        });
        malformedTokenServer.start();
        assertTrue(assertThrows(IllegalStateException.class,
                () -> client(null).tags(base(malformedTokenServer), "acme/image", 10))
                .getMessage().contains("Invalid registry token response"));
    }

    private RegistryV2Client client(RegistryV2Client.Credentials credentials) {
        return client(credentials, RegistryV2Client.Limits.defaults());
    }

    private void assertRejectedLink(String link, String message) throws Exception {
        HttpServer server = server();
        server.createContext("/v2/acme/image/tags/list", exchange -> {
            exchange.getResponseHeaders().add("Link", link);
            json(exchange, 200, "{\"tags\":[\"3.1.0\"]}");
        });
        server.start();

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> client(null).tags(base(server), "acme/image", 10));
        assertTrue(error.getMessage().contains(message), error.getMessage());
    }

    private RegistryV2Client client(RegistryV2Client.Credentials credentials, RegistryV2Client.Limits limits) {
        return new RegistryV2Client(HttpClient.newHttpClient(), new ObjectMapper(), credentials, limits);
    }

    private HttpServer server() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servers.add(server);
        return server;
    }

    private static URI base(HttpServer server) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private static void json(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
