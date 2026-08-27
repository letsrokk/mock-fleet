package com.github.letsrokk.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.ToolResponse;
import io.vertx.core.http.HttpMethod;
import jakarta.inject.Singleton;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

@Singleton
public final class FleetMcpTools {

    private final FleetApiClient fleetApi;
    private final WireMockAdminClient wireMock;
    private final FleetMcpConfig config;
    private final ObjectMapper mapper;
    private final OutboundTargetValidator outboundTargets;
    private final PerMockCoordinator coordinator;
    private final McpToolExecutor executor;
    private final McpMetrics metrics;
    private final Set<String> sensitiveHeaders;

    public FleetMcpTools(FleetApiClient fleetApi, WireMockAdminClient wireMock, FleetMcpConfig config,
            ObjectMapper mapper, OutboundTargetValidator outboundTargets, PerMockCoordinator coordinator,
            McpToolExecutor executor, McpMetrics metrics) {
        this.fleetApi = fleetApi;
        this.wireMock = wireMock;
        this.config = config;
        this.mapper = mapper;
        this.outboundTargets = outboundTargets;
        this.coordinator = coordinator;
        this.executor = executor;
        this.metrics = metrics;
        this.sensitiveHeaders = config.sensitiveHeaders().stream()
                .map(value -> value.toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    @Tool(name = "list_mocks", description = "List active Mock Fleet mocks without starting any mock pod.", annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public ToolResponse listMocks(
            @ToolArg(description = "Page size", required = false) Integer limit,
            @ToolArg(description = "Zero-based offset", required = false) Integer offset) {
        return fleet("list_mocks", () -> McpToolExecutor.ToolResult.of("Listed active mocks.",
                page(fleetApi.listMocks(), "mocks", limit, offset)));
    }

    @Tool(name = "get_mock_config", description = "Get one mock's configuration and Fleet routing metadata.", annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public ToolResponse getMockConfig(@ToolArg(description = "Mock ID") String mockId) {
        return fleet("get_mock_config", () -> {
            MockIdValidator.requireValid(mockId);
            JsonNode view = fleetApi.getConfig();
            ObjectNode result = mapper.createObjectNode();
            result.set("resourceVersion", view.path("resourceVersion"));
            result.set("mock", requireMockConfig(view.path("mocks"), mockId, "NOT_FOUND"));
            result.set("routing", view.path("routing"));
            return McpToolExecutor.ToolResult.of("Loaded configuration for " + mockId + ".", result);
        });
    }

    @Tool(name = "list_option_definitions", description = "List supported WireMock CLI option definitions.", annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public ToolResponse listOptionDefinitions() {
        return fleet("list_option_definitions", () -> {
            JsonNode view = fleetApi.getConfig();
            ObjectNode result = mapper.createObjectNode();
            result.set("optionDefinitions", view.path("options"));
            return McpToolExecutor.ToolResult.of("Listed WireMock option definitions.", result);
        });
    }

    @Tool(name = "update_mock_config", description = "Replace a mock's complete options and resources using optimistic concurrency.",
            inputSchema = @Tool.InputSchema(generator = UpdateMockConfigInputSchemaGenerator.class),
            annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false))
    public ToolResponse updateMockConfig(
            @ToolArg(description = "Mock ID") String mockId,
            @ToolArg(description = "Current Fleet ConfigMap resourceVersion") String resourceVersion,
            @ToolArg(description = "Complete mock-specific WireMock CLI option override list") List<String> options,
            @ToolArg(description = "Kubernetes requests and limits override; omit to inherit baseline resources", required = false) MockResources resources,
            @ToolArg(description = "How to apply the saved configuration") ConfigApplyMode applyMode) {
        return fleet("update_mock_config", () -> {
            if (resources != null && resources.requests() == null) {
                throw new IllegalArgumentException("resources.requests is required when resources are provided");
            }
            if (resources != null && resources.limits() == null) {
                throw new IllegalArgumentException("resources.limits is required when resources are provided");
            }
            JsonNode view = fleetApi.updateConfig(mockId, resourceVersion, options, resources, applyMode);
            ObjectNode result = mapper.createObjectNode();
            result.set("resourceVersion", view.path("resourceVersion"));
            result.set("mock", requireMockConfig(view.path("mocks"), mockId, "INVALID_UPSTREAM_RESPONSE"));
            result.put("applyMode", applyMode.name());
            return McpToolExecutor.ToolResult.of("Updated configuration for " + mockId + ".", result);
        });
    }

    @Tool(name = "delete_mock_config", description = "Delete a mock-specific configuration using optimistic concurrency.",
            annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false))
    public ToolResponse deleteMockConfig(
            @ToolArg(description = "Mock ID") String mockId,
            @ToolArg(description = "Current Fleet ConfigMap resourceVersion") String resourceVersion,
            @ToolArg(description = "How to apply the deletion") ConfigApplyMode applyMode) {
        return fleet("delete_mock_config", () -> {
            JsonNode view = fleetApi.deleteConfig(mockId, resourceVersion, applyMode);
            JsonNode mocks = view.get("mocks");
            if (mocks == null || !mocks.isArray()) {
                throw invalidUpstreamResponse("Fleet API response does not contain a mocks array", mockId);
            }
            if (findMockConfig(mocks, mockId) != null) {
                throw invalidUpstreamResponse("Deleted mock is still present", mockId);
            }
            ObjectNode result = mapper.createObjectNode();
            result.set("resourceVersion", view.path("resourceVersion"));
            result.put("mockId", mockId);
            result.put("deleted", true);
            result.put("applyMode", applyMode.name());
            return McpToolExecutor.ToolResult.of("Deleted configuration for " + mockId + ".", result);
        });
    }

    @Tool(name = "stop_mock", description = "Stop an active mock pod. The next proxied request may start it again.",
            annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false))
    public ToolResponse stopMock(@ToolArg(description = "Mock ID") String mockId) {
        return fleet("stop_mock", () -> {
            fleetApi.stopMock(mockId);
            return McpToolExecutor.ToolResult.of("Stopped mock " + mockId + ".", Map.of("mockId", mockId, "stopped", true));
        });
    }

    @Tool(name = "list_stubs", description = "List WireMock stubs. This may start an inactive mock pod.", annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public ToolResponse listStubs(
            @ToolArg(description = "Mock ID") String mockId,
            @ToolArg(description = "Page size", required = false) Integer limit,
            @ToolArg(description = "Zero-based offset", required = false) Integer offset) {
        return wireMock("list_stubs", mockId, () -> McpToolExecutor.ToolResult.of("Listed stubs for " + mockId + ".",
                wireMock.listStubs(mockId, pageSize(limit), pageOffset(offset))));
    }

    @Tool(name = "list_unmatched_stubs", description = "List stubs that have not matched journaled requests. Requires WireMock 3.13+ and may start the pod.", annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public ToolResponse listUnmatchedStubs(@ToolArg(description = "Mock ID") String mockId,
            @ToolArg(description = "Page size", required = false) Integer limit,
            @ToolArg(description = "Zero-based offset", required = false) Integer offset) {
        return wireMock("list_unmatched_stubs", mockId, () -> McpToolExecutor.ToolResult.of(
                "Listed unmatched stubs for " + mockId + ".",
                page(wireMock.listUnmatchedStubs(mockId), "mappings", limit, offset)));
    }

    @Tool(name = "get_stub", description = "Get a WireMock stub by ID. This may start an inactive mock pod.", annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public ToolResponse getStub(@ToolArg(description = "Mock ID") String mockId,
            @ToolArg(description = "WireMock stub UUID") String stubId) {
        return wireMock("get_stub", mockId, () -> McpToolExecutor.ToolResult.of(
                "Loaded stub " + stubId + ".", wireMock.getStub(mockId, stubId)));
    }

    @Tool(name = "create_stub", description = "Create a temporary WireMock stub. Client-supplied id, uuid, and persistent fields are ignored.", annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = true, idempotentHint = false, openWorldHint = false))
    public ToolResponse createStub(@ToolArg(description = "Mock ID") String mockId,
            @ToolArg(description = "Native WireMock stub mapping JSON") Map<String, Object> mapping) {
        return wireMock("create_stub", mockId, () -> coordinator.serialized(mockId, () -> {
            ObjectNode payload = object(mapping, "mapping");
            outboundTargets.validate(payload);
            return McpToolExecutor.ToolResult.of("Created a temporary stub for " + mockId + ".",
                    wireMock.createStub(mockId, payload));
        }));
    }

    @Tool(name = "update_stub", description = "Update a WireMock stub while preserving its current persistence state.", annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = true, idempotentHint = false, openWorldHint = false))
    public ToolResponse updateStub(@ToolArg(description = "Mock ID") String mockId,
            @ToolArg(description = "WireMock stub UUID") String stubId,
            @ToolArg(description = "Native WireMock stub mapping JSON") Map<String, Object> mapping) {
        return wireMock("update_stub", mockId, () -> coordinator.serialized(mockId, () -> {
            ObjectNode payload = object(mapping, "mapping");
            outboundTargets.validate(payload);
            return McpToolExecutor.ToolResult.of("Updated stub " + stubId + ".",
                    wireMock.updateStub(mockId, stubId, payload));
        }));
    }

    @Tool(name = "delete_stub", description = "Delete a WireMock stub without deleting referenced body files.", annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = true, idempotentHint = false, openWorldHint = false))
    public ToolResponse deleteStub(@ToolArg(description = "Mock ID") String mockId,
            @ToolArg(description = "WireMock stub UUID") String stubId) {
        return wireMock("delete_stub", mockId, () -> coordinator.serialized(mockId, () -> {
            wireMock.deleteStub(mockId, stubId);
            return McpToolExecutor.ToolResult.of("Deleted stub " + stubId + ".",
                    Map.of("mockId", mockId, "stubId", stubId, "deleted", true));
        }));
    }

    @Tool(name = "persist_stub", description = "Idempotently persist a temporary stub after verifying referenced body files.", annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false))
    public ToolResponse persistStub(@ToolArg(description = "Mock ID") String mockId,
            @ToolArg(description = "WireMock stub UUID") String stubId) {
        return wireMock("persist_stub", mockId, () -> coordinator.serialized(mockId, () -> {
            requireStorage();
            JsonNode stub = wireMock.getStub(mockId, stubId);
            verifyBodyFiles(mockId, stub);
            JsonNode updated = wireMock.setPersistent(mockId, stubId, true);
            return McpToolExecutor.ToolResult.of("Stub " + stubId + " is persistent.", updated);
        }));
    }

    @Tool(name = "unpersist_stub", description = "Idempotently make a persistent stub temporary without deleting body files.", annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false))
    public ToolResponse unpersistStub(@ToolArg(description = "Mock ID") String mockId,
            @ToolArg(description = "WireMock stub UUID") String stubId) {
        return wireMock("unpersist_stub", mockId, () -> coordinator.serialized(mockId, () -> {
            requireStorage();
            return McpToolExecutor.ToolResult.of("Stub " + stubId + " is temporary.",
                    wireMock.setPersistent(mockId, stubId, false));
        }));
    }

    @Tool(name = "send_request", description = "Send ordinary traffic through Fleet Proxy. Admin paths are rejected and the request may start the pod.", annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = true, idempotentHint = false, openWorldHint = true))
    public ToolResponse sendRequest(
            @ToolArg(description = "Mock ID") String mockId,
            @ToolArg(description = "HTTP method") String method,
            @ToolArg(description = "Relative path and optional query") String path,
            @ToolArg(description = "Request headers", required = false) Map<String, Object> headers,
            @ToolArg(description = "UTF-8 request body", required = false) String body,
            @ToolArg(description = "Base64 request body for binary content", required = false) String bodyBase64) {
        return wireMock("send_request", mockId, () -> {
            byte[] requestBody = decodeBody(body, bodyBase64);
            TransportResponse response = wireMock.sendRequest(mockId, parseMethod(method), path, headerMap(headers), requestBody);
            return McpToolExecutor.ToolResult.of("Received HTTP " + response.status() + " from " + mockId + ".",
                    trafficResult(response));
        });
    }

    @Tool(name = "find_requests", description = "Find journaled requests matching a native WireMock request pattern. Sensitive headers are redacted.", annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public ToolResponse findRequests(@ToolArg(description = "Mock ID") String mockId,
            @ToolArg(description = "Native WireMock request-pattern JSON") Map<String, Object> requestPattern,
            @ToolArg(description = "Page size", required = false) Integer limit,
            @ToolArg(description = "Zero-based offset", required = false) Integer offset) {
        return wireMock("find_requests", mockId, () -> McpToolExecutor.ToolResult.of(
                "Found matching requests for " + mockId + ".",
                page(wireMock.findRequests(mockId, json(requestPattern)), "requests", limit, offset)));
    }

    @Tool(name = "count_requests", description = "Count journaled requests matching a native WireMock request pattern.", annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public ToolResponse countRequests(@ToolArg(description = "Mock ID") String mockId,
            @ToolArg(description = "Native WireMock request-pattern JSON") Map<String, Object> requestPattern) {
        return wireMock("count_requests", mockId, () -> McpToolExecutor.ToolResult.of(
                "Counted matching requests for " + mockId + ".", wireMock.countRequests(mockId, json(requestPattern))));
    }

    @Tool(name = "list_unmatched_requests", description = "List unmatched journal requests with sensitive headers redacted.", annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public ToolResponse listUnmatchedRequests(@ToolArg(description = "Mock ID") String mockId,
            @ToolArg(description = "Page size", required = false) Integer limit,
            @ToolArg(description = "Zero-based offset", required = false) Integer offset) {
        return wireMock("list_unmatched_requests", mockId, () -> McpToolExecutor.ToolResult.of(
                "Listed unmatched requests for " + mockId + ".",
                page(wireMock.listUnmatchedRequests(mockId), "requests", limit, offset)));
    }

    @Tool(name = "get_near_misses", description = "Get near misses for unmatched requests or an optional request pattern.", annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public ToolResponse getNearMisses(@ToolArg(description = "Mock ID") String mockId,
            @ToolArg(description = "Optional native WireMock request-pattern JSON", required = false) Map<String, Object> requestPattern) {
        return wireMock("get_near_misses", mockId, () -> McpToolExecutor.ToolResult.of(
                "Loaded near misses for " + mockId + ".",
                wireMock.getNearMisses(mockId, requestPattern == null ? null : json(requestPattern))));
    }

    @Tool(name = "reset_request_journal", description = "Delete all entries from the WireMock request journal.", annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false))
    public ToolResponse resetRequestJournal(@ToolArg(description = "Mock ID") String mockId) {
        return wireMock("reset_request_journal", mockId, () -> {
            wireMock.resetRequestJournal(mockId);
            return McpToolExecutor.ToolResult.of("Reset request journal for " + mockId + ".",
                    Map.of("mockId", mockId, "reset", true));
        });
    }

    @Tool(name = "start_recording", description = "Start WireMock recording with persist=false and outputFormat=IDS. The target is checked against the outbound policy.", annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = true, idempotentHint = false, openWorldHint = true))
    public ToolResponse startRecording(@ToolArg(description = "Mock ID") String mockId,
            @ToolArg(description = "Native WireMock recorder JSON") Map<String, Object> recording) {
        return wireMock("start_recording", mockId, () -> coordinator.serialized(mockId, () -> {
            ObjectNode payload = object(recording, "recording");
            outboundTargets.validate(payload);
            return McpToolExecutor.ToolResult.of("Started recording for " + mockId + ".",
                    wireMock.startRecording(mockId, payload));
        }));
    }

    @Tool(name = "recording_status", description = "Get WireMock recorder status. This may start an inactive mock pod.", annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public ToolResponse recordingStatus(@ToolArg(description = "Mock ID") String mockId) {
        return wireMock("recording_status", mockId, () -> McpToolExecutor.ToolResult.of(
                "Loaded recorder status for " + mockId + ".", wireMock.recordingStatus(mockId)));
    }

    @Tool(name = "stop_recording", description = "Stop recording and return sanitized temporary candidate IDs for review.", annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = true, idempotentHint = false, openWorldHint = true))
    public ToolResponse stopRecording(@ToolArg(description = "Mock ID") String mockId) {
        return wireMock("stop_recording", mockId, () -> coordinator.serialized(mockId, () -> McpToolExecutor.ToolResult.of(
                "Stopped recording for " + mockId + ". Review candidates with get_stub.", wireMock.stopRecording(mockId))));
    }

    @Tool(name = "snapshot_requests", description = "Create sanitized temporary recorder candidates with persist=false and outputFormat=IDS.", annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = true, idempotentHint = false, openWorldHint = true))
    public ToolResponse snapshotRequests(@ToolArg(description = "Mock ID") String mockId,
            @ToolArg(description = "Native WireMock snapshot JSON") Map<String, Object> snapshot) {
        return wireMock("snapshot_requests", mockId, () -> coordinator.serialized(mockId, () -> {
            ObjectNode payload = object(snapshot, "snapshot");
            outboundTargets.validate(payload);
            return McpToolExecutor.ToolResult.of("Created temporary recorder candidates for " + mockId + ".",
                    wireMock.snapshotRequests(mockId, payload));
        }));
    }

    @Tool(name = "list_body_files", description = "List WireMock body-file names. This may start an inactive mock pod.", annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public ToolResponse listBodyFiles(@ToolArg(description = "Mock ID") String mockId,
            @ToolArg(description = "Page size", required = false) Integer limit,
            @ToolArg(description = "Zero-based offset", required = false) Integer offset) {
        return wireMock("list_body_files", mockId, () -> McpToolExecutor.ToolResult.of(
                "Listed body files for " + mockId + ".",
                page(wireMock.listBodyFiles(mockId), "files", limit, offset)));
    }

    @Tool(name = "get_body_file", description = "Get a WireMock body file as text or base64 within the configured body limit.", annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public ToolResponse getBodyFile(@ToolArg(description = "Mock ID") String mockId,
            @ToolArg(description = "Relative body-file name") String fileName) {
        return wireMock("get_body_file", mockId, () -> {
            TransportResponse response = wireMock.getBodyFile(mockId, fileName);
            if (response.body().length > config.includedBodyBytes()) {
                throw tooLarge(response.body().length, config.includedBodyBytes());
            }
            ObjectNode result = binaryResult(response.body(), firstHeader(response.headers(), "content-type"));
            result.put("fileName", fileName);
            return McpToolExecutor.ToolResult.of("Loaded body file " + fileName + ".", result);
        });
    }

    @Tool(name = "put_body_file", description = "Create or replace a WireMock body file from base64 content.", annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false))
    public ToolResponse putBodyFile(@ToolArg(description = "Mock ID") String mockId,
            @ToolArg(description = "Relative body-file name") String fileName,
            @ToolArg(description = "Base64 file content") String contentBase64,
            @ToolArg(description = "Media type", required = false, defaultValue = "application/octet-stream") String contentType) {
        return wireMock("put_body_file", mockId, () -> coordinator.serialized(mockId, () -> {
            byte[] content = decodeBase64(contentBase64, "contentBase64");
            wireMock.putBodyFile(mockId, fileName, content, contentType);
            return McpToolExecutor.ToolResult.of("Stored body file " + fileName + ".",
                    Map.of("mockId", mockId, "fileName", fileName, "size", content.length));
        }));
    }

    @Tool(name = "delete_body_file", description = "Delete a body file. Referencing stubs are reported and require force=true; stubs are never deleted.", annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false))
    public ToolResponse deleteBodyFile(@ToolArg(description = "Mock ID") String mockId,
            @ToolArg(description = "Relative body-file name") String fileName,
            @ToolArg(description = "Delete even when stubs reference the file", required = false, defaultValue = "false") boolean force) {
        return wireMock("delete_body_file", mockId, () -> coordinator.serialized(mockId, () -> {
            BodyFileName.requireValid(fileName);
            List<String> references = referencingStubs(mockId, fileName);
            if (!references.isEmpty() && !force) {
                throw new McpOperationException("BODY_FILE_REFERENCED",
                        "Body file is referenced by stubs; set force=true to delete it without cascading", false,
                        Map.of("fileName", fileName, "stubIds", references));
            }
            wireMock.deleteBodyFile(mockId, fileName);
            return McpToolExecutor.ToolResult.of("Deleted body file " + fileName + ".",
                    Map.of("mockId", mockId, "fileName", fileName, "deleted", true, "referencingStubIds", references));
        }));
    }

    @Tool(name = "list_scenarios", description = "List WireMock scenario state. This may start an inactive mock pod.", annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public ToolResponse listScenarios(@ToolArg(description = "Mock ID") String mockId,
            @ToolArg(description = "Page size", required = false) Integer limit,
            @ToolArg(description = "Zero-based offset", required = false) Integer offset) {
        return wireMock("list_scenarios", mockId, () -> McpToolExecutor.ToolResult.of(
                "Listed scenarios for " + mockId + ".",
                page(wireMock.listScenarios(mockId), "scenarios", limit, offset)));
    }

    @Tool(name = "reset_scenarios", description = "Reset all WireMock scenarios to their initial state.", annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false))
    public ToolResponse resetScenarios(@ToolArg(description = "Mock ID") String mockId) {
        return wireMock("reset_scenarios", mockId, () -> {
            wireMock.resetScenarios(mockId);
            return McpToolExecutor.ToolResult.of("Reset scenarios for " + mockId + ".",
                    Map.of("mockId", mockId, "reset", true));
        });
    }

    private ToolResponse fleet(String toolName, Supplier<McpToolExecutor.ToolResult> action) {
        return executor.execute(toolName, action);
    }

    private ToolResponse wireMock(String toolName, String mockId, Supplier<McpToolExecutor.ToolResult> action) {
        return executor.execute(toolName, () -> {
            MockIdValidator.requireValid(mockId);
            WireMockVersion runtimeVersion = wireMock.version(mockId);
            if (!ToolCapabilityRegistry.supports(toolName, runtimeVersion)) {
                throw new McpOperationException("WIREMOCK_VERSION_UNSUPPORTED",
                        toolName + " requires WireMock " + ToolCapabilityRegistry.minimumVersion(toolName) + "+", false,
                        Map.of("actualVersion", runtimeVersion.toString(),
                                "minimumVersion", ToolCapabilityRegistry.minimumVersion(toolName).toString()));
            }
            return action.get();
        });
    }

    private void requireStorage() {
        if (!config.storageEnabled() || !fleetApi.storageEnabled()) {
            throw new McpOperationException("STORAGE_DISABLED", "Persistent mappings storage is not enabled", false, Map.of());
        }
    }

    private void verifyBodyFiles(String mockId, JsonNode stub) {
        List<String> referenced = new ArrayList<>();
        collectTextFields(stub, "bodyFileName", referenced);
        if (referenced.isEmpty()) {
            return;
        }
        JsonNode files = wireMock.listBodyFiles(mockId);
        Set<String> existing = mapper.convertValue(files, mapper.getTypeFactory().constructCollectionType(Set.class, String.class));
        List<String> missing = referenced.stream().filter(file -> !existing.contains(file)).distinct().toList();
        if (!missing.isEmpty()) {
            throw new McpOperationException("BODY_FILE_MISSING", "Stub references missing body files", false,
                    Map.of("missingFiles", missing));
        }
    }

    private List<String> referencingStubs(String mockId, String fileName) {
        List<String> references = new ArrayList<>();
        int offset = 0;
        while (true) {
            JsonNode page = wireMock.listStubs(mockId, config.maxPageSize(), offset);
            JsonNode mappings = page.path("mappings");
            if (!mappings.isArray()) {
                break;
            }
            for (JsonNode mapping : mappings) {
                List<String> files = new ArrayList<>();
                collectTextFields(mapping, "bodyFileName", files);
                if (files.contains(fileName)) {
                    references.add(mapping.path("id").asText(mapping.path("uuid").asText("unknown")));
                }
            }
            offset += mappings.size();
            int total = page.path("meta").path("total").asInt(offset);
            if (mappings.isEmpty() || offset >= total) {
                break;
            }
        }
        return List.copyOf(references);
    }

    private ObjectNode trafficResult(TransportResponse response) {
        if (response.body().length > config.includedBodyBytes()) {
            throw tooLarge(response.body().length, config.includedBodyBytes());
        }
        ObjectNode result = binaryResult(response.body(), firstHeader(response.headers(), "content-type"));
        result.put("status", response.status());
        result.set("headers", mapper.valueToTree(redactHeaders(response.headers())));
        return result;
    }

    private ObjectNode binaryResult(byte[] body, String contentType) {
        ObjectNode result = mapper.createObjectNode();
        result.put("size", body.length);
        result.put("contentType", contentType == null ? "application/octet-stream" : contentType);
        if (isText(contentType)) {
            result.put("body", new String(body, StandardCharsets.UTF_8));
        } else {
            result.put("bodyBase64", Base64.getEncoder().encodeToString(body));
        }
        return result;
    }

    private Map<String, List<String>> redactHeaders(Map<String, List<String>> headers) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        int redacted = 0;
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            boolean sensitive = sensitiveHeaders.contains(entry.getKey().toLowerCase(Locale.ROOT));
            result.put(entry.getKey(), sensitive ? List.of("[REDACTED]") : entry.getValue());
            if (sensitive) {
                redacted++;
            }
        }
        metrics.headersRedacted(redacted);
        return result;
    }

    private Map<String, List<String>> headerMap(Map<String, Object> headers) {
        if (headers == null) {
            return Map.of();
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        headers.forEach((name, value) -> {
            if (value instanceof List<?> values) {
                result.put(name, values.stream().map(String::valueOf).toList());
            } else {
                result.put(name, List.of(String.valueOf(value)));
            }
        });
        return Map.copyOf(result);
    }

    private byte[] decodeBody(String body, String bodyBase64) {
        if (body != null && bodyBase64 != null) {
            throw new IllegalArgumentException("Specify either body or bodyBase64, not both");
        }
        byte[] decoded = bodyBase64 != null ? decodeBase64(bodyBase64, "bodyBase64")
                : body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        if (decoded.length > config.maxPayloadBytes()) {
            throw tooLarge(decoded.length, config.maxPayloadBytes());
        }
        return decoded;
    }

    private byte[] decodeBase64(String value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " is required");
        }
        try {
            return Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(name + " is not valid base64");
        }
    }

    private ObjectNode page(JsonNode source, String field, Integer limit, Integer offset) {
        return JsonPaginator.page(mapper, source, field, pageSize(limit), pageOffset(offset));
    }

    private int pageSize(Integer value) {
        int resolved = value == null ? config.defaultPageSize() : value;
        if (resolved < 1 || resolved > config.maxPageSize()) {
            throw new IllegalArgumentException("limit must be between 1 and " + config.maxPageSize());
        }
        return resolved;
    }

    private int pageOffset(Integer value) {
        int resolved = value == null ? 0 : value;
        if (resolved < 0) {
            throw new IllegalArgumentException("offset cannot be negative");
        }
        return resolved;
    }

    private HttpMethod parseMethod(String value) {
        try {
            return HttpMethod.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            throw new IllegalArgumentException("Unsupported HTTP method: " + value);
        }
    }

    private ObjectNode object(Map<String, Object> value, String name) {
        JsonNode node = json(value);
        if (!(node instanceof ObjectNode object)) {
            throw new IllegalArgumentException(name + " must be a JSON object");
        }
        return object;
    }

    private JsonNode json(Map<String, Object> value) {
        if (value == null) {
            throw new IllegalArgumentException("JSON argument is required");
        }
        return mapper.valueToTree(value);
    }

    private JsonNode requireMockConfig(JsonNode mocks, String mockId, String missingCode) {
        JsonNode mock = findMockConfig(mocks, mockId);
        if (mock != null) {
            return mock;
        }
        if ("NOT_FOUND".equals(missingCode)) {
            throw new McpOperationException("NOT_FOUND", "No configuration exists for mock " + mockId + ".", false,
                    Map.of("mockId", mockId));
        }
        throw invalidUpstreamResponse("Updated mock is missing", mockId);
    }

    private McpOperationException invalidUpstreamResponse(String message, String mockId) {
        return new McpOperationException("INVALID_UPSTREAM_RESPONSE", message, false, Map.of("mockId", mockId));
    }

    private JsonNode findMockConfig(JsonNode mocks, String mockId) {
        if (mocks.isArray()) {
            for (JsonNode mock : mocks) {
                if (mockId.equals(mock.path("mockId").asText())) {
                    return mock;
                }
            }
        }
        return null;
    }

    private static void collectTextFields(JsonNode node, String fieldName, List<String> values) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            node.properties().forEach(entry -> {
                if (fieldName.equals(entry.getKey()) && entry.getValue().isTextual()) {
                    values.add(entry.getValue().textValue());
                }
                collectTextFields(entry.getValue(), fieldName, values);
            });
        } else if (node.isArray()) {
            node.forEach(child -> collectTextFields(child, fieldName, values));
        }
    }

    private static String firstHeader(Map<String, List<String>> headers, String name) {
        List<String> values = headers.get(name);
        return values == null || values.isEmpty() ? null : values.getFirst();
    }

    private static boolean isText(String contentType) {
        if (contentType == null) {
            return false;
        }
        String normalized = contentType.toLowerCase(Locale.ROOT);
        return normalized.startsWith("text/") || normalized.contains("json") || normalized.contains("xml")
                || normalized.contains("yaml") || normalized.contains("javascript") || normalized.contains("form-urlencoded");
    }

    private static McpOperationException tooLarge(int actual, int limit) {
        return new McpOperationException("RESULT_TOO_LARGE", "Body exceeds the configured inclusion limit", false,
                Map.of("actualBytes", actual, "limitBytes", limit));
    }
}
