package com.github.letsrokk.mcp;

import io.quarkiverse.mcp.server.GlobalInputSchemaGenerator;
import io.quarkiverse.mcp.server.ToolManager;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;
import java.lang.reflect.Type;

@Singleton
public final class StrictInputSchemaGenerator implements GlobalInputSchemaGenerator {

    private final FleetMcpConfig config;

    public StrictInputSchemaGenerator(FleetMcpConfig config) {
        this.config = config;
    }

    @Override
    public InputSchema generate(ToolManager.ToolInfo tool) {
        JsonObject properties = new JsonObject();
        JsonArray required = new JsonArray();
        for (ToolManager.ToolArgument argument : tool.arguments()) {
            properties.put(argument.name(), schema(tool.name(), argument));
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

    private JsonObject schema(String toolName, ToolManager.ToolArgument argument) {
        Type type = argument.type();
        String description = argument.description();
        String defaultValue = argument.defaultValue();
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
        if ("mockId".equals(argument.name())) {
            schema.put("pattern", MockIdValidator.pattern()).put("maxLength", MockIdValidator.maxLength());
        } else if ("limit".equals(argument.name())) {
            schema.put("minimum", 1).put("maximum", config.maxPageSize());
        } else if ("delete_mock_config".equals(toolName) && "applyMode".equals(argument.name())) {
            schema.put("enum", new JsonArray().add("futureOnly").add("restartActive"));
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
