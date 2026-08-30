package com.github.letsrokk.mcp;

import io.quarkiverse.mcp.server.ToolCallException;
import io.quarkiverse.mcp.server.ToolInputGuardrail;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Singleton
public final class StrictToolInputGuardrail implements ToolInputGuardrail {

    @Override
    public void apply(ToolInputContext context) {
        JsonObject schema = context.getTool().asJson().getJsonObject("inputSchema");
        List<String> failures = new ArrayList<>();
        validate(context.getArguments().getMap(), schema, "$", failures);
        if (!failures.isEmpty()) {
            throw new ToolCallException("Invalid tool arguments: " + String.join("; ", failures));
        }
    }

    @SuppressWarnings("unchecked")
    private void validate(Object value, JsonObject schema, String path, List<String> failures) {
        JsonArray alternatives = schema.getJsonArray("oneOf");
        if (alternatives != null) {
            for (Object alternative : alternatives) {
                List<String> alternativeFailures = new ArrayList<>();
                JsonObject alternativeSchema = alternative instanceof JsonObject json
                        ? json : new JsonObject((Map<String, Object>) alternative);
                validate(value, alternativeSchema, path, alternativeFailures);
                if (alternativeFailures.isEmpty()) {
                    return;
                }
            }
            failures.add(path + " does not match an allowed schema");
            return;
        }
        Object type = schema.getValue("type");
        if (type instanceof String expectedType && !matchesType(value, expectedType)) {
            failures.add(path + " must be " + expectedType);
            return;
        }

        JsonArray allowed = schema.getJsonArray("enum");
        if (allowed != null && !allowed.contains(value)) {
            failures.add(path + " is not an allowed value");
        }
        Number minimum = schema.getNumber("minimum");
        if (minimum != null && value instanceof Number number
                && Double.compare(number.doubleValue(), minimum.doubleValue()) < 0) {
            failures.add(path + " must be at least " + minimum);
        }
        String pattern = schema.getString("pattern");
        if (pattern != null && value instanceof String text && !Pattern.matches(pattern, text)) {
            failures.add(path + " does not match the required format");
        }
        Integer maxLength = schema.getInteger("maxLength");
        if (maxLength != null && value instanceof String text && text.length() > maxLength) {
            failures.add(path + " must contain at most " + maxLength + " characters");
        }

        if (value instanceof Map<?, ?> rawObject && "object".equals(type)) {
            Map<String, Object> object = (Map<String, Object>) rawObject;
            JsonObject properties = schema.getJsonObject("properties", new JsonObject());
            JsonArray required = schema.getJsonArray("required", new JsonArray());
            for (Object requiredName : required) {
                if (!object.containsKey(requiredName.toString())) {
                    failures.add(path + "." + requiredName + " is required");
                }
            }
            Object additional = schema.getValue("additionalProperties");
            for (Map.Entry<String, Object> entry : object.entrySet()) {
                JsonObject propertySchema = properties.getJsonObject(entry.getKey());
                if (propertySchema != null) {
                    validate(entry.getValue(), propertySchema, path + "." + entry.getKey(), failures);
                } else if (Boolean.FALSE.equals(additional)) {
                    failures.add(path + "." + entry.getKey() + " is not allowed");
                } else if (additional instanceof JsonObject additionalSchema) {
                    validate(entry.getValue(), additionalSchema, path + "." + entry.getKey(), failures);
                } else if (additional instanceof Map<?, ?> additionalSchema) {
                    validate(entry.getValue(), new JsonObject((Map<String, Object>) additionalSchema),
                            path + "." + entry.getKey(), failures);
                }
            }
        } else if (value instanceof List<?> array && "array".equals(type)) {
            JsonObject itemSchema = schema.getJsonObject("items");
            if (itemSchema != null) {
                for (int index = 0; index < array.size(); index++) {
                    validate(array.get(index), itemSchema, path + "[" + index + "]", failures);
                }
            }
        }
    }

    private boolean matchesType(Object value, String type) {
        return switch (type) {
            case "object" -> value instanceof Map<?, ?>;
            case "array" -> value instanceof List<?>;
            case "string" -> value instanceof String;
            case "integer" -> value instanceof Byte || value instanceof Short || value instanceof Integer
                    || value instanceof Long;
            case "number" -> value instanceof Number;
            case "boolean" -> value instanceof Boolean;
            case "null" -> value == null;
            default -> true;
        };
    }
}
