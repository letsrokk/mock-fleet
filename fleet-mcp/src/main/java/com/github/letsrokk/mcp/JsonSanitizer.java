package com.github.letsrokk.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

public final class JsonSanitizer {

    private final ObjectMapper mapper;
    private final Set<String> sensitiveHeaders;
    private final McpMetrics metrics;

    public JsonSanitizer(ObjectMapper mapper, Set<String> sensitiveHeaders) {
        this(mapper, sensitiveHeaders, null);
    }

    public JsonSanitizer(ObjectMapper mapper, Set<String> sensitiveHeaders, McpMetrics metrics) {
        this.mapper = mapper;
        this.metrics = metrics;
        this.sensitiveHeaders = sensitiveHeaders.stream()
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    public JsonNode redactHeaders(JsonNode input) {
        JsonNode copy = input == null ? mapper.nullNode() : input.deepCopy();
        record(sanitize(copy, false));
        return copy;
    }

    public JsonNode removeSensitiveHeaders(JsonNode input) {
        JsonNode copy = input == null ? mapper.nullNode() : input.deepCopy();
        record(sanitize(copy, true));
        return copy;
    }

    private int sanitize(JsonNode node, boolean remove) {
        int count = 0;
        if (node instanceof ObjectNode object) {
            var names = new java.util.ArrayList<String>();
            object.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                JsonNode child = object.get(name);
                if ("headers".equalsIgnoreCase(name) && child instanceof ObjectNode headers) {
                    count += sanitizeHeaders(headers, remove);
                } else if ("cookies".equalsIgnoreCase(name) && sensitiveHeaders.contains("cookie")
                        && looksLikeRequest(object)) {
                    count++;
                    if (remove) {
                        object.remove(name);
                    } else {
                        redactValues(child);
                    }
                } else {
                    count += sanitize(child, remove);
                }
            }
        } else if (node instanceof ArrayNode array) {
            for (JsonNode child : array) {
                count += sanitize(child, remove);
            }
        }
        return count;
    }

    private boolean looksLikeRequest(ObjectNode object) {
        return object.has("headers") || object.has("method") || object.has("url") || object.has("absoluteUrl")
                || object.has("urlPath") || object.has("clientIp") || object.has("browserProxyRequest");
    }

    private void redactValues(JsonNode node) {
        if (node instanceof ObjectNode object) {
            var names = new java.util.ArrayList<String>();
            object.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                JsonNode child = object.get(name);
                if (child.isContainerNode()) {
                    redactValues(child);
                } else {
                    object.put(name, "[REDACTED]");
                }
            }
        } else if (node instanceof ArrayNode array) {
            for (int index = 0; index < array.size(); index++) {
                JsonNode child = array.get(index);
                if (child.isContainerNode()) {
                    redactValues(child);
                } else {
                    array.set(index, mapper.getNodeFactory().textNode("[REDACTED]"));
                }
            }
        }
    }

    private int sanitizeHeaders(ObjectNode headers, boolean remove) {
        int count = 0;
        var names = new java.util.ArrayList<String>();
        headers.fieldNames().forEachRemaining(names::add);
        for (String name : names) {
            if (sensitiveHeaders.contains(name.toLowerCase(Locale.ROOT))) {
                count++;
                if (remove) {
                    headers.remove(name);
                } else {
                    headers.put(name, "[REDACTED]");
                }
            }
        }
        return count;
    }

    private void record(int count) {
        if (metrics != null) {
            metrics.headersRedacted(count);
        }
    }
}
