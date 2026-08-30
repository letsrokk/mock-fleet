package com.github.letsrokk.mcp;

import io.quarkiverse.mcp.server.InputSchemaGenerator;
import io.quarkiverse.mcp.server.ToolManager;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;

@Singleton
public final class UpdateMockConfigInputSchemaGenerator
        implements InputSchemaGenerator<JsonObject> {

    @Override
    public JsonObject generate(ToolManager.ToolInfo tool) {
        JsonObject stringMap = new JsonObject()
                .put("type", "object")
                .put("additionalProperties", new JsonObject().put("type", "string"));
        JsonObject resources = new JsonObject()
                .put("type", "object")
                .put("description", "Kubernetes resource override. Only cpu and memory are accepted, and effective "
                        + "values must stay within the cluster-owned request floor and limit ceiling. Omit resources "
                        + "or individual cpu and memory keys to inherit baseline values")
                .put("properties", new JsonObject()
                        .put("requests", stringMap.copy())
                        .put("limits", stringMap.copy()))
                .put("required", new JsonArray().add("requests").add("limits"))
                .put("additionalProperties", false);
        JsonObject schema = new JsonObject()
                .put("type", "object")
                .put("properties", new JsonObject()
                        .put("mockId", string("Mock ID").put("pattern", MockIdValidator.pattern())
                                .put("maxLength", MockIdValidator.maxLength()))
                        .put("resourceVersion", string("Current Fleet ConfigMap resourceVersion"))
                        .put("wireMockVersion", string("Exact selectable WireMock version, or the mock's current retained version; omit to inherit the catalog default")
                                .put("pattern", WireMockVersion.EXACT_PATTERN))
                        .put("options", new JsonObject()
                                .put("type", "array")
                                .put("description", "Complete mock-specific WireMock CLI option override list")
                                .put("items", new JsonObject().put("type", "string")))
                        .put("resources", resources)
                        .put("applyMode", string("How to apply the saved configuration")
                                .put("enum", new JsonArray().add("futureOnly").add("restartActive"))))
                .put("required", new JsonArray()
                        .add("mockId")
                        .add("resourceVersion")
                        .add("options")
                        .add("applyMode"))
                .put("additionalProperties", false);
        return schema;
    }

    private JsonObject string(String description) {
        return new JsonObject().put("type", "string").put("description", description);
    }
}
