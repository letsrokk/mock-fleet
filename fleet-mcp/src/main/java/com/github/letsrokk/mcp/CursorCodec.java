package com.github.letsrokk.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;

public final class CursorCodec {

    private static final int VERSION = 1;
    private final ObjectMapper mapper;

    public CursorCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String encode(String toolName, JsonNode scope, long position) {
        if (position < 0) {
            throw new IllegalArgumentException("Cursor position cannot be negative");
        }
        ObjectNode payload = mapper.createObjectNode();
        payload.put("version", VERSION);
        payload.put("tool", toolName);
        payload.put("scope", scopeHash(scope));
        payload.put("position", position);
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mapper.writeValueAsBytes(payload));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to encode cursor", e);
        }
    }

    public long decode(String toolName, JsonNode scope, String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return 0;
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(cursor);
            JsonNode payload = mapper.readTree(new String(decoded, StandardCharsets.UTF_8));
            if (!payload.isObject() || payload.path("version").asInt(-1) != VERSION
                    || !toolName.equals(payload.path("tool").asText())
                    || !scopeHash(scope).equals(payload.path("scope").asText())
                    || !payload.path("position").isIntegralNumber()
                    || payload.path("position").asLong(-1) < 0) {
                throw new IllegalArgumentException("Cursor does not match this tool call");
            }
            return payload.path("position").asLong();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid cursor: " + e.getMessage());
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid cursor");
        }
    }

    private String scopeHash(JsonNode scope) {
        try {
            byte[] canonical = mapper.writeValueAsBytes(canonical(scope == null ? mapper.nullNode() : scope));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to hash cursor scope", e);
        }
    }

    private JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            ObjectNode result = mapper.createObjectNode();
            List<String> names = new ArrayList<>();
            value.fieldNames().forEachRemaining(names::add);
            names.sort(Comparator.naturalOrder());
            for (String name : names) {
                result.set(name, canonical(value.get(name)));
            }
            return result;
        }
        if (value.isArray()) {
            ArrayNode result = mapper.createArrayNode();
            value.forEach(item -> result.add(canonical(item)));
            return result;
        }
        return value;
    }
}
