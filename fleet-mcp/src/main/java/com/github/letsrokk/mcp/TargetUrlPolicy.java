package com.github.letsrokk.mcp;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public final class TargetUrlPolicy {

    private final Set<String> allowedHosts;
    private final HostResolver resolver;
    private final McpMetrics metrics;

    public TargetUrlPolicy(Set<String> allowedHosts, HostResolver resolver) {
        this(allowedHosts, resolver, null);
    }

    public TargetUrlPolicy(Set<String> allowedHosts, HostResolver resolver, McpMetrics metrics) {
        this.allowedHosts = allowedHosts.stream()
                .map(host -> host.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.metrics = metrics;
    }

    public TargetUrlPolicy(Set<String> allowedHosts) {
        this(allowedHosts, InetAddress::getAllByName);
    }

    public URI requireAllowed(URI uri) {
        try {
            return checkAllowed(uri);
        } catch (IllegalArgumentException e) {
            if (metrics != null) {
                metrics.targetBlocked();
            }
            throw e;
        }
    }

    private URI checkAllowed(URI uri) {
        Objects.requireNonNull(uri, "uri");
        if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("Target URL must use HTTP or HTTPS");
        }
        if (uri.getHost() == null || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("Target URL must have a host and no user information");
        }

        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (allowedHosts.contains(host)) {
            return uri;
        }

        InetAddress[] addresses;
        try {
            addresses = isAddressLiteral(host)
                    ? new InetAddress[] { InetAddress.getByName(host) }
                    : resolver.resolve(host);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Target host cannot be resolved: " + host, e);
        }
        if (addresses.length == 0) {
            throw new IllegalArgumentException("Target host has no addresses: " + host);
        }
        boolean ipv6Literal = host.indexOf(':') >= 0;
        for (InetAddress address : addresses) {
            if (!isPublic(address, ipv6Literal)) {
                throw new IllegalArgumentException("Target resolves to a blocked address: " + address.getHostAddress());
            }
        }
        return uri;
    }

    private static boolean isAddressLiteral(String host) {
        return host.indexOf(':') >= 0 || host.chars().allMatch(character -> Character.isDigit(character) || character == '.');
    }

    HostResolver resolver() {
        return resolver;
    }

    private static boolean isPublic(InetAddress address, boolean ipv6Literal) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            return !ipv6Literal
                    && !inPrefix(bytes, 8, 0)
                    && !inPrefix(bytes, 8, 10)
                    && !inPrefix(bytes, 10, 100, 64)
                    && !inPrefix(bytes, 8, 127)
                    && !inPrefix(bytes, 16, 169, 254)
                    && !inPrefix(bytes, 12, 172, 16)
                    && !inPrefix(bytes, 24, 192, 0, 0)
                    && !inPrefix(bytes, 24, 192, 0, 2)
                    && !inPrefix(bytes, 24, 192, 31, 196)
                    && !inPrefix(bytes, 24, 192, 52, 193)
                    && !inPrefix(bytes, 24, 192, 88, 99)
                    && !inPrefix(bytes, 16, 192, 168)
                    && !inPrefix(bytes, 24, 192, 175, 48)
                    && !inPrefix(bytes, 15, 198, 18)
                    && !inPrefix(bytes, 24, 198, 51, 100)
                    && !inPrefix(bytes, 24, 203, 0, 113)
                    && !inPrefix(bytes, 4, 224)
                    && !inPrefix(bytes, 4, 240);
        }
        if (address instanceof Inet6Address) {
            return inPrefix(bytes, 3, 0x20)
                    && !inPrefix(bytes, 23, 0x20, 0x01, 0x00)
                    && !inPrefix(bytes, 32, 0x20, 0x01, 0x0d, 0xb8)
                    && !inPrefix(bytes, 16, 0x20, 0x02)
                    && !inPrefix(bytes, 48, 0x26, 0x20, 0x00, 0x4f, 0x80, 0x00)
                    && !inPrefix(bytes, 20, 0x3f, 0xff, 0x00);
        }
        return false;
    }

    private static boolean inPrefix(byte[] address, int prefixBits, int... prefix) {
        int completeBytes = prefixBits / 8;
        for (int index = 0; index < completeBytes; index++) {
            if (Byte.toUnsignedInt(address[index]) != prefix[index]) {
                return false;
            }
        }
        int remainingBits = prefixBits % 8;
        if (remainingBits == 0) {
            return true;
        }
        int mask = 0xff << (8 - remainingBits);
        return (Byte.toUnsignedInt(address[completeBytes]) & mask) == (prefix[completeBytes] & mask);
    }

    @FunctionalInterface
    public interface HostResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }
}
