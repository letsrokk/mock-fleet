package com.github.letsrokk.mcp;

import io.quarkiverse.mcp.server.InputSchemaGenerator;
import io.quarkiverse.mcp.server.ToolManager;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;

@Singleton
public final class RichJsonInputSchemaGenerator implements InputSchemaGenerator<JsonObject> {

    private final FleetMcpConfig config;

    public RichJsonInputSchemaGenerator(FleetMcpConfig config) {
        this.config = config;
    }

    @Override
    public JsonObject generate(ToolManager.ToolInfo tool) {
        JsonObject properties = new JsonObject().put("mockId", mockId());
        JsonArray required = new JsonArray().add("mockId");
        switch (tool.name()) {
            case "create_stub" -> addMapping(properties, required, false);
            case "update_stub" -> {
                properties.put("stubId", string("WireMock stub UUID"));
                required.add("stubId");
                addMapping(properties, required, false);
            }
            case "patch_stub" -> {
                properties.put("stubId", string("WireMock stub UUID"));
                properties.put("patch", jsonObject("JSON Merge Patch: omit to preserve, null to remove, arrays replace",
                        new JsonArray().add(new JsonObject().put("name", "renamed")
                                .put("request", new JsonObject().put("urlPath", "/orders")))));
                required.add("stubId").add("patch");
            }
            case "delete_stubs" -> {
                properties.put("stubIds", new JsonObject().put("type", "array")
                        .put("description", "Distinct explicit stub IDs to delete")
                        .put("items", string("WireMock stub ID").put("pattern", "[0-9A-Za-z-]{1,128}"))
                        .put("minItems", 1).put("maxItems", config.maxPageSize()).put("uniqueItems", true));
                required.add("stubIds");
            }
            case "find_requests" -> {
                addRequestPattern(properties, required, false);
                addPagination(properties);
            }
            case "count_requests" -> addRequestPattern(properties, required, false);
            case "get_near_misses" -> {
                addRequestPattern(properties, required, true);
                addPagination(properties);
            }
            case "start_recording" -> {
                properties.put("recording", jsonObject("Native WireMock recorder JSON", new JsonArray()
                        .add(new JsonObject().put("repeatsAsScenarios", false).put("targetBaseUrl", "https://api.example.test")
                                .put("filters", new JsonObject().put("urlPathPattern", "/v1/.*")))));
                required.add("recording");
            }
            case "snapshot_requests" -> {
                properties.put("snapshot", jsonObject("Native WireMock snapshot JSON. Put request matchers (including bodyPatterns), ids, and allowNonProxied inside filters", new JsonArray()
                        .add(new JsonObject().put("repeatsAsScenarios", false).put("filters", new JsonObject()
                                .put("method", "POST").put("urlPath", "/soap")
                                .put("bodyPatterns", new JsonArray().add(new JsonObject().put("contains", "<Action>A</Action>")))))
                        .add(new JsonObject().put("repeatsAsScenarios", false).put("filters", new JsonObject()
                                .put("ids", new JsonArray().add("d13b8bb8-95bb-4c44-b7d7-601baa333c1c"))))));
                required.add("snapshot");
            }
            default -> throw new IllegalArgumentException("Unsupported rich input schema tool: " + tool.name());
        }
        if (tool.name().equals("create_stub") || tool.name().equals("update_stub") || tool.name().equals("patch_stub")) {
            properties.put("includeBody", new JsonObject().put("type", "boolean")
                    .put("description", "Return the full mapping with inline response bodies; defaults to true. Set false for compact output").put("default", true));
        }
        return new JsonObject().put("type", "object").put("properties", properties)
                .put("required", required).put("additionalProperties", false);
    }

    private void addMapping(JsonObject properties, JsonArray required, boolean optional) {
        properties.put("mapping", jsonObject("Native WireMock stub mapping JSON", new JsonArray().add(new JsonObject()
                .put("request", new JsonObject().put("method", "GET").put("urlPath", "/orders/42"))
                .put("response", new JsonObject().put("status", 200)
                        .put("jsonBody", new JsonObject().put("id", 42))))));
        if (!optional) {
            required.add("mapping");
        }
    }

    private void addRequestPattern(JsonObject properties, JsonArray required, boolean optional) {
        properties.put("requestPattern", jsonObject("Native WireMock request-pattern JSON", new JsonArray()
                .add(new JsonObject().put("method", "POST").put("urlPath", "/orders")
                        .put("headers", new JsonObject().put("Content-Type",
                                new JsonObject().put("contains", "application/json"))))));
        if (!optional) {
            required.add("requestPattern");
        }
    }

    private void addPagination(JsonObject properties) {
        properties.put("limit", new JsonObject().put("type", "integer")
                .put("description", "Page size").put("minimum", 1).put("maximum", config.maxPageSize()));
        properties.put("cursor", string("Opaque continuation cursor"));
    }

    private JsonObject jsonObject(String description, JsonArray examples) {
        return new JsonObject().put("type", "object").put("description", description)
                .put("additionalProperties", true).put("examples", examples);
    }

    private JsonObject string(String description) {
        return new JsonObject().put("type", "string").put("description", description);
    }

    private JsonObject mockId() {
        return string("Mock ID").put("pattern", MockIdValidator.pattern())
                .put("maxLength", MockIdValidator.maxLength());
    }
}
