package com.github.letsrokk.mockops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class RegistryV2Client {
    private static final Pattern REPOSITORY_COMPONENT =
            Pattern.compile("[a-z0-9]+(?:(?:[._]|__|[-]+)[a-z0-9]+)*");
    private static final Pattern AUTH_PARAMETER =
            Pattern.compile("([A-Za-z][A-Za-z0-9_-]*)\\s*=\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

    private final HttpClient client;
    private final ObjectMapper json;
    private final Optional<Credentials> credentials;
    private final Limits limits;

    RegistryV2Client(HttpClient client, ObjectMapper json, Credentials credentials) {
        this(client, json, credentials, Limits.defaults());
    }

    RegistryV2Client(HttpClient client, ObjectMapper json, Credentials credentials, Limits limits) {
        this.client = Objects.requireNonNull(client, "client");
        this.json = Objects.requireNonNull(json, "json");
        this.credentials = Optional.ofNullable(credentials);
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    List<String> tags(URI registry, String repository, int pageSize) {
        URI origin = validateRegistry(registry);
        validateRepository(repository);
        if (pageSize < 1 || pageSize > 1000) {
            throw new IllegalArgumentException("page size must be from 1 to 1000.");
        }

        URI next = origin.resolve("/v2/" + repository + "/tags/list?n=" + pageSize);
        Set<URI> visited = new HashSet<>();
        Set<String> tags = new LinkedHashSet<>();
        String bearer = null;
        int pages = 0;
        int tagCount = 0;
        while (next != null) {
            if (pages++ >= limits.maxPages()) {
                throw new IllegalStateException("Registry tag page limit exceeded.");
            }
            requireSameOrigin(origin, next);
            if (!visited.add(next)) {
                throw new IllegalStateException("Registry pagination loop detected.");
            }

            HttpResponse<InputStream> response = sendRegistry(next, bearer);
            if (response.statusCode() == 401 && bearer == null) {
                close(response.body());
                bearer = token(response, repository, origin);
                response = sendRegistry(next, bearer);
            }
            if (response.statusCode() != 200) {
                close(response.body());
                String detail = response.statusCode() == 401 ? " (authentication failed)" : "";
                throw new IllegalStateException("Registry tag request failed: HTTP " + response.statusCode() + detail + ".");
            }

            byte[] body = readBounded(response.body(), limits.maxTagBodyBytes(), "Registry tag body byte limit exceeded.");
            tagCount = parseTags(body, tags, tagCount);
            next = next(response.headers().allValues("Link"), next, origin);
        }
        return List.copyOf(tags);
    }

    private int parseTags(byte[] body, Set<String> tags, int tagCount) {
        try {
            JsonNode root = json.readTree(body);
            JsonNode values = root == null ? null : root.get("tags");
            if (values == null || !values.isArray()) {
                throw new IllegalStateException("Invalid registry tag response: tags must be an array.");
            }
            for (JsonNode value : values) {
                if (!value.isTextual()) {
                    throw new IllegalStateException("Invalid registry tag response: every tag must be a string.");
                }
                if (++tagCount > limits.maxTags()) {
                    throw new IllegalStateException("Registry tag limit exceeded.");
                }
                tags.add(value.textValue());
            }
            return tagCount;
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new IllegalStateException("Invalid registry tag response.", exception);
        }
    }

    private HttpResponse<InputStream> sendRegistry(URI uri, String bearer) {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(REQUEST_TIMEOUT).GET();
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        } else {
            credentials.ifPresent(value -> request.header("Authorization", value.basic()));
        }
        return send(request.build(), "Registry request failed.");
    }

    private String token(HttpResponse<InputStream> challengeResponse, String repository, URI registryOrigin) {
        String header = challengeResponse.headers().firstValue("WWW-Authenticate")
                .orElseThrow(() -> new IllegalStateException("Registry did not provide a Bearer challenge."));
        if (!header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new IllegalStateException("Registry did not provide a supported Bearer challenge.");
        }
        Map<String, String> parameters = authParameters(header.substring(7));
        String realm = required(parameters, "realm", "Registry Bearer challenge lacks realm.");
        String service = required(parameters, "service", "Registry Bearer challenge lacks service.");
        String scope = parameters.getOrDefault("scope", "repository:" + repository + ":pull");

        URI endpoint;
        try {
            URI parsedRealm = URI.create(realm);
            if (!validBearerRealm(registryOrigin, parsedRealm)) {
                throw new IllegalArgumentException();
            }
            endpoint = appendQuery(parsedRealm, "service=" + encode(service) + "&scope=" + encode(scope));
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Registry Bearer challenge has an invalid realm.", exception);
        }

        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint).timeout(REQUEST_TIMEOUT).GET();
        credentials.ifPresent(value -> request.header("Authorization", value.basic()));
        HttpResponse<InputStream> response = send(request.build(), "Registry token request failed.");
        if (response.statusCode() != 200) {
            close(response.body());
            throw new IllegalStateException("Registry token request failed: HTTP " + response.statusCode() + ".");
        }
        byte[] body = readBounded(response.body(), limits.maxTokenBodyBytes(), "Registry token body byte limit exceeded.");
        try {
            JsonNode root = json.readTree(body);
            if (root == null || !root.isObject()) {
                throw new IllegalStateException("Invalid registry token response.");
            }
            String value = textual(root, "token");
            if (value == null || value.isBlank()) {
                value = textual(root, "access_token");
            }
            if (value == null || value.isBlank()) {
                throw new IllegalStateException("Registry token response lacks token or access_token.");
            }
            return value;
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new IllegalStateException("Invalid registry token response.", exception);
        }
    }

    private boolean validBearerRealm(URI registryOrigin, URI realm) {
        if (!isHttp(realm) || realm.getHost() == null || realm.getUserInfo() != null || realm.getFragment() != null) {
            return false;
        }
        boolean sameOrigin = sameOrigin(registryOrigin, realm);
        if ("https".equalsIgnoreCase(registryOrigin.getScheme())) {
            if (!"https".equalsIgnoreCase(realm.getScheme())) {
                return false;
            }
        } else if (!"http".equalsIgnoreCase(realm.getScheme()) || !sameOrigin) {
            return false;
        }
        return credentials.isEmpty() || sameOrigin || "https".equalsIgnoreCase(realm.getScheme());
    }

    private HttpResponse<InputStream> send(HttpRequest request, String failure) {
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure, exception);
        } catch (IOException exception) {
            throw new IllegalStateException(failure, exception);
        }
    }

    private static byte[] readBounded(InputStream stream, int maximum, String failure) {
        try (stream) {
            byte[] bytes = stream.readNBytes(maximum + 1);
            if (bytes.length > maximum) {
                throw new IllegalStateException(failure);
            }
            return bytes;
        } catch (IOException exception) {
            throw new IllegalStateException("Registry response body could not be read.", exception);
        }
    }

    private static URI next(List<String> headers, URI current, URI origin) {
        if (headers.isEmpty()) {
            return null;
        }
        URI result = null;
        for (String header : headers) {
            for (String value : splitLinks(header)) {
                Link link = parseLink(value);
                if (link.next()) {
                    if (result != null) {
                        throw new IllegalStateException("Registry Link header has multiple next relations.");
                    }
                    try {
                        result = current.resolve(link.target());
                    } catch (IllegalArgumentException exception) {
                        throw new IllegalStateException("Registry Link header has an invalid next target.", exception);
                    }
                }
            }
        }
        if (result == null) {
            return null;
        }
        requireSameOrigin(origin, result);
        if (result.getUserInfo() != null || result.getFragment() != null) {
            throw new IllegalStateException("Registry Link next target is malformed.");
        }
        return result;
    }

    private static List<String> splitLinks(String header) {
        List<String> links = new ArrayList<>();
        int start = 0;
        boolean quoted = false;
        boolean angled = false;
        boolean escaped = false;
        for (int index = 0; index < header.length(); index++) {
            char character = header.charAt(index);
            if (escaped) escaped = false;
            else if (quoted && character == '\\') escaped = true;
            else if (character == '"') quoted = !quoted;
            else if (!quoted && character == '<') {
                if (angled) throw new IllegalStateException("Malformed Registry Link header.");
                angled = true;
            } else if (!quoted && character == '>') {
                if (!angled) throw new IllegalStateException("Malformed Registry Link header.");
                angled = false;
            } else if (!quoted && !angled && character == ',') {
                links.add(header.substring(start, index));
                start = index + 1;
            }
        }
        if (quoted || angled || escaped) {
            throw malformedLink();
        }
        links.add(header.substring(start));
        return links;
    }

    private static Link parseLink(String value) {
        String trimmed = value.trim();
        if (!trimmed.startsWith("<")) {
            throw malformedLink();
        }
        int close = trimmed.indexOf('>');
        if (close < 2) {
            throw malformedLink();
        }
        String target = trimmed.substring(1, close);
        String remainder = trimmed.substring(close + 1).trim();
        boolean next = false;
        if (!remainder.isEmpty()) {
            for (String rawAttribute : splitAttributes(remainder)) {
                String attribute = rawAttribute.trim();
                if (attribute.isEmpty()) {
                    continue;
                }
                int equals = attribute.indexOf('=');
                if (equals < 1) {
                    throw malformedLink();
                }
                String name = attribute.substring(0, equals).trim();
                String content = attribute.substring(equals + 1).trim();
                if (content.startsWith("\"") || content.endsWith("\"")) {
                    if (content.length() < 2 || !content.startsWith("\"") || !content.endsWith("\"")) {
                        throw malformedLink();
                    }
                    content = content.substring(1, content.length() - 1);
                }
                if (name.equalsIgnoreCase("rel")) {
                    for (String relation : content.split("\\s+")) {
                        next |= relation.equalsIgnoreCase("next");
                    }
                }
            }
        }
        return new Link(target, next);
    }

    private static List<String> splitAttributes(String value) {
        List<String> attributes = new ArrayList<>();
        int start = 0;
        boolean quoted = false;
        boolean escaped = false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (escaped) escaped = false;
            else if (quoted && character == '\\') escaped = true;
            else if (character == '"') quoted = !quoted;
            else if (!quoted && character == ';') {
                attributes.add(value.substring(start, index));
                start = index + 1;
            }
        }
        if (quoted || escaped) {
            throw malformedLink();
        }
        attributes.add(value.substring(start));
        return attributes;
    }

    private static Map<String, String> authParameters(String value) {
        Map<String, String> result = new HashMap<>();
        Matcher matcher = AUTH_PARAMETER.matcher(value);
        int end = 0;
        while (matcher.find()) {
            String gap = value.substring(end, matcher.start()).trim();
            if (!gap.isEmpty() && !gap.equals(",")) throw new IllegalStateException("Malformed Registry Bearer challenge.");
            result.put(matcher.group(1).toLowerCase(Locale.ROOT), matcher.group(2));
            end = matcher.end();
        }
        String tail = value.substring(end).trim();
        if (result.isEmpty() || (!tail.isEmpty() && !tail.equals(","))) {
            throw new IllegalStateException("Malformed Registry Bearer challenge.");
        }
        return result;
    }

    private static URI validateRegistry(URI registry) {
        Objects.requireNonNull(registry, "registry");
        if (!isHttp(registry) || registry.getHost() == null || registry.getUserInfo() != null
                || registry.getQuery() != null || registry.getFragment() != null
                || !(registry.getPath().isEmpty() || registry.getPath().equals("/"))) {
            throw new IllegalArgumentException("registry URL must be an absolute HTTP(S) origin.");
        }
        return URI.create(registry.getScheme().toLowerCase(Locale.ROOT) + "://" + registry.getRawAuthority());
    }

    private static void validateRepository(String repository) {
        if (repository == null || repository.isBlank() || repository.length() > 255) {
            throw new IllegalArgumentException("repository must be a valid lowercase Registry V2 repository path.");
        }
        for (String component : repository.split("/", -1)) {
            if (!REPOSITORY_COMPONENT.matcher(component).matches()) {
                throw new IllegalArgumentException("repository must be a valid lowercase Registry V2 repository path.");
            }
        }
    }

    private static void requireSameOrigin(URI origin, URI candidate) {
        if (!sameOrigin(origin, candidate)) {
            throw new IllegalStateException("Registry pagination next target must remain on the registry origin.");
        }
    }

    private static boolean sameOrigin(URI origin, URI candidate) {
        return isHttp(candidate) && candidate.getHost() != null
                && origin.getScheme().equalsIgnoreCase(candidate.getScheme())
                && origin.getHost().equalsIgnoreCase(candidate.getHost())
                && effectivePort(origin) == effectivePort(candidate);
    }

    private static boolean isHttp(URI value) {
        return value.isAbsolute() && ("http".equalsIgnoreCase(value.getScheme()) || "https".equalsIgnoreCase(value.getScheme()));
    }

    private static int effectivePort(URI value) {
        if (value.getPort() >= 0) return value.getPort();
        return "https".equalsIgnoreCase(value.getScheme()) ? 443 : 80;
    }

    private static URI appendQuery(URI uri, String query) {
        return URI.create(uri + (uri.getRawQuery() == null ? "?" : "&") + query);
    }

    private static String required(Map<String, String> parameters, String name, String failure) {
        String value = parameters.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(failure);
        }
        return value;
    }

    private static IllegalStateException malformedLink() {
        return new IllegalStateException("Malformed Registry Link header.");
    }

    private static String textual(JsonNode root, String field) {
        JsonNode value = root.get(field);
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static void close(InputStream stream) {
        try {
            stream.close();
        } catch (IOException ignored) {
            // The response is already being discarded.
        }
    }

    record Credentials(String username, String password) {
        Credentials {
            Objects.requireNonNull(username, "username");
            Objects.requireNonNull(password, "password");
        }

        String basic() {
            return "Basic " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
        }
    }

    record Limits(int maxPages, int maxTags, int maxTagBodyBytes, int maxTokenBodyBytes) {
        Limits {
            if (maxPages < 1 || maxTags < 1 || maxTagBodyBytes < 1 || maxTokenBodyBytes < 1) {
                throw new IllegalArgumentException("Registry client limits must be positive.");
            }
        }

        static Limits defaults() {
            return new Limits(50, 5000, 1024 * 1024, 64 * 1024);
        }
    }

    private record Link(String target, boolean next) { }
}
