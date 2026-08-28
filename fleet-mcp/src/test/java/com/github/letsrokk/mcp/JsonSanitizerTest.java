package com.github.letsrokk.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import org.junit.jupiter.api.Test;

class JsonSanitizerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final JsonSanitizer sanitizer = new JsonSanitizer(mapper, Set.of("authorization", "cookie", "x-api-key"));

    @Test
    void redactsSensitiveHeadersWithoutChangingBodies() throws Exception {
        var input = mapper.readTree("""
                {"request":{"headers":{"Authorization":"Bearer secret","Accept":"application/json"},
                 "body":"Authorization=not-a-header"}}
                """);

        var output = sanitizer.redactHeaders(input);

        assertEquals("[REDACTED]", output.at("/request/headers/Authorization").textValue());
        assertEquals("application/json", output.at("/request/headers/Accept").textValue());
        assertEquals("Authorization=not-a-header", output.at("/request/body").textValue());
    }

    @Test
    void redactsParsedCookiesWhenCookieHeaderIsSensitive() throws Exception {
        var input = mapper.readTree("""
                {"request":{"headers":{"Cookie":"session=secret-cookie"},
                 "cookies":{"session":{"value":"secret-cookie"},"theme":"secret-theme"}}}
                """);

        var output = sanitizer.redactHeaders(input);

        assertFalse(output.toString().contains("secret-cookie"), output.toPrettyString());
        assertFalse(output.toString().contains("secret-theme"), output.toPrettyString());
        assertEquals("[REDACTED]", output.at("/request/cookies/session/value").textValue());
        assertEquals("[REDACTED]", output.at("/request/cookies/theme").textValue());
    }

    @Test
    void removesSensitiveHeadersFromRecorderCandidates() throws Exception {
        var input = mapper.readTree("""
                {"mappings":[{"request":{"headers":{"Cookie":{"equalTo":"secret"},"Accept":{"equalTo":"text/plain"}}},
                 "response":{"headers":{"X-API-Key":"secret","Content-Type":"text/plain"}}}]}
                """);

        var output = sanitizer.removeSensitiveHeaders(input);

        assertFalse(output.at("/mappings/0/request/headers").has("Cookie"));
        assertFalse(output.at("/mappings/0/response/headers").has("X-API-Key"));
        assertEquals("text/plain", output.at("/mappings/0/response/headers/Content-Type").textValue());
    }

    @Test
    void removesCookieMatchersAndParsedCookiesFromRecorderCandidates() throws Exception {
        var input = mapper.readTree("""
                {"mappings":[{"request":{"headers":{"Cookie":{"equalTo":"secret-header"}},
                 "cookies":{"session":{"equalTo":"secret-cookie"}},"method":"GET"},
                 "response":{"status":200}}]}
                """);

        var output = sanitizer.removeSensitiveHeaders(input);

        assertFalse(output.toString().contains("secret-header"), output.toPrettyString());
        assertFalse(output.toString().contains("secret-cookie"), output.toPrettyString());
        assertFalse(output.at("/mappings/0/request").has("cookies"));
    }
}
