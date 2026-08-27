package com.github.letsrokk.mcp;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TargetUrlPolicyTest {

    private final TargetUrlPolicy policy = new TargetUrlPolicy(Set.of(), host -> switch (host) {
        case "public.example" -> addresses("93.184.216.34");
        case "loopback.example" -> addresses("127.0.0.1");
        case "metadata.example" -> addresses("169.254.169.254");
        default -> throw new UnknownHostException(host);
    });

    @ParameterizedTest
    @ValueSource(strings = {
            "https://public.example/api",
            "http://93.184.216.34/path",
            "https://[2606:4700:4700::1111]/path"
    })
    void permitsPublicHttpTargets(String value) {
        assertDoesNotThrow(() -> policy.requireAllowed(URI.create(value)));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "file:///etc/passwd", "ftp://public.example/file", "http://127.0.0.1",
            "http://10.0.0.1", "http://169.254.169.254/latest", "http://224.0.0.1",
            "http://loopback.example", "http://metadata.example",
            "http://192.31.196.1", "http://192.52.193.1", "http://192.88.99.1", "http://192.175.48.1",
            "http://[::ffff:93.184.216.34]", "http://[64:ff9b::1]", "http://[100::1]",
            "http://[2001:1::1]", "http://[2001:20::1]", "http://[2001:db8::1]",
            "http://[2002::1]", "http://[2620:4f:8000::1]", "http://[3fff::1]", "http://[5f00::1]"
    })
    void blocksNonHttpAndNonPublicTargets(String value) {
        assertThrows(IllegalArgumentException.class, () -> policy.requireAllowed(URI.create(value)));
    }

    @ParameterizedTest
    @ValueSource(strings = { "http://127.0.0.1:9000", "http://metadata.example" })
    void explicitHostExceptionsOverrideAddressBlocking(String value) {
        var exceptionPolicy = new TargetUrlPolicy(Set.of("127.0.0.1", "metadata.example"), policy.resolver());
        assertDoesNotThrow(() -> exceptionPolicy.requireAllowed(URI.create(value)));
    }

    private static InetAddress[] addresses(String... values) throws UnknownHostException {
        var addresses = new InetAddress[values.length];
        for (int i = 0; i < values.length; i++) {
            addresses[i] = InetAddress.getByName(values[i]);
        }
        return addresses;
    }
}
