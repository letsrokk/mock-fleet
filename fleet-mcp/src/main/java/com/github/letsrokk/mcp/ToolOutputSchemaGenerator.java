package com.github.letsrokk.mcp;

import io.quarkiverse.mcp.server.OutputSchemaGenerator;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;

@Singleton
public final class ToolOutputSchemaGenerator implements OutputSchemaGenerator {

    @Override
    public Object generate(Class<?> marker) {
        return new JsonObject().put("oneOf", new JsonArray()
                .add(success(marker))
                .add(errorEnvelope()));
    }

    private JsonObject success(Class<?> marker) {
        return switch (marker.getSimpleName()) {
            case "ListMocks" -> strict(properties(
                    "mocks", array(mockRow()), "page", page()));
            case "GetMockConfig" -> strict(properties(
                    "resourceVersion", nullableString(), "mock", mockConfig(), "routing", routing()));
            case "ListOptionDefinitions" -> strict(properties(
                    "wireMockVersion", versionString(),
                    "catalogStatus", string().put("enum", new JsonArray().add("supported").add("newer_unresearched")),
                    "options", array(optionDefinition())));
            case "UpdateMockConfig" -> strict(properties(
                    "resourceVersion", nullableString(), "mock", mockConfig(), "apply", apply()));
            case "DeleteMockConfig" -> strict(properties(
                    "resourceVersion", nullableString(), "mockId", string(), "deleted", bool(), "apply", apply()));
            case "StartMock" -> lifecycle();
            case "StopMock" -> stopLifecycle();
            case "StubPage" -> strict(properties(
                    "mockId", string(), "stubs", array(openObject()), "page", page()));
            case "Stub" -> strict(properties("mockId", string(), "stub", openObject()));
            case "DeleteStub" -> strict(properties(
                    "mockId", string(), "stubId", string(), "deleted", bool()));
            case "SendRequest" -> strict(properties(
                    "mockId", string(), "response", response()));
            case "RequestPage" -> strict(properties(
                    "mockId", string(), "requests", array(openObject()), "page", page()));
            case "CountRequests" -> strict(properties(
                    "mockId", string(), "count", integer()));
            case "NearMisses" -> strict(properties(
                    "mockId", string(), "nearMisses", array(openObject()), "page", page()));
            case "Reset" -> strict(properties("mockId", string(), "reset", bool()));
            case "RecordingStatus" -> strict(properties(
                    "mockId", string(), "status", openObject()));
            case "RecordingCandidates" -> strict(properties(
                    "mockId", string(), "candidateIds", array(string()), "candidateCount", integer(),
                    "matchedRequests", bool()));
            case "BodyFilePage" -> strict(properties(
                    "mockId", string(), "files", array(string()), "page", page()));
            case "GetBodyFile" -> strict(properties(
                    "mockId", string(), "fileName", string(), "body", body()));
            case "PutBodyFile" -> strict(properties(
                    "mockId", string(), "fileName", string(), "sizeBytes", integer()));
            case "DeleteBodyFile" -> strict(properties(
                    "mockId", string(), "fileName", string(), "deleted", bool(), "forced", bool()));
            case "ScenarioPage" -> strict(properties(
                    "mockId", string(), "scenarios", array(openObject()), "page", page()));
            default -> throw new IllegalArgumentException("Unknown MCP output schema marker: " + marker.getName());
        };
    }

    private JsonObject errorEnvelope() {
        JsonObject error = strict(properties(
                "code", string(),
                "message", string(),
                "retryable", bool(),
                "stateMayHaveChanged", bool(),
                "details", openObject()));
        return strict(properties("error", error));
    }

    private JsonObject lifecycle() {
        return strict(properties(
                "mockId", string(),
                "status", string().put("enum", new JsonArray().add("RUNNING").add("STARTING")),
                "podName", nullableString(),
                "message", nullableString(),
                "retryAfterMs", new JsonObject().put("type", new JsonArray().add("integer").add("null"))));
    }

    private JsonObject stopLifecycle() {
        return strict(properties(
                "mockId", string(),
                "status", string().put("enum", new JsonArray().add("STOPPED")),
                "podName", nullableString(),
                "message", nullableString(),
                "retryAfterMs", new JsonObject().put("type", new JsonArray().add("integer").add("null"))
                        .put("minimum", 0)));
    }

    private JsonObject response() {
        return strict(properties(
                "status", integer(),
                "headers", new JsonObject().put("type", "object")
                        .put("additionalProperties", array(string())),
                "contentType", string(),
                "body", body()));
    }

    private JsonObject body() {
        return strict(properties(
                "encoding", string().put("enum", new JsonArray().add("utf8").add("base64")),
                "data", string(),
                "sizeBytes", integer()));
    }

    private JsonObject mockRow() {
        return strict(properties(
                "mockId", string(),
                "lifecycle", lifecycleStatus(),
                "wireMockVersion", versionString(),
                "runtimeVersion", nullableVersionString(),
                "hasSavedConfig", bool()));
    }

    private JsonObject mockConfig() {
        return strict(properties(
                "mockId", string(),
                "lifecycle", lifecycleStatus(),
                "baseline", configData(true, false),
                "user", configData(true, true),
                "effective", configData(false, false),
                "wireMockVersion", versionString(),
                "runtimeVersion", nullableVersionString()));
    }

    private JsonObject configData(boolean nullableVersion, boolean nullableResources) {
        JsonObject resourceSchema = resources();
        if (nullableResources) {
            resourceSchema.put("type", new JsonArray().add("object").add("null"));
        }
        return strict(properties(
                "version", nullableVersion ? nullableVersionString() : versionString(),
                "options", array(string()),
                "resources", resourceSchema));
    }

    private JsonObject resources() {
        JsonObject stringMap = new JsonObject().put("type", "object").put("additionalProperties", string());
        return strict(properties("requests", stringMap.copy(), "limits", stringMap.copy()));
    }

    private JsonObject routing() {
        return strict(properties(
                "mode", string().put("enum", new JsonArray().add("HOST").add("PATH")),
                "host", string()));
    }

    private JsonObject apply() {
        return strict(properties(
                "mockId", string(),
                "mode", string().put("enum", new JsonArray().add("futureOnly").add("restartActive")),
                "lifecycle", lifecycleStatus()));
    }

    private JsonObject lifecycleStatus() {
        return string().put("enum", new JsonArray()
                .add("STOPPED").add("STARTING").add("RUNNING").add("FAILED"));
    }

    private JsonObject versionString() {
        return string().put("pattern", WireMockVersion.EXACT_PATTERN);
    }

    private JsonObject nullableVersionString() {
        return nullableString().put("pattern", WireMockVersion.EXACT_PATTERN);
    }

    private JsonObject page() {
        return strict(properties("limit", integer(), "returned", integer(), "hasMore", bool(),
                "nextCursor", nullableString()));
    }

    private JsonObject optionDefinition() {
        return strict(properties(
                "name", string(),
                "label", string(),
                "kind", string().put("enum", new JsonArray().add("flag").add("input").add("number")
                        .add("select").add("optional_number").add("optional_input")),
                "group", string(),
                "description", string(),
                "values", array(string()),
                "minimum", nullableInteger(),
                "maximum", nullableInteger()));
    }

    private JsonObject strict(JsonObject properties) {
        JsonArray requiredArray = new JsonArray();
        properties.fieldNames().forEach(requiredArray::add);
        return new JsonObject().put("type", "object").put("properties", properties)
                .put("required", requiredArray).put("additionalProperties", false);
    }

    private JsonObject properties(Object... pairs) {
        JsonObject properties = new JsonObject();
        for (int index = 0; index < pairs.length; index += 2) {
            properties.put((String) pairs[index], pairs[index + 1]);
        }
        return properties;
    }

    private JsonObject string() {
        return new JsonObject().put("type", "string");
    }

    private JsonObject nullableString() {
        return new JsonObject().put("type", new JsonArray().add("string").add("null"));
    }

    private JsonObject bool() {
        return new JsonObject().put("type", "boolean");
    }

    private JsonObject integer() {
        return new JsonObject().put("type", "integer");
    }

    private JsonObject nullableInteger() {
        return new JsonObject().put("type", new JsonArray().add("integer").add("null"));
    }

    private JsonObject array(JsonObject items) {
        return new JsonObject().put("type", "array").put("items", items);
    }

    private JsonObject openObject() {
        return new JsonObject().put("type", "object").put("additionalProperties", true);
    }
}
