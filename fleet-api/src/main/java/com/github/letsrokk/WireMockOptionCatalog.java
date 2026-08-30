package com.github.letsrokk;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class WireMockOptionCatalog {

    private static final Set<String> SENSITIVE_OPTIONS = Set.of(
            "--admin-api-basic-auth",
            "--ca-keystore-password",
            "--keystore-password",
            "--key-manager-password",
            "--truststore-password");

    private static final List<OptionDefinition> DEFINITIONS = List.of(
            input("--admin-api-basic-auth", "Admin API basic authentication", "Security", "Requires HTTP Basic authentication for WireMock Admin API calls."),
            flag("--admin-api-require-https", "Require HTTPS for Admin API", "Security", "Requires HTTPS for WireMock Admin API calls."),
            flag("--help", "Print help", "Process", "Prints WireMock command-line help and exits."),
            flag("--version", "Print version", "Process", "Prints the WireMock version and exits."),
            number("--port", "HTTP port", "Network Listeners", "Sets the HTTP listener port.", 0, 65535),
            flag("--disable-http", "Disable HTTP", "Network Listeners", "Disables the plain HTTP listener."),
            number("--https-port", "HTTPS port", "Network Listeners", "Enables HTTPS on the supplied port.", 1, 65535),
            input("--bind-address", "Bind address", "Network Listeners", "Sets the listener bind address."),
            input("--root-dir", "Root directory", "Storage", "Sets the root directory containing mappings and body files."),
            input("--load-resources-from-classpath", "Classpath resources", "Storage", "Loads mappings and body files from a classpath location."),
            flag("--verbose", "Verbose logging", "Logging and Diagnostics", "Log more detail to stdout."),
            flag("--print-all-network-traffic", "Print network traffic", "Logging and Diagnostics", "Print raw inbound and outbound network traffic."),
            flag("--disable-request-logging", "Disable request logging", "Logging and Diagnostics", "Stops requests and responses being sent to the notifier."),
            number("--logged-response-body-size-limit", "Response body log limit", "Logging and Diagnostics", "Truncates logged response bodies above this byte limit.", 0, 16777216),
            flag("--disable-banner", "Disable banner", "Logging and Diagnostics", "Prevents the WireMock logo being printed on startup."),
            flag("--no-request-journal", "Disable request journal", "Request Journal and Recording", "Turns off the in-memory journal of received requests."),
            number("--max-request-journal-entries", "Max journal entries", "Request Journal and Recording", "Sets the maximum number of request journal entries.", 0, 100000),
            flag("--record-mappings", "Record mappings", "Request Journal and Recording", "Records incoming requests as stub mappings."),
            input("--match-headers", "Match headers", "Request Journal and Recording", "Captures the named request headers when recording."),
            input("--filename-template", "Filename template", "Request Journal and Recording", "Sets the Handlebars filename template for recorded mappings."),
            input("--proxy-all", "Proxy all", "Proxying", "Proxies all requests to the supplied base URL."),
            input("--proxy-via", "Proxy via", "Proxying", "Routes proxied traffic through another proxy server."),
            flag("--preserve-host-header", "Preserve host header", "Proxying", "Keeps the original Host header when proxying."),
            flag("--preserve-user-agent-proxy-header", "Preserve user agent", "Proxying", "Keeps the original User-Agent header when proxying."),
            input("--supported-proxy-encodings", "Proxy encodings", "Proxying", "Sets acceptable compression methods for proxy and recording traffic."),
            input("--allow-proxy-targets", "Allow proxy targets", "Proxying", "Limits proxying and recording to the supplied targets."),
            input("--deny-proxy-targets", "Deny proxy targets", "Proxying", "Blocks proxying and recording to the supplied targets."),
            number("--proxy-timeout", "Proxy timeout ms", "Proxying", "Sets the proxy request timeout in milliseconds.", 1, 3600000),
            select("--proxy-pass-through", "Proxy pass through", "Proxying", "Controls whether unmatched browser proxy requests pass through.", List.of("true", "false")),
            flag("--enable-browser-proxying", "Browser proxying", "Browser Proxy and Certificates", "Runs WireMock as a browser proxy."),
            input("--ca-keystore", "CA keystore", "Browser Proxy and Certificates", "Sets the CA keystore used for generated proxy certificates."),
            input("--ca-keystore-password", "CA keystore password", "Browser Proxy and Certificates", "Sets the CA keystore password."),
            input("--ca-keystore-type", "CA keystore type", "Browser Proxy and Certificates", "Sets the CA keystore type."),
            flag("--trust-all-proxy-targets", "Trust all proxy targets", "Browser Proxy and Certificates", "Trusts all remote certificates when proxying HTTPS traffic."),
            input("--trust-proxy-target", "Trust proxy target", "Browser Proxy and Certificates", "Trusts a specific remote endpoint certificate."),
            input("--https-keystore", "HTTPS keystore", "Browser Proxy and Certificates", "Sets the HTTPS keystore path."),
            input("--keystore-type", "Keystore type", "Browser Proxy and Certificates", "Sets the HTTPS keystore type."),
            input("--keystore-password", "Keystore password", "Browser Proxy and Certificates", "Sets the HTTPS keystore password."),
            input("--key-manager-password", "Key manager password", "Browser Proxy and Certificates", "Sets the key manager password."),
            input("--https-truststore", "HTTPS truststore", "Browser Proxy and Certificates", "Sets the HTTPS truststore path."),
            input("--truststore-type", "Truststore type", "Browser Proxy and Certificates", "Sets the HTTPS truststore type."),
            input("--truststore-password", "Truststore password", "Browser Proxy and Certificates", "Sets the HTTPS truststore password."),
            flag("--https-require-client-cert", "Require client cert", "Browser Proxy and Certificates", "Requires clients to authenticate with a certificate."),
            flag("--disable-http2-plain", "Disable HTTP/2 plain", "HTTP Responses", "Disables HTTP/2 over plain HTTP."),
            flag("--disable-http2-tls", "Disable HTTP/2 TLS", "HTTP Responses", "Disables HTTP/2 over HTTPS."),
            flag("--disable-gzip", "Disable gzip", "HTTP Responses", "Prevents response bodies from being gzipped."),
            flag("--enable-stub-cors", "Enable stub CORS", "HTTP Responses", "Adds automatic CORS response headers for stubs."),
            select("--use-chunked-encoding", "Chunked encoding", "HTTP Responses", "Controls when responses use Transfer-Encoding: chunked.", List.of("always", "never", "body_file")),
            select("--disable-connection-reuse", "Disable connection reuse", "HTTP Responses", "Controls whether HTTP connection reuse is disabled.", List.of("true", "false")),
            flag("--disable-strict-http-headers", "Disable strict headers", "HTTP Responses", "Disables strict HTTP header handling."),
            flag("--global-response-templating", "Global response templating", "Templating", "Renders all response definitions with Handlebars templates."),
            flag("--local-response-templating", "Local response templating", "Templating", "Allows templating only on stub mappings that opt in."),
            flag("--disable-response-templating", "Disable response templating", "Templating", "Disables processing responses with Handlebars templates."),
            optionalNumber("--max-template-cache-entries", "Max template cache entries", "Templating", "Limits compiled template fragments kept in cache.", 0, 100000),
            optionalInput("--permitted-system-keys", "Permitted system keys", "Templating", "Sets permitted system property and environment variable names for templates."),
            input("--extensions", "Extensions", "Extensions", "Sets extension class names."),
            flag("--disable-extensions-scanning", "Disable extension scanning", "Extensions", "Prevents extensions being scanned and loaded from the classpath."),
            flag("--disable-optimize-xml-factories-loading", "Disable XML factory optimization", "Extensions", "Disables optimized XML factory loading."),
            select("--async-response-enabled", "Async responses", "Performance and Jetty", "Controls asynchronous request processing for delayed responses.", List.of("true", "false")),
            number("--async-response-threads", "Async response threads", "Performance and Jetty", "Sets the number of background response threads.", 1, 256),
            number("--container-threads", "Container threads", "Performance and Jetty", "Sets the number of Jetty container threads.", 1, 512),
            number("--max-http-client-connections", "Max HTTP client connections", "Performance and Jetty", "Sets the maximum HTTP client connections.", 1, 10000),
            number("--jetty-acceptor-threads", "Jetty acceptor threads", "Performance and Jetty", "Sets the number of Jetty acceptor threads.", 1, 256),
            number("--jetty-accept-queue-size", "Jetty accept queue size", "Performance and Jetty", "Sets the Jetty accepted request queue size.", 1, 10000),
            number("--jetty-header-buffer-size", "Jetty header buffer size", "Performance and Jetty", "Sets the deprecated Jetty request header buffer size.", 1, 1048576),
            number("--jetty-header-request-size", "Jetty request header size", "Performance and Jetty", "Sets the Jetty request header buffer size.", 1, 1048576),
            number("--jetty-header-response-size", "Jetty response header size", "Performance and Jetty", "Sets the Jetty response header buffer size.", 1, 1048576),
            number("--jetty-idle-timeout", "Jetty idle timeout ms", "Performance and Jetty", "Sets the Jetty connection idle timeout in milliseconds.", 1, 3600000),
            number("--jetty-stop-timeout", "Jetty stop timeout ms", "Performance and Jetty", "Sets the Jetty stop timeout in milliseconds.", 1, 3600000),
            input("--timeout", "Timeout ms", "Performance and Jetty", "Sets the default global timeout in milliseconds."),
            number("--webhook-threadpool-size", "Webhook thread pool size", "Webhooks and WebSockets", "Sets the webhook processing thread count.", 1, 256));

    private WireMockOptionCatalog() {
    }

    static List<OptionDefinition> definitions() {
        return definitions(new WireMockVersion(3, 13, 2));
    }

    static List<OptionDefinition> definitions(WireMockVersion version) {
        return WireMockOptionMatrix.loadDefault().resolve(version).options();
    }

    static List<OptionDefinition> baseDefinitions() {
        return DEFINITIONS;
    }

    static boolean isSensitive(String name) {
        return SENSITIVE_OPTIONS.contains(name);
    }

    static void rejectSensitive(List<String> values) {
        for (String token : tokenize(values == null ? List.of() : values)) {
            String name = optionName(token);
            if (name != null && SENSITIVE_OPTIONS.contains(name)) {
                throw invalid("WireMock password option is not supported: " + name, name);
            }
        }
    }

    static List<String> redactSensitive(List<String> values) {
        List<String> tokens = tokenize(values == null ? List.of() : values);
        List<String> redacted = new ArrayList<>();
        for (int index = 0; index < tokens.size(); index++) {
            String token = tokens.get(index);
            String name = optionName(token);
            if (name == null || !SENSITIVE_OPTIONS.contains(name)) {
                redacted.add(token);
                continue;
            }
            redacted.add(name);
            redacted.add("<redacted>");
            if (!token.contains("=") && index + 1 < tokens.size()) {
                index++;
            }
        }
        return List.copyOf(redacted);
    }

    static List<String> validateAndNormalize(List<String> values) {
        return validateAndNormalize(values, new WireMockVersion(3, 13, 2));
    }

    static List<String> validateAndNormalize(List<String> values, WireMockVersion version) {
        if (values == null) {
            return List.of();
        }
        Map<String, OptionDefinition> byName = indexDefinitions(definitions(version));
        List<String> tokens = tokenize(values);
        List<String> normalized = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (int index = 0; index < tokens.size();) {
            String raw = tokens.get(index++);
            if (!raw.startsWith("--")) {
                throw invalid("Unexpected WireMock option value: " + raw, raw);
            }
            String name = raw;
            String inlineValue = null;
            int equals = raw.indexOf('=');
            if (equals >= 0) {
                name = raw.substring(0, equals);
                inlineValue = raw.substring(equals + 1);
            }
            OptionDefinition definition = byName.get(name);
            if (definition == null) {
                throw invalid("Unknown WireMock option: " + name, name);
            }
            if (!definition.available()) {
                throw invalid("WireMock option requires secure Secret storage and is unavailable: " + name, name);
            }
            if (!seen.add(name)) {
                throw invalid("Duplicate WireMock option: " + name, name);
            }
            normalized.add(name);
            if ("flag".equals(definition.kind())) {
                if (inlineValue != null) {
                    throw invalid("WireMock flag does not accept a value: " + name, name);
                }
                continue;
            }
            String optionValue = inlineValue;
            if (optionValue == null && index < tokens.size() && !tokens.get(index).startsWith("--")) {
                optionValue = tokens.get(index++);
            }
            boolean optional = definition.kind().startsWith("optional_");
            if (optionValue == null && optional) {
                continue;
            }
            if (optionValue == null && "select".equals(definition.kind())
                    && definition.values().equals(List.of("true", "false"))) {
                optionValue = "true";
            }
            if (optionValue == null || optionValue.isBlank()) {
                throw invalid("WireMock option requires a value: " + name, name);
            }
            String valueKind = optional ? definition.kind().substring("optional_".length()) : definition.kind();
            if ("number".equals(valueKind)) {
                BigInteger numericValue;
                try {
                    numericValue = new BigInteger(optionValue);
                } catch (NumberFormatException error) {
                    throw invalid(integerRangeMessage(definition), name);
                }
                if (numericValue.compareTo(BigInteger.valueOf(definition.minimum())) < 0
                        || numericValue.compareTo(BigInteger.valueOf(definition.maximum())) > 0) {
                    throw invalid(integerRangeMessage(definition), name);
                }
            }
            if ("select".equals(valueKind) && !definition.values().contains(optionValue)) {
                throw invalid("Unsupported value for " + name + ": " + optionValue, name);
            }
            normalized.add(optionValue);
        }
        return List.copyOf(normalized);
    }

    static List<String> tokenize(List<String> values) {
        List<String> result = new ArrayList<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                throw ApiException.badRequest("INVALID_OPTIONS", "WireMock options must be non-blank strings.", Map.of());
            }
            String trimmed = value.trim();
            if (trimmed.startsWith("--")) {
                result.addAll(tokenize(trimmed));
            } else {
                result.add(trimmed);
            }
        }
        return List.copyOf(result);
    }

    private static List<String> tokenize(String value) {
        List<String> tokens = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        char quote = 0;
        boolean escaped = false;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (escaped) {
                token.append(current);
                escaped = false;
            } else if (current == '\\') {
                escaped = true;
            } else if (quote != 0) {
                if (current == quote) {
                    quote = 0;
                } else {
                    token.append(current);
                }
            } else if (current == '\'' || current == '"') {
                quote = current;
            } else if (Character.isWhitespace(current)) {
                addToken(tokens, token);
            } else {
                token.append(current);
            }
        }
        if (escaped) {
            token.append('\\');
        }
        if (quote != 0) {
            throw ApiException.badRequest("INVALID_OPTIONS", "Unclosed quote in WireMock options.", Map.of());
        }
        addToken(tokens, token);
        return tokens;
    }

    private static void addToken(List<String> tokens, StringBuilder token) {
        if (!token.isEmpty()) {
            tokens.add(token.toString());
            token.setLength(0);
        }
    }

    private static ApiException invalid(String message, String option) {
        return ApiException.badRequest("INVALID_OPTIONS", message, Map.of("option", option));
    }

    private static String optionName(String token) {
        if (token == null || !token.startsWith("--")) {
            return null;
        }
        int equals = token.indexOf('=');
        return equals < 0 ? token : token.substring(0, equals);
    }

    private static String integerRangeMessage(OptionDefinition definition) {
        return "WireMock option requires an integer from " + definition.minimum() + " to "
                + definition.maximum() + ": " + definition.name();
    }

    private static Map<String, OptionDefinition> indexDefinitions(List<OptionDefinition> definitions) {
        Map<String, OptionDefinition> indexed = new LinkedHashMap<>();
        definitions.forEach(definition -> indexed.put(definition.name(), definition));
        return Map.copyOf(indexed);
    }

    private static OptionDefinition flag(String name, String label, String group, String description) {
        return new OptionDefinition(name, label, "flag", group, description, List.of(), null, null);
    }

    private static OptionDefinition input(String name, String label, String group, String description) {
        return new OptionDefinition(name, label, "input", group, description, List.of(), null, null);
    }

    private static OptionDefinition optionalInput(String name, String label, String group, String description) {
        return new OptionDefinition(name, label, "optional_input", group, description, List.of(), null, null);
    }

    private static OptionDefinition number(String name, String label, String group, String description,
                                           int minimum, int maximum) {
        return new OptionDefinition(name, label, "number", group, description, List.of(), minimum, maximum);
    }

    private static OptionDefinition optionalNumber(String name, String label, String group, String description,
                                                   int minimum, int maximum) {
        return new OptionDefinition(name, label, "optional_number", group, description,
                List.of(), minimum, maximum);
    }

    private static OptionDefinition select(String name, String label, String group, String description,
                                           List<String> values) {
        return new OptionDefinition(name, label, "select", group, description, List.copyOf(values), null, null);
    }

    public record OptionDefinition(String name, String label, String kind, String group, String description,
                                   List<String> values, Integer minimum, Integer maximum,
                                   boolean available, String unavailableReason, String compatibility,
                                   String compatibilityMessage, List<VersionRange> versionRanges) {
        public OptionDefinition(String name, String label, String kind, String group, String description,
                                List<String> values, Integer minimum, Integer maximum) {
            this(name, label, kind, group, description, values, minimum, maximum,
                    true, null, "supported", null, List.of());
        }

        public OptionDefinition(String name, String label, String kind, String group, String description,
                                List<String> values) {
            this(name, label, kind, group, description, values, null, null,
                    true, null, "supported", null, List.of());
        }
    }

    public record VersionRange(String minimumVersion, String maximumVersion) {
    }
}
