package com.github.letsrokk.mcp;

import io.quarkiverse.mcp.server.GlobalInputSchemaGenerator;
import io.quarkiverse.mcp.server.ToolManager;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;
import java.lang.reflect.Type;

@Singleton
public final class StrictInputSchemaGenerator implements GlobalInputSchemaGenerator {

    @Override
    public InputSchema generate(ToolManager.ToolInfo tool) {
        JsonObject properties = new JsonObject();
        JsonArray required = new JsonArray();
        for (ToolManager.ToolArgument argument : tool.arguments()) {
            properties.put(argument.name(), schema(argument.type(), argument.description(), argument.defaultValue()));
            if (argument.required()) {
                required.add(argument.name());
            }
        }
        JsonObject schema = new JsonObject()
                .put("type", "object")
                .put("properties", properties)
                .put("required", required)
                .put("additionalProperties", false);
        return new StrictInputSchema(schema);
    }

    private JsonObject schema(Type type, String description, String defaultValue) {
        String name = type.getTypeName();
        JsonObject schema = new JsonObject();
        if (name.equals("java.lang.String")) {
            schema.put("type", "string");
        } else if (name.equals("java.lang.Integer") || name.equals("int") || name.equals("long")
                || name.equals("java.lang.Long")) {
            schema.put("type", "integer");
        } else if (name.equals("boolean") || name.equals("java.lang.Boolean")) {
            schema.put("type", "boolean");
        } else {
            throw new IllegalArgumentException("Tool " + type + " requires a custom input schema generator");
        }
        if (description != null && !description.isBlank()) {
            schema.put("description", description);
        }
        if (defaultValue != null && !defaultValue.isBlank()) {
            if (name.equals("boolean") || name.equals("java.lang.Boolean")) {
                schema.put("default", Boolean.parseBoolean(defaultValue));
            } else if (name.equals("java.lang.Integer") || name.equals("int") || name.equals("long")
                    || name.equals("java.lang.Long")) {
                schema.put("default", Long.parseLong(defaultValue));
            } else {
                schema.put("default", defaultValue);
            }
        }
        return schema;
    }

    private record StrictInputSchema(JsonObject schema) implements InputSchema {
        @Override
        public Object value() {
            return schema;
        }

        @Override
        public String asJson() {
            return schema.encode();
        }
    }
}
