package com.github.letsrokk.mcp;

import io.quarkiverse.mcp.server.InputSchemaGenerator;
import io.quarkiverse.mcp.server.ToolManager;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;

@Singleton
public final class BodyInputSchemaGenerator implements InputSchemaGenerator<JsonObject> {

    private final FleetMcpConfig config;

    public BodyInputSchemaGenerator(FleetMcpConfig config) {
        this.config = config;
    }

    @Override
    public JsonObject generate(ToolManager.ToolInfo tool) {
        JsonObject properties = new JsonObject().put("mockId", mockId());
        JsonArray required = new JsonArray().add("mockId");
        if ("send_request".equals(tool.name())) {
            properties.put("method", string("HTTP method"));
            properties.put("path", string("Relative path and optional query"));
            JsonObject headerValue = new JsonObject().put("oneOf", new JsonArray()
                    .add(new JsonObject().put("type", "string"))
                    .add(new JsonObject().put("type", "array")
                            .put("items", new JsonObject().put("type", "string"))));
            properties.put("headers", new JsonObject().put("type", "object")
                    .put("description", "Request headers; values may be strings or arrays of strings")
                    .put("additionalProperties", headerValue));
            properties.put("body", body("Encoded request body"));
            required.add("method").add("path");
        } else if ("put_body_file".equals(tool.name())) {
            properties.put("fileName", string("Relative body-file name"));
            properties.put("body", body("Encoded file content"));
            required.add("fileName").add("body");
        } else {
            throw new IllegalArgumentException("Unsupported body input schema tool: " + tool.name());
        }
        return new JsonObject().put("type", "object").put("properties", properties)
                .put("required", required).put("additionalProperties", false);
    }

    private JsonObject body(String description) {
        JsonObject properties = new JsonObject()
                .put("encoding", string("Byte encoding").put("enum", new JsonArray().add("utf8").add("base64")))
                .put("data", string("UTF-8 text or base64 data"))
                .put("sizeBytes", new JsonObject().put("type", "integer")
                        .put("description", "Decoded byte length")
                        .put("minimum", 0).put("maximum", config.maxPayloadBytes()));
        return new JsonObject().put("type", "object").put("description", description).put("properties", properties)
                .put("required", new JsonArray().add("encoding").add("data").add("sizeBytes"))
                .put("additionalProperties", false)
                .put("examples", new JsonArray()
                        .add(new JsonObject().put("encoding", "utf8").put("data", "hello").put("sizeBytes", 5))
                        .add(new JsonObject().put("encoding", "base64").put("data", "AAE=").put("sizeBytes", 2)));
    }

    private JsonObject string(String description) {
        return new JsonObject().put("type", "string").put("description", description);
    }

    private JsonObject mockId() {
        return string("Mock ID").put("pattern", MockIdValidator.pattern())
                .put("maxLength", MockIdValidator.maxLength());
    }
}
