package com.github.letsrokk.mcp;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OutboundTargetValidatorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final TargetUrlPolicy policy = new TargetUrlPolicy(Set.of(), host -> switch (host) {
        case "public.example" -> addresses("93.184.216.34");
        case "private.example" -> addresses("10.0.0.1");
        default -> throw new UnknownHostException(host);
    });

    @Test
    void validatesRecorderAndProxyTargets() throws Exception {
        var validator = new OutboundTargetValidator(policy, Set.of());

        assertDoesNotThrow(() -> validator.validate(json("""
                {"targetBaseUrl":"https://public.example/api",
                 "response":{"proxyBaseUrl":"https://public.example/upstream"}}
                """)));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(json("""
                {"targetBaseUrl":"http://private.example/api"}
                """)));
    }

    @Test
    void rejectsUnapprovedServeEventListeners() throws Exception {
        var validator = new OutboundTargetValidator(policy, Set.of());

        assertThrows(IllegalArgumentException.class, () -> validator.validate(json("""
                {"serveEventListeners":[{"name":"webhook","parameters":{"url":"https://public.example/hook"}}]}
                """)));
    }

    @Test
    void validatesTargetsForApprovedWebhookListener() throws Exception {
        var validator = new OutboundTargetValidator(policy, Set.of("webhook"));

        assertDoesNotThrow(() -> validator.validate(json("""
                {"serveEventListeners":[{"name":"webhook","parameters":{"url":"https://public.example/hook"}}]}
                """)));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(json("""
                {"serveEventListeners":[{"name":"webhook","parameters":{"url":"http://private.example/hook"}}]}
                """)));
    }

    private JsonNode json(String value) throws Exception {
        return mapper.readTree(value);
    }

    private static InetAddress[] addresses(String... values) throws UnknownHostException {
        InetAddress[] addresses = new InetAddress[values.length];
        for (int index = 0; index < values.length; index++) {
            addresses[index] = InetAddress.getByName(values[index]);
        }
        return addresses;
    }
}
