package com.github.letsrokk.mcp;

import io.quarkiverse.mcp.server.InputSchemaGenerator;
import io.quarkiverse.mcp.server.ToolManager;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;

@Singleton
public final class ImportMockConfigsInputSchemaGenerator implements InputSchemaGenerator<JsonObject> {

    @Override
    public JsonObject generate(ToolManager.ToolInfo tool) {
        JsonObject existing = new UpdateMockConfigInputSchemaGenerator().generate(tool).getJsonObject("properties");
        JsonObject entry = new JsonObject().put("type", "object")
                .put("properties", new JsonObject()
                        .put("mockId", existing.getJsonObject("mockId"))
                        .put("version", nullable(existing.getJsonObject("wireMockVersion")))
                        .put("options", existing.getJsonObject("options"))
                        .put("resources", nullable(existing.getJsonObject("resources"))))
                .put("required", new JsonArray().add("mockId").add("version").add("options").add("resources"))
                .put("additionalProperties", false);
        return new JsonObject().put("type", "object")
                .put("properties", new JsonObject()
                        .put("resourceVersion", existing.getJsonObject("resourceVersion"))
                        .put("mocks", new JsonObject().put("type", "array").put("items", entry)
                                .put("description", "Saved mock configuration array, in the same format as exports"))
                        .put("targetMockId", existing.getJsonObject("mockId").copy()
                                .put("description", "Override the ID of an import containing exactly one mock")))
                .put("required", new JsonArray().add("resourceVersion").add("mocks"))
                .put("additionalProperties", false);
    }

    private JsonObject nullable(JsonObject schema) {
        return new JsonObject().put("oneOf", new JsonArray().add(schema)
                .add(new JsonObject().put("type", "null")));
    }
}
