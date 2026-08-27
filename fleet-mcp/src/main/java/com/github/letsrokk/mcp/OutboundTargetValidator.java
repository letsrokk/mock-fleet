package com.github.letsrokk.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class OutboundTargetValidator {

    private final TargetUrlPolicy policy;
    private final Set<String> allowedListeners;
    private final McpMetrics metrics;

    public OutboundTargetValidator(TargetUrlPolicy policy) {
        this(policy, Set.of(), null);
    }

    public OutboundTargetValidator(TargetUrlPolicy policy, Set<String> allowedListeners) {
        this(policy, allowedListeners, null);
    }

    public OutboundTargetValidator(TargetUrlPolicy policy, Set<String> allowedListeners, McpMetrics metrics) {
        this.policy = policy;
        this.metrics = metrics;
        this.allowedListeners = allowedListeners.stream()
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    public void validate(JsonNode value) {
        validate(value, false);
    }

    private void validate(JsonNode node, boolean webhookContext) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isArray()) {
            node.forEach(child -> validate(child, webhookContext));
            return;
        }
        if (!node.isObject()) {
            return;
        }
        validateListeners(node.path("serveEventListeners"));
        boolean webhook = webhookContext || "webhook".equalsIgnoreCase(node.path("name").asText());
        Iterator<Map.Entry<String, JsonNode>> fields = node.properties().iterator();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String name = field.getKey().toLowerCase(Locale.ROOT);
            JsonNode child = field.getValue();
            boolean targetField = "targetbaseurl".equals(name) || "proxybaseurl".equals(name)
                    || "proxyurl".equals(name) || (webhook && "url".equals(name));
            if (targetField && child.isTextual()) {
                policy.requireAllowed(URI.create(child.textValue()));
            }
            validate(child, webhook);
        }
    }

    private void validateListeners(JsonNode listeners) {
        if (listeners.isMissingNode() || listeners.isNull()) {
            return;
        }
        if (!listeners.isArray()) {
            recordBlock();
            throw new IllegalArgumentException("serveEventListeners must be an array");
        }
        for (JsonNode listener : listeners) {
            String name = listener.path("name").asText("").toLowerCase(Locale.ROOT);
            if (name.isBlank() || !allowedListeners.contains(name)) {
                recordBlock();
                throw new IllegalArgumentException("Serve event listener is not allowed: " + name);
            }
        }
    }

    private void recordBlock() {
        if (metrics != null) {
            metrics.targetBlocked();
        }
    }
}
