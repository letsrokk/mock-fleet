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
                    "mocks", array(mockRow()), "page", page()), "mocks", "page");
            case "GetMockConfig" -> strict(properties(
                    "resourceVersion", nullableString(), "mock", mockConfig(), "routing", routing()),
                    "resourceVersion", "mock", "routing");
            case "ListOptionDefinitions" -> strict(properties(
                    "wireMockVersion", versionString(),
                    "catalogStatus", string().put("enum", new JsonArray().add("supported").add("newer_unresearched")),
                    "options", array(optionDefinition())),
                    "wireMockVersion", "catalogStatus", "options");
            case "UpdateMockConfig" -> strict(properties(
                    "resourceVersion", nullableString(), "mock", mockConfig(), "apply", apply()),
                    "resourceVersion", "mock", "apply");
            case "DeleteMockConfig" -> strict(properties(
                    "resourceVersion", nullableString(), "mockId", string(), "deleted", bool(), "apply", apply()),
                    "resourceVersion", "mockId", "deleted", "apply");
            case "StartMock" -> lifecycle();
            case "StopMock" -> stopLifecycle();
            case "StubPage" -> strict(properties(
                    "mockId", string(), "stubs", array(openObject()), "page", page()),
                    "mockId", "stubs", "page");
            case "Stub" -> strict(properties("mockId", string(), "stub", openObject()), "mockId", "stub");
            case "DeleteStub" -> strict(properties(
                    "mockId", string(), "stubId", string(), "deleted", bool()), "mockId", "stubId", "deleted");
            case "SendRequest" -> strict(properties(
                    "mockId", string(), "response", response()), "mockId", "response");
            case "RequestPage" -> strict(properties(
                    "mockId", string(), "requests", array(openObject()), "page", page()),
                    "mockId", "requests", "page");
            case "CountRequests" -> strict(properties(
                    "mockId", string(), "count", integer()), "mockId", "count");
            case "NearMisses" -> strict(properties(
                    "mockId", string(), "nearMisses", array(openObject()), "page", page()),
                    "mockId", "nearMisses", "page");
            case "Reset" -> strict(properties("mockId", string(), "reset", bool()), "mockId", "reset");
            case "RecordingStatus" -> strict(properties(
                    "mockId", string(), "status", openObject()), "mockId", "status");
            case "RecordingCandidates" -> strict(properties(
                    "mockId", string(), "candidateIds", array(string()), "candidateCount", integer(),
                    "matchedRequests", bool()), "mockId", "candidateIds", "candidateCount", "matchedRequests");
            case "BodyFilePage" -> strict(properties(
                    "mockId", string(), "files", array(string()), "page", page()), "mockId", "files", "page");
            case "GetBodyFile" -> strict(properties(
                    "mockId", string(), "fileName", string(), "body", body()),
                    "mockId", "fileName", "body");
            case "PutBodyFile" -> strict(properties(
                    "mockId", string(), "fileName", string(), "sizeBytes", integer()),
                    "mockId", "fileName", "sizeBytes");
            case "DeleteBodyFile" -> strict(properties(
                    "mockId", string(), "fileName", string(), "deleted", bool(), "forced", bool()),
                    "mockId", "fileName", "deleted", "forced");
            case "ScenarioPage" -> strict(properties(
                    "mockId", string(), "scenarios", array(openObject()), "page", page()),
                    "mockId", "scenarios", "page");
            default -> throw new IllegalArgumentException("Unknown MCP output schema marker: " + marker.getName());
        };
    }

    private JsonObject errorEnvelope() {
        JsonObject error = strict(properties(
                "code", string(),
                "message", string(),
                "retryable", bool(),
                "stateMayHaveChanged", bool(),
                "details", openObject()),
                "code", "message", "retryable", "stateMayHaveChanged", "details");
        return strict(properties("error", error), "error");
    }

    private JsonObject lifecycle() {
        return strict(properties(
                "mockId", string(),
                "status", string().put("enum", new JsonArray().add("RUNNING").add("STARTING")),
                "podName", nullableString(),
                "message", nullableString(),
                "retryAfterMs", new JsonObject().put("type", new JsonArray().add("integer").add("null"))),
                "mockId", "status", "podName", "message", "retryAfterMs");
    }

    private JsonObject stopLifecycle() {
        return strict(properties(
                "mockId", string(),
                "status", string().put("enum", new JsonArray().add("STOPPED")),
                "podName", nullableString(),
                "message", nullableString(),
                "retryAfterMs", new JsonObject().put("type", new JsonArray().add("integer").add("null"))
                        .put("minimum", 0)),
                "mockId", "status", "podName", "message", "retryAfterMs");
    }

    private JsonObject response() {
        return strict(properties(
                "status", integer(),
                "headers", new JsonObject().put("type", "object")
                        .put("additionalProperties", array(string())),
                "contentType", string(),
                "body", body()), "status", "headers", "contentType", "body");
    }

    private JsonObject body() {
        return strict(properties(
                "encoding", string().put("enum", new JsonArray().add("utf8").add("base64")),
                "data", string(),
                "sizeBytes", integer()), "encoding", "data", "sizeBytes");
    }

    private JsonObject mockRow() {
        return strict(properties(
                "mockId", string(),
                "lifecycle", lifecycleStatus(),
                "wireMockVersion", versionString(),
                "runtimeVersion", nullableVersionString(),
                "hasSavedConfig", bool()),
                "mockId", "lifecycle", "wireMockVersion", "runtimeVersion", "hasSavedConfig");
    }

    private JsonObject mockConfig() {
        return strict(properties(
                "mockId", string(),
                "lifecycle", lifecycleStatus(),
                "baseline", configData(true, false),
                "user", configData(true, true),
                "effective", configData(false, false),
                "wireMockVersion", versionString(),
                "runtimeVersion", nullableVersionString()),
                "mockId", "lifecycle", "baseline", "user", "effective", "wireMockVersion", "runtimeVersion");
    }

    private JsonObject configData(boolean nullableVersion, boolean nullableResources) {
        JsonObject resourceSchema = resources();
        if (nullableResources) {
            resourceSchema.put("type", new JsonArray().add("object").add("null"));
        }
        return strict(properties(
                "version", nullableVersion ? nullableVersionString() : versionString(),
                "options", array(string()),
                "resources", resourceSchema),
                "version", "options", "resources");
    }

    private JsonObject resources() {
        JsonObject stringMap = new JsonObject().put("type", "object").put("additionalProperties", string());
        return strict(properties("requests", stringMap.copy(), "limits", stringMap.copy()), "requests", "limits");
    }

    private JsonObject routing() {
        return strict(properties(
                "mode", string().put("enum", new JsonArray().add("HOST").add("PATH")),
                "host", string()), "mode", "host");
    }

    private JsonObject apply() {
        return strict(properties(
                "mockId", string(),
                "mode", string().put("enum", new JsonArray().add("futureOnly").add("restartActive")),
                "lifecycle", lifecycleStatus()), "mockId", "mode", "lifecycle");
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
                "nextCursor", nullableString()), "limit", "returned", "hasMore", "nextCursor");
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
                "maximum", nullableInteger()),
                "name", "label", "kind", "group", "description", "values", "minimum", "maximum");
    }

    private JsonObject strict(JsonObject properties, String... required) {
        JsonArray requiredArray = new JsonArray();
        for (String name : required) {
            requiredArray.add(name);
        }
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
