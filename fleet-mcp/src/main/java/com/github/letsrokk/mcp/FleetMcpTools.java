package com.github.letsrokk.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.ToolGuardrails;
import io.quarkiverse.mcp.server.ToolResponse;
import io.vertx.core.http.HttpMethod;
import jakarta.inject.Singleton;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
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
    private final CursorCodec cursors;

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
        this.cursors = new CursorCodec(mapper);
        this.sensitiveHeaders = config.sensitiveHeaders().stream()
                .map(value -> value.toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    @ToolGuardrails(input = StrictToolInputGuardrail.class, output = StructuredToolErrorGuardrail.class)
    @Tool(name = "list_mocks", description = "List active Mock Fleet mocks without starting any mock pod.", outputSchema = @Tool.OutputSchema(from = OutputSchemas.ListMocks.class, generator = ToolOutputSchemaGenerator.class), annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public ToolResponse listMocks(
            @ToolArg(description = "Page size", required = false) Integer limit,
            @ToolArg(description = "Opaque continuation cursor", required = false) String cursor) {
        JsonNode scope = scope(null, null);
        return fleet("list_mocks", () -> McpToolExecutor.ToolResult.of("Listed active mocks.",
                collectionResult(null, page("list_mocks", scope, fleetApi.listMocks(), "mocks", limit, cursor),
                        "mocks", "mocks", "list_mocks", scope)));
    }

    @ToolGuardrails(input = StrictToolInputGuardrail.class, output = StructuredToolErrorGuardrail.class)
    @Tool(name = "list_mock_configs", description = "List mock IDs with user-saved configuration overrides.", outputSchema = @Tool.OutputSchema(from = OutputSchemas.ListMockConfigs.class, generator = ToolOutputSchemaGenerator.class), annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public ToolResponse listMockConfigs(
            @ToolArg(description = "Page size", required = false) Integer limit,
            @ToolArg(description = "Opaque continuation cursor", required = false) String cursor) {
        JsonNode scope = scope(null, null);
        return fleet("list_mock_configs", () -> {
            JsonNode view = fleetApi.getConfig();
            ObjectNode result = mapper.createObjectNode();
            result.set("resourceVersion", view.path("resourceVersion"));
            result.set("mockIds", view.path("savedMockIds"));
            return McpToolExecutor.ToolResult.of("Listed saved mock configurations.",
                    collectionResult(null, page("list_mock_configs", scope, result, "mockIds", limit, cursor),
                            "mockIds", "mockIds", "list_mock_configs", scope));
        });
    }

    @ToolGuardrails(input = StrictToolInputGuardrail.class, output = StructuredToolErrorGuardrail.class)
    @Tool(name = "get_mock_config", description = "Get one mock's configuration and Fleet routing metadata.", outputSchema = @Tool.OutputSchema(from = OutputSchemas.GetMockConfig.class, generator = ToolOutputSchemaGenerator.class), annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
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

    @ToolGuardrails(input = StrictToolInputGuardrail.class, output = StructuredToolErrorGuardrail.class)
    @Tool(name = "list_option_definitions", description = "List supported WireMock CLI option definitions.", outputSchema = @Tool.OutputSchema(from = OutputSchemas.ListOptionDefinitions.class, generator = ToolOutputSchemaGenerator.class), annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public ToolResponse listOptionDefinitions() {
        return fleet("list_option_definitions", () -> {
            JsonNode view = fleetApi.getConfig();
            ObjectNode result = mapper.createObjectNode();
            result.set("optionDefinitions", view.path("options"));
            return McpToolExecutor.ToolResult.of("Listed WireMock option definitions.", result);
        });
    }

    @ToolGuardrails(input = StrictToolInputGuardrail.class, output = StructuredToolErrorGuardrail.class)
    @Tool(name = "update_mock_config", description = "Create or replace a mock's complete saved options and resources using optimistic concurrency.",
            inputSchema = @Tool.InputSchema(generator = UpdateMockConfigInputSchemaGenerator.class),
            outputSchema = @Tool.OutputSchema(from = OutputSchemas.UpdateMockConfig.class, generator = ToolOutputSchemaGenerator.class),
            annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false))
    public ToolResponse updateMockConfig(
            @ToolArg(description = "Mock ID") String mockId,
            @ToolArg(description = "Current Fleet ConfigMap resourceVersion") String resourceVersion,
            @ToolArg(description = "Complete mock-specific WireMock CLI option override list") List<String> options,
            @ToolArg(description = "Kubernetes requests and limits override; omit to inherit baseline resources", required = false) MockResources resources,
            @ToolArg(description = "How to apply the saved configuration") String applyMode) {
        return fleet("update_mock_config", () -> {
            if (resources != null && resources.requests() == null) {
                throw new IllegalArgumentException("resources.requests is required when resources are provided");
            }
            if (resources != null && resources.limits() == null) {
                throw new IllegalArgumentException("resources.limits is required when resources are provided");
            }
            ConfigApplyMode parsedApplyMode = parseApplyMode(applyMode);
            JsonNode view = fleetApi.updateConfig(mockId, resourceVersion, options, resources, parsedApplyMode);
            JsonNode configView = requireMutationEnvelope(view, mockId);
            ObjectNode result = mapper.createObjectNode();
            result.set("resourceVersion", configView.path("resourceVersion"));
            JsonNode updatedMock = findMockConfig(configView.path("mocks"), mockId);
            if (updatedMock == null) {
                throw invalidMutationResponse("Updated mock is missing", mockId);
            }
            result.set("mock", updatedMock);
            result.set("apply", view.path("apply"));
            return McpToolExecutor.ToolResult.of("Updated configuration for " + mockId + ".", result);
        });
    }

    @ToolGuardrails(input = StrictToolInputGuardrail.class, output = StructuredToolErrorGuardrail.class)
    @Tool(name = "delete_mock_config", description = "Delete a mock-specific configuration using optimistic concurrency.",
            outputSchema = @Tool.OutputSchema(from = OutputSchemas.DeleteMockConfig.class, generator = ToolOutputSchemaGenerator.class),
            annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false))
    public ToolResponse deleteMockConfig(
            @ToolArg(description = "Mock ID") String mockId,
            @ToolArg(description = "Current Fleet ConfigMap resourceVersion") String resourceVersion,
            @ToolArg(description = "How to apply the deletion") String applyMode) {
        return fleet("delete_mock_config", () -> {
            JsonNode view = fleetApi.deleteConfig(mockId, resourceVersion, parseApplyMode(applyMode));
            JsonNode configView = requireMutationEnvelope(view, mockId);
            JsonNode savedMockIds = configView.get("savedMockIds");
            if (savedMockIds == null || !savedMockIds.isArray()) {
                throw invalidMutationResponse("Fleet API response does not contain a savedMockIds array", mockId);
            }
            for (JsonNode savedMockId : savedMockIds) {
                if (mockId.equals(savedMockId.asText())) {
                    throw invalidMutationResponse("Deleted mock configuration is still saved", mockId);
                }
            }
            ObjectNode result = mapper.createObjectNode();
            result.set("resourceVersion", configView.path("resourceVersion"));
            result.put("mockId", mockId);
            result.put("deleted", true);
            result.set("apply", view.path("apply"));
            return McpToolExecutor.ToolResult.of("Deleted configuration for " + mockId + ".", result);
        });
    }

    @ToolGuardrails(input = StrictToolInputGuardrail.class, output = StructuredToolErrorGuardrail.class)
    @Tool(name = "start_mock", description = "Start a mock pod or report that startup is still in progress.",
            outputSchema = @Tool.OutputSchema(from = OutputSchemas.StartMock.class, generator = ToolOutputSchemaGenerator.class),
            annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public ToolResponse startMock(@ToolArg(description = "Mock ID") String mockId) {
        return fleet("start_mock", () -> {
            ObjectNode lifecycle = requireLifecycleResponse(mockId);
            String summary = "RUNNING".equals(lifecycle.path("status").asText())
                    ? "Mock " + mockId + " is running."
                    : "Mock " + mockId + " is starting.";
            return McpToolExecutor.ToolResult.of(summary, lifecycle);
        });
    }

    @ToolGuardrails(input = StrictToolInputGuardrail.class, output = StructuredToolErrorGuardrail.class)
    @Tool(name = "stop_mock", description = "Stop an active mock pod. The next proxied request may start it again.",
            outputSchema = @Tool.OutputSchema(from = OutputSchemas.StopMock.class, generator = ToolOutputSchemaGenerator.class),
            annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false))
    public ToolResponse stopMock(@ToolArg(description = "Mock ID") String mockId) {
        return fleet("stop_mock", () -> {
            ObjectNode lifecycle = requireStopLifecycleResponse(mockId);
            return McpToolExecutor.ToolResult.of("Stopped mock " + mockId + ".", lifecycle);
        });
    }

    @ToolGuardrails(input = StrictToolInputGuardrail.class, output = StructuredToolErrorGuardrail.class)
    @Tool(name = "list_stubs", description = "List WireMock stubs. This may start an inactive mock pod.", outputSchema = @Tool.OutputSchema(from = OutputSchemas.StubPage.class, generator = ToolOutputSchemaGenerator.class), annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public ToolResponse listStubs(
            @ToolArg(description = "Mock ID") String mockId,
            @ToolArg(description = "Page size", required = false) Integer limit,
            @ToolArg(description = "Opaque continuation cursor", required = false) String cursor) {
        JsonNode scope = scope(mockId, null);
        return wireMock("list_stubs", mockId, () -> McpToolExecutor.ToolResult.of("Listed stubs for " + mockId + ".",
                collectionResult(mockId, wireMock.listStubs(mockId, pageSize(limit),
                        mappingCursorPosition("list_stubs", scope, cursor)),
                        "mappings", "stubs", "list_stubs", scope)));
    }

    @ToolGuardrails(input = StrictToolInputGuardrail.class, output = StructuredToolErrorGuardrail.class)
    @Tool(name = "list_unmatched_stubs", description = "List stubs that have not matched journaled requests. Requires WireMock 3.13+ and may start the pod.", outputSchema = @Tool.OutputSchema(from = OutputSchemas.StubPage.class, generator = ToolOutputSchemaGenerator.class), annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public ToolResponse listUnmatchedStubs(@ToolArg(description = "Mock ID") String mockId,
            @ToolArg(description = "Page size", required = false) Integer limit,
            @ToolArg(description = "Opaque continuation cursor", required = false) String cursor) {
        JsonNode scope = scope(mockId, null);
        return wireMock("list_unmatched_stubs", mockId, () -> McpToolExecutor.ToolResult.of(
                "Listed unmatched stubs for " + mockId + ".",
                collectionResult(mockId, boundedCollectionPage("list_unmatched_stubs", scope, cursor,
                        () -> wireMock.listUnmatchedStubsPage(mockId, pageSize(limit),
                                cursorPosition("list_unmatched_stubs", scope, cursor), config.maxCollectionScanBytes(),
                                config.maxCollectionScanItems())),
                        "mappings", "stubs", "list_unmatched_stubs", scope)));
    }

    @ToolGuardrails(input = StrictToolInputGuardrail.class, output = StructuredToolErrorGuardrail.class)
    @Tool(name = "get_stub", description = "Get a WireMock stub by ID. This may start an inactive mock pod.", outputSchema = @Tool.OutputSchema(from = OutputSchemas.Stub.class, generator = ToolOutputSchemaGenerator.class), annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public ToolResponse getStub(@ToolArg(description = "Mock ID") String mockId,
            @ToolArg(description = "WireMock stub UUID") String stubId) {
        return wireMock("get_stub", mockId, () -> McpToolExecutor.ToolResult.of(
                "Loaded stub " + stubId + ".", wrapped(mockId, "stub", wireMock.getStub(mockId, stubId))));
    }

    @ToolGuardrails(input = StrictToolInputGuardrail.class, output = StructuredToolErrorGuardrail.class)
    @Tool(name = "create_stub", description = "Create a temporary WireMock stub. Client-supplied id, uuid, and persistent fields are ignored.", inputSchema = @Tool.InputSchema(generator = RichJsonInputSchemaGenerator.class), outputSchema = @Tool.OutputSchema(from = OutputSchemas.Stub.class, generator = ToolOutputSchemaGenerator.class), annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = true, idempotentHint = false, openWorldHint = false))
    public ToolResponse createStub(@ToolArg(description = "Mock ID") String mockId,
            @ToolArg(description = "Native WireMock stub mapping JSON") Map<String, Object> mapping) {
        return wireMock("create_stub", mockId, () -> coordinator.serialized(mockId, () -> {
            ObjectNode payload = object(mapping, "mapping");
            outboundTargets.validate(payload);
            return McpToolExecutor.ToolResult.of("Created a temporary stub for " + mockId + ".",
                    wrapped(mockId, "stub", wireMock.createStub(mockId, payload)));
        }));
    }

    @ToolGuardrails(input = StrictToolInputGuardrail.class, output = StructuredToolErrorGuardrail.class)
    @Tool(name = "update_stub", description = "Update a WireMock stub while preserving its current persistence state.", inputSchema = @Tool.InputSchema(generator = RichJsonInputSchemaGenerator.class), outputSchema = @Tool.OutputSchema(from = OutputSchemas.Stub.class, generator = ToolOutputSchemaGenerator.class), annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = true, idempotentHint = false, openWorldHint = false))
    public ToolResponse updateStub(@ToolArg(description = "Mock ID") String mockId,
            @ToolArg(description = "WireMock stub UUID") String stubId,
            @ToolArg(description = "Native WireMock stub mapping JSON") Map<String, Object> mapping) {
        return wireMock("update_stub", mockId, () -> coordinator.serialized(mockId, () -> {
            ObjectNode payload = object(mapping, "mapping");
            outboundTargets.validate(payload);
            return McpToolExecutor.ToolResult.of("Updated stub " + stubId + ".",
                    wrapped(mockId, "stub", wireMock.updateStub(mockId, stubId, payload)));
        }));
    }

    @ToolGuardrails(input = StrictToolInputGuardrail.class, output = StructuredToolErrorGuardrail.class)
    @Tool(name = "delete_stub", description = "Delete a WireMock stub without deleting referenced body files.", outputSchema = @Tool.OutputSchema(from = OutputSchemas.DeleteStub.class, generator = ToolOutputSchemaGenerator.class), annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = true, idempotentHint = false, openWorldHint = false))
    public ToolResponse deleteStub(@ToolArg(description = "Mock ID") String mockId,
            @ToolArg(description = "WireMock stub UUID") String stubId) {
        return wireMock("delete_stub", mockId, () -> coordinator.serialized(mockId, () -> {
            wireMock.deleteStub(mockId, stubId);
            return McpToolExecutor.ToolResult.of("Deleted stub " + stubId + ".",
                    Map.of("mockId", mockId, "stubId", stubId, "deleted", true));
        }));
    }

    @ToolGuardrails(input = StrictToolInputGuardrail.class, output = StructuredToolErrorGuardrail.class)
    @Tool(name = "persist_stub", description = "Idempotently persist a temporary stub after verifying referenced body files.", outputSchema = @Tool.OutputSchema(from = OutputSchemas.Stub.class, generator = ToolOutputSchemaGenerator.class), annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false))
    public ToolResponse persistStub(@ToolArg(description = "Mock ID") String mockId,
            @ToolArg(description = "WireMock stub UUID") String stubId) {
        return wireMock("persist_stub", mockId, () -> coordinator.serialized(mockId, () -> {
            requireStorage();
            JsonNode stub = wireMock.getStub(mockId, stubId);
            verifyBodyFiles(mockId, stub);
            JsonNode updated = wireMock.setPersistent(mockId, stubId, true);
            return McpToolExecutor.ToolResult.of("Stub " + stubId + " is persistent.", wrapped(mockId, "stub", updated));
        }));
    }

    @ToolGuardrails(input = StrictToolInputGuardrail.class, output = StructuredToolErrorGuardrail.class)
    @Tool(name = "unpersist_stub", description = "Idempotently make a persistent stub temporary without deleting body files.", outputSchema = @Tool.OutputSchema(from = OutputSchemas.Stub.class, generator = ToolOutputSchemaGenerator.class), annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false))
    public ToolResponse unpersistStub(@ToolArg(description = "Mock ID") String mockId,
            @ToolArg(description = "WireMock stub UUID") String stubId) {
        return wireMock("unpersist_stub", mockId, () -> coordinator.serialized(mockId, () -> {
            requireStorage();
            return McpToolExecutor.ToolResult.of("Stub " + stubId + " is temporary.",
                    wrapped(mockId, "stub", wireMock.setPersistent(mockId, stubId, false)));
        }));
    }

    @ToolGuardrails(input = StrictToolInputGuardrail.class, output = StructuredToolErrorGuardrail.class)
    @Tool(name = "send_request", description = "Send ordinary traffic through Fleet Proxy. Admin paths are rejected and the request may start the pod.", inputSchema = @Tool.InputSchema(generator = BodyInputSchemaGenerator.class), outputSchema = @Tool.OutputSchema(from = OutputSchemas.SendRequest.class, generator = ToolOutputSchemaGenerator.class), annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = true, idempotentHint = false, openWorldHint = true))
    public ToolResponse sendRequest(
            @ToolArg(description = "Mock ID") String mockId,
            @ToolArg(description = "HTTP method") String method,
            @ToolArg(description = "Relative path and optional query") String path,
            @ToolArg(description = "Request headers", required = false) Map<String, Object> headers,
            @ToolArg(description = "Encoded request body", required = false) Map<String, Object> body) {
        return wireMock("send_request", mockId, () -> {
            byte[] requestBody = decodeBody(body, false);
            TransportResponse response = wireMock.sendRequest(mockId, parseMethod(method), path, headerMap(headers), requestBody);
            ObjectNode result = mapper.createObjectNode();
            result.put("mockId", mockId);
            result.set("response", trafficResult(response));
            return McpToolExecutor.ToolResult.of("Received HTTP " + response.status() + " from " + mockId + ".", result);
        });
    }

    @ToolGuardrails(input = StrictToolInputGuardrail.class, output = StructuredToolErrorGuardrail.class)
    @Tool(name = "find_requests", description = "Find journaled requests matching a native WireMock request pattern. Sensitive headers are redacted.", inputSchema = @Tool.InputSchema(generator = RichJsonInputSchemaGenerator.class), outputSchema = @Tool.OutputSchema(from = OutputSchemas.RequestPage.class, generator = ToolOutputSchemaGenerator.class), annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public ToolResponse findRequests(@ToolArg(description = "Mock ID") String mockId,
            @ToolArg(description = "Native WireMock request-pattern JSON") Map<String, Object> requestPattern,
            @ToolArg(description = "Page size", required = false) Integer limit,
            @ToolArg(description = "Opaque continuation cursor", required = false) String cursor) {
        JsonNode pattern = json(requestPattern);
        JsonNode scope = scope(mockId, pattern);
        return wireMock("find_requests", mockId, () -> McpToolExecutor.ToolResult.of(
                "Found matching requests for " + mockId + ".",
                collectionResult(mockId, boundedCollectionPage("find_requests", scope, cursor,
                        () -> wireMock.findRequestsPage(mockId, pattern, pageSize(limit),
                                cursorPosition("find_requests", scope, cursor), config.maxCollectionScanBytes(),
                                config.maxCollectionScanItems())), "requests", "requests", "find_requests", scope)));
    }

    @ToolGuardrails(input = StrictToolInputGuardrail.class, output = StructuredToolErrorGuardrail.class)
    @Tool(name = "count_requests", description = "Count journaled requests matching a native WireMock request pattern.", inputSchema = @Tool.InputSchema(generator = RichJsonInputSchemaGenerator.class), outputSchema = @Tool.OutputSchema(from = OutputSchemas.CountRequests.class, generator = ToolOutputSchemaGenerator.class), annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public ToolResponse countRequests(@ToolArg(description = "Mock ID") String mockId,
            @ToolArg(description = "Native WireMock request-pattern JSON") Map<String, Object> requestPattern) {
        return wireMock("count_requests", mockId, () -> {
            JsonNode response = wireMock.countRequests(mockId, json(requestPattern));
            ObjectNode result = mapper.createObjectNode();
            result.put("mockId", mockId);
            result.set("count", response.path("count"));
            return McpToolExecutor.ToolResult.of("Counted matching requests for " + mockId + ".", result);
        });
    }

    @ToolGuardrails(input = StrictToolInputGuardrail.class, output = StructuredToolErrorGuardrail.class)
    @Tool(name = "list_unmatched_requests", description = "List unmatched journal requests with sensitive headers redacted.", outputSchema = @Tool.OutputSchema(from = OutputSchemas.RequestPage.class, generator = ToolOutputSchemaGenerator.class), annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public ToolResponse listUnmatchedRequests(@ToolArg(description = "Mock ID") String mockId,
            @ToolArg(description = "Page size", required = false) Integer limit,
            @ToolArg(description = "Opaque continuation cursor", required = false) String cursor) {
        JsonNode scope = scope(mockId, null);
        return wireMock("list_unmatched_requests", mockId, () -> McpToolExecutor.ToolResult.of(
                "Listed unmatched requests for " + mockId + ".",
                collectionResult(mockId, boundedCollectionPage("list_unmatched_requests", scope, cursor,
                        () -> wireMock.listUnmatchedRequestsPage(mockId, pageSize(limit),
                                cursorPosition("list_unmatched_requests", scope, cursor),
                                config.maxCollectionScanBytes(), config.maxCollectionScanItems())),
                        "requests", "requests", "list_unmatched_requests", scope)));
    }

    @ToolGuardrails(input = StrictToolInputGuardrail.class, output = StructuredToolErrorGuardrail.class)
    @Tool(name = "get_near_misses", description = "Get near misses for unmatched requests or an optional request pattern.", inputSchema = @Tool.InputSchema(generator = RichJsonInputSchemaGenerator.class), outputSchema = @Tool.OutputSchema(from = OutputSchemas.NearMisses.class, generator = ToolOutputSchemaGenerator.class), annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public ToolResponse getNearMisses(@ToolArg(description = "Mock ID") String mockId,
            @ToolArg(description = "Optional native WireMock request-pattern JSON", required = false) Map<String, Object> requestPattern,
            @ToolArg(description = "Page size", required = false) Integer limit,
            @ToolArg(description = "Opaque continuation cursor", required = false) String cursor) {
        JsonNode pattern = requestPattern == null ? null : json(requestPattern);
        JsonNode scope = scope(mockId, pattern);
        return wireMock("get_near_misses", mockId, () -> McpToolExecutor.ToolResult.of(
                "Loaded near misses for " + mockId + ".",
                collectionResult(mockId, boundedCollectionPage("get_near_misses", scope, cursor,
                        () -> wireMock.getNearMissesPage(mockId, pattern, pageSize(limit),
                                cursorPosition("get_near_misses", scope, cursor), config.maxCollectionScanBytes(),
                                config.maxCollectionScanItems())),
                        "nearMisses", "nearMisses", "get_near_misses", scope)));
    }

    @ToolGuardrails(input = StrictToolInputGuardrail.class, output = StructuredToolErrorGuardrail.class)
    @Tool(name = "reset_request_journal", description = "Delete all entries from the WireMock request journal.", outputSchema = @Tool.OutputSchema(from = OutputSchemas.Reset.class, generator = ToolOutputSchemaGenerator.class), annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false))
    public ToolResponse resetRequestJournal(@ToolArg(description = "Mock ID") String mockId) {
        return wireMock("reset_request_journal", mockId, () -> {
            wireMock.resetRequestJournal(mockId);
            return McpToolExecutor.ToolResult.of("Reset request journal for " + mockId + ".",
                    Map.of("mockId", mockId, "reset", true));
        });
    }

    @ToolGuardrails(input = StrictToolInputGuardrail.class, output = StructuredToolErrorGuardrail.class)
    @Tool(name = "start_recording", description = "Start WireMock recording with persist=false and outputFormat=IDS. The target is checked against the outbound policy.", inputSchema = @Tool.InputSchema(generator = RichJsonInputSchemaGenerator.class), outputSchema = @Tool.OutputSchema(from = OutputSchemas.RecordingStatus.class, generator = ToolOutputSchemaGenerator.class), annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = true, idempotentHint = false, openWorldHint = true))
    public ToolResponse startRecording(@ToolArg(description = "Mock ID") String mockId,
            @ToolArg(description = "Native WireMock recorder JSON") Map<String, Object> recording) {
        return wireMock("start_recording", mockId, () -> coordinator.serialized(mockId, () -> {
            ObjectNode payload = object(recording, "recording");
            outboundTargets.validate(payload);
            return McpToolExecutor.ToolResult.of("Started recording for " + mockId + ".",
                    wrapped(mockId, "status", wireMock.startRecording(mockId, payload)));
        }));
    }

    @ToolGuardrails(input = StrictToolInputGuardrail.class, output = StructuredToolErrorGuardrail.class)
    @Tool(name = "get_recording_status", description = "Get WireMock recorder status. This may start an inactive mock pod.", outputSchema = @Tool.OutputSchema(from = OutputSchemas.RecordingStatus.class, generator = ToolOutputSchemaGenerator.class), annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public ToolResponse recordingStatus(@ToolArg(description = "Mock ID") String mockId) {
        return wireMock("get_recording_status", mockId, () -> McpToolExecutor.ToolResult.of(
                "Loaded recorder status for " + mockId + ".", wrapped(mockId, "status", wireMock.recordingStatus(mockId))));
    }

    @ToolGuardrails(input = StrictToolInputGuardrail.class, output = StructuredToolErrorGuardrail.class)
    @Tool(name = "stop_recording", description = "Stop recording and return sanitized temporary candidate IDs for review.", outputSchema = @Tool.OutputSchema(from = OutputSchemas.RecordingCandidates.class, generator = ToolOutputSchemaGenerator.class), annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = true, idempotentHint = false, openWorldHint = true))
    public ToolResponse stopRecording(@ToolArg(description = "Mock ID") String mockId) {
        return wireMock("stop_recording", mockId, () -> coordinator.serialized(mockId, () -> McpToolExecutor.ToolResult.of(
                "Stopped recording for " + mockId + ". Review candidates with get_stub.",
                recordingCandidates(mockId, wireMock.stopRecording(mockId)))));
    }

    @ToolGuardrails(input = StrictToolInputGuardrail.class, output = StructuredToolErrorGuardrail.class)
    @Tool(name = "snapshot_requests", description = "Create sanitized temporary recorder candidates with persist=false and outputFormat=IDS.", inputSchema = @Tool.InputSchema(generator = RichJsonInputSchemaGenerator.class), outputSchema = @Tool.OutputSchema(from = OutputSchemas.RecordingCandidates.class, generator = ToolOutputSchemaGenerator.class), annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = true, idempotentHint = false, openWorldHint = true))
    public ToolResponse snapshotRequests(@ToolArg(description = "Mock ID") String mockId,
            @ToolArg(description = "Native WireMock snapshot JSON") Map<String, Object> snapshot) {
        return wireMock("snapshot_requests", mockId, () -> coordinator.serialized(mockId, () -> {
            ObjectNode payload = object(snapshot, "snapshot");
            outboundTargets.validate(payload);
            return McpToolExecutor.ToolResult.of("Created temporary recorder candidates for " + mockId + ".",
                    recordingCandidates(mockId, wireMock.snapshotRequests(mockId, payload)));
        }));
    }

    @ToolGuardrails(input = StrictToolInputGuardrail.class, output = StructuredToolErrorGuardrail.class)
    @Tool(name = "list_body_files", description = "List WireMock body-file names. This may start an inactive mock pod.", outputSchema = @Tool.OutputSchema(from = OutputSchemas.BodyFilePage.class, generator = ToolOutputSchemaGenerator.class), annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public ToolResponse listBodyFiles(@ToolArg(description = "Mock ID") String mockId,
            @ToolArg(description = "Page size", required = false) Integer limit,
            @ToolArg(description = "Opaque continuation cursor", required = false) String cursor) {
        JsonNode scope = scope(mockId, null);
        return wireMock("list_body_files", mockId, () -> McpToolExecutor.ToolResult.of(
                "Listed body files for " + mockId + ".",
                collectionResult(mockId, boundedCollectionPage("list_body_files", scope, cursor,
                        () -> wireMock.listBodyFilesPage(mockId, pageSize(limit),
                                cursorPosition("list_body_files", scope, cursor), config.maxCollectionScanBytes(),
                                config.maxCollectionScanItems())),
                        "files", "files", "list_body_files", scope)));
    }

    @ToolGuardrails(input = StrictToolInputGuardrail.class, output = StructuredToolErrorGuardrail.class)
    @Tool(name = "get_body_file", description = "Get a WireMock body file as text or base64 within the configured body limit.", outputSchema = @Tool.OutputSchema(from = OutputSchemas.GetBodyFile.class, generator = ToolOutputSchemaGenerator.class), annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public ToolResponse getBodyFile(@ToolArg(description = "Mock ID") String mockId,
            @ToolArg(description = "Relative body-file name") String fileName) {
        return wireMock("get_body_file", mockId, () -> {
            TransportResponse response = wireMock.getBodyFile(mockId, fileName);
            if (response.body().length > config.includedBodyBytes()) {
                throw tooLarge(response.body().length, config.includedBodyBytes());
            }
            ObjectNode result = mapper.createObjectNode();
            result.set("body", encodedBody(response.body(), null));
            result.put("mockId", mockId);
            result.put("fileName", fileName);
            return McpToolExecutor.ToolResult.of("Loaded body file " + fileName + ".", result);
        });
    }

    @ToolGuardrails(input = StrictToolInputGuardrail.class, output = StructuredToolErrorGuardrail.class)
    @Tool(name = "put_body_file", description = "Create or replace a WireMock body file from encoded content.", inputSchema = @Tool.InputSchema(generator = BodyInputSchemaGenerator.class), outputSchema = @Tool.OutputSchema(from = OutputSchemas.PutBodyFile.class, generator = ToolOutputSchemaGenerator.class), annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false))
    public ToolResponse putBodyFile(@ToolArg(description = "Mock ID") String mockId,
            @ToolArg(description = "Relative body-file name") String fileName,
            @ToolArg(description = "Encoded file content") Map<String, Object> body) {
        return wireMock("put_body_file", mockId, () -> coordinator.serialized(mockId, () -> {
            byte[] content = decodeBody(body, true);
            wireMock.putBodyFile(mockId, fileName, content);
            return McpToolExecutor.ToolResult.of("Stored body file " + fileName + ".",
                    Map.of("mockId", mockId, "fileName", fileName, "sizeBytes", content.length));
        }));
    }

    @ToolGuardrails(input = StrictToolInputGuardrail.class, output = StructuredToolErrorGuardrail.class)
    @Tool(name = "delete_body_file", description = "Delete a body file. Referencing stubs are reported and require force=true; stubs are never deleted.", outputSchema = @Tool.OutputSchema(from = OutputSchemas.DeleteBodyFile.class, generator = ToolOutputSchemaGenerator.class), annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false))
    public ToolResponse deleteBodyFile(@ToolArg(description = "Mock ID") String mockId,
            @ToolArg(description = "Relative body-file name") String fileName,
            @ToolArg(description = "Delete even when stubs reference the file", required = false, defaultValue = "false") boolean force) {
        return wireMock("delete_body_file", mockId, () -> coordinator.serialized(mockId, () -> {
            BodyFileName.requireValid(fileName);
            String reference = force ? null : firstReferencingStub(mockId, fileName);
            if (reference != null) {
                throw new McpOperationException("BODY_FILE_REFERENCED",
                        "Body file is referenced by stubs; set force=true to delete it without cascading", false,
                        Map.of("fileName", fileName, "stubId", reference));
            }
            wireMock.deleteBodyFile(mockId, fileName);
            return McpToolExecutor.ToolResult.of("Deleted body file " + fileName + ".",
                    Map.of("mockId", mockId, "fileName", fileName, "deleted", true, "forced", force));
        }));
    }

    @ToolGuardrails(input = StrictToolInputGuardrail.class, output = StructuredToolErrorGuardrail.class)
    @Tool(name = "list_scenarios", description = "List WireMock scenario state. This may start an inactive mock pod.", outputSchema = @Tool.OutputSchema(from = OutputSchemas.ScenarioPage.class, generator = ToolOutputSchemaGenerator.class), annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public ToolResponse listScenarios(@ToolArg(description = "Mock ID") String mockId,
            @ToolArg(description = "Page size", required = false) Integer limit,
            @ToolArg(description = "Opaque continuation cursor", required = false) String cursor) {
        JsonNode scope = scope(mockId, null);
        return wireMock("list_scenarios", mockId, () -> McpToolExecutor.ToolResult.of(
                "Listed scenarios for " + mockId + ".",
                collectionResult(mockId, boundedCollectionPage("list_scenarios", scope, cursor,
                        () -> wireMock.listScenariosPage(mockId, pageSize(limit),
                                cursorPosition("list_scenarios", scope, cursor), config.maxCollectionScanBytes(),
                                config.maxCollectionScanItems())),
                        "scenarios", "scenarios", "list_scenarios", scope)));
    }

    @ToolGuardrails(input = StrictToolInputGuardrail.class, output = StructuredToolErrorGuardrail.class)
    @Tool(name = "reset_scenarios", description = "Reset all WireMock scenarios to their initial state.", outputSchema = @Tool.OutputSchema(from = OutputSchemas.Reset.class, generator = ToolOutputSchemaGenerator.class), annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false))
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
            requireRunning(mockId);
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

    private ObjectNode requireLifecycleResponse(String mockId) {
        return requireLifecycleResponse(mockId, "start", Set.of("RUNNING", "STARTING"),
                () -> fleetApi.startMock(mockId));
    }

    private ObjectNode requireStopLifecycleResponse(String mockId) {
        return requireLifecycleResponse(mockId, "stop", Set.of("STOPPED"), () -> fleetApi.stopMock(mockId));
    }

    private ObjectNode requireLifecycleResponse(String mockId, String operation, Set<String> allowedStatuses,
            Supplier<JsonNode> request) {
        MockIdValidator.requireValid(mockId);
        JsonNode response = request.get();
        if (!response.isObject() || !response.path("mockId").isTextual() || !response.path("status").isTextual()) {
            throw invalidUpstreamResponse("Fleet " + operation + " response is missing lifecycle fields", mockId);
        }
        if (!mockId.equals(response.path("mockId").asText())) {
            throw invalidUpstreamResponse(
                    "Fleet " + operation + " response mockId does not match the requested mock", mockId);
        }
        String status = response.path("status").asText();
        if (!allowedStatuses.contains(status)) {
            throw invalidUpstreamResponse(
                    "Fleet " + operation + " response contains unsupported status " + status, mockId);
        }
        requireNullableText(response, "podName", mockId, operation);
        requireNullableText(response, "message", mockId, operation);
        requireNullableNonNegativeInteger(response, "retryAfterMs", mockId, operation);
        ObjectNode result = mapper.createObjectNode();
        result.put("mockId", mockId);
        result.put("status", status);
        copyNullable(result, response, "podName");
        copyNullable(result, response, "message");
        copyNullable(result, response, "retryAfterMs");
        return result;
    }

    private void requireRunning(String mockId) {
        ObjectNode lifecycle = requireLifecycleResponse(mockId);
        if (!"STARTING".equals(lifecycle.path("status").asText())) {
            return;
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("mockId", mockId);
        details.put("status", "STARTING");
        if (!lifecycle.path("podName").isNull()) {
            details.put("podName", lifecycle.path("podName").asText());
        }
        if (!lifecycle.path("message").isNull()) {
            details.put("message", lifecycle.path("message").asText());
        }
        if (!lifecycle.path("retryAfterMs").isNull()) {
            details.put("retryAfterMs", lifecycle.path("retryAfterMs").asInt());
        }
        throw new McpOperationException("MOCK_STARTING", "Mock " + mockId + " is still starting", true, false, details);
    }

    private void requireNullableText(JsonNode response, String field, String mockId, String operation) {
        JsonNode value = response.get(field);
        if (value == null) {
            throw invalidUpstreamResponse(
                    "Fleet " + operation + " response field " + field + " is required", mockId);
        }
        if (!value.isNull() && !value.isTextual()) {
            throw invalidUpstreamResponse(
                    "Fleet " + operation + " response field " + field + " must be a string or null", mockId);
        }
    }

    private void requireNullableNonNegativeInteger(JsonNode response, String field, String mockId, String operation) {
        JsonNode value = response.get(field);
        if (value == null) {
            throw invalidUpstreamResponse(
                    "Fleet " + operation + " response field " + field + " is required", mockId);
        }
        if (!value.isNull() && (!value.isIntegralNumber() || !value.canConvertToInt() || value.asInt() < 0)) {
            throw invalidUpstreamResponse(
                    "Fleet " + operation + " response field " + field
                            + " must be a non-negative integer or null",
                    mockId);
        }
    }

    private void copyNullable(ObjectNode target, JsonNode source, String field) {
        JsonNode value = source.get(field);
        target.set(field, value == null ? mapper.getNodeFactory().nullNode() : value);
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

    private String firstReferencingStub(String mockId, String fileName) {
        int offset = 0;
        long scannedBytes = 0;
        int scannedItems = 0;
        while (true) {
            JsonNode page = wireMock.listStubs(mockId, config.maxPageSize(), offset);
            JsonNode mappings = page.path("mappings");
            if (!mappings.isArray()) {
                break;
            }
            for (JsonNode mapping : mappings) {
                try {
                    scannedBytes += mapper.writeValueAsBytes(mapping).length;
                } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
                    throw new IllegalStateException("Unable to measure mapping reference scan", failure);
                }
                scannedItems++;
                enforceReferenceScanBudget(scannedBytes, scannedItems, offset);
                List<String> files = new ArrayList<>();
                collectTextFields(mapping, "bodyFileName", files);
                if (files.contains(fileName)) {
                    return mapping.path("id").asText(mapping.path("uuid").asText("unknown"));
                }
            }
            if (!page.path("meta").path("hasMore").asBoolean()) {
                break;
            }
            offset = page.path("meta").path("nextPosition").asInt(offset + mappings.size());
        }
        return null;
    }

    private void enforceReferenceScanBudget(long bytes, int items, int position) {
        if (bytes > config.maxCollectionScanBytes()) {
            throw new McpOperationException("RESULT_TOO_LARGE", "Body reference scan byte limit exceeded", false,
                    Map.of("limitBytes", config.maxCollectionScanBytes(), "position", position));
        }
        if (items > config.maxCollectionScanItems()) {
            throw new McpOperationException("RESULT_TOO_LARGE", "Body reference scan item limit exceeded", false,
                    Map.of("limitItems", config.maxCollectionScanItems(), "position", position));
        }
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
        String normalizedContentType = contentType == null || contentType.isBlank()
                ? "application/octet-stream" : contentType;
        result.put("contentType", normalizedContentType);
        result.set("body", encodedBody(body, contentType));
        return result;
    }

    private ObjectNode encodedBody(byte[] body, String contentType) {
        ObjectNode encoded = mapper.createObjectNode();
        String normalizedContentType = contentType == null || contentType.isBlank()
                ? "application/octet-stream" : contentType;
        String printable = printableUtf8(body);
        boolean inferText = contentType == null || contentType.isBlank()
                || normalizedContentType.toLowerCase(Locale.ROOT).startsWith("application/octet-stream");
        if ((isText(normalizedContentType) || inferText) && printable != null) {
            encoded.put("encoding", "utf8");
            encoded.put("data", printable);
        } else {
            encoded.put("encoding", "base64");
            encoded.put("data", Base64.getEncoder().encodeToString(body));
        }
        encoded.put("sizeBytes", body.length);
        return encoded;
    }

    private ObjectNode wrapped(String mockId, String field, JsonNode value) {
        ObjectNode result = mapper.createObjectNode();
        result.put("mockId", mockId);
        result.set(field, value);
        return result;
    }

    private ObjectNode collectionResult(String mockId, JsonNode source, String sourceField, String resultField,
            String toolName, JsonNode scope) {
        if (!source.path(sourceField).isArray() || !source.path("meta").isObject()) {
            throw new McpOperationException("INVALID_UPSTREAM_RESPONSE", "Paginated result is missing collection or metadata",
                    false, Map.of("field", sourceField));
        }
        ObjectNode result = mapper.createObjectNode();
        if (source.path("resourceVersion").isValueNode()) {
            result.set("resourceVersion", source.path("resourceVersion"));
        }
        if (mockId != null) {
            result.put("mockId", mockId);
        }
        result.set(resultField, source.path(sourceField));
        JsonNode meta = source.path("meta");
        if (!meta.path("limit").isIntegralNumber() || !meta.path("returned").isIntegralNumber()
                || !meta.path("hasMore").isBoolean() || !meta.path("nextPosition").isIntegralNumber()) {
            throw new McpOperationException("INVALID_UPSTREAM_RESPONSE", "Paginated metadata is malformed", false,
                    Map.of("field", sourceField));
        }
        ObjectNode page = result.putObject("page");
        page.put("limit", meta.path("limit").asInt());
        page.put("returned", meta.path("returned").asInt());
        boolean hasMore = meta.path("hasMore").asBoolean();
        page.put("hasMore", hasMore);
        if (hasMore) {
            page.put("nextCursor", cursors.encode(toolName, scope, meta.path("nextPosition").asLong()));
        } else {
            page.putNull("nextCursor");
        }
        return result;
    }

    private ObjectNode recordingCandidates(String mockId, JsonNode response) {
        JsonNode ids = response.path("ids");
        if (!ids.isArray()) {
            throw invalidUpstreamResponse("WireMock recorder response is missing candidate IDs", mockId);
        }
        ObjectNode result = mapper.createObjectNode();
        result.put("mockId", mockId);
        result.set("candidateIds", ids);
        result.put("candidateCount", ids.size());
        result.put("matchedRequests", !ids.isEmpty());
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

    private byte[] decodeBody(Map<String, Object> body, boolean required) {
        if (body == null) {
            if (required) {
                throw new IllegalArgumentException("body is required");
            }
            return new byte[0];
        }
        ObjectNode value = object(body, "body");
        String encoding = value.path("encoding").isTextual() ? value.path("encoding").asText() : null;
        String data = value.path("data").isTextual() ? value.path("data").asText() : null;
        if (data == null) {
            throw new IllegalArgumentException("body.data is required");
        }
        byte[] decoded = switch (encoding == null ? "" : encoding) {
            case "utf8" -> data.getBytes(StandardCharsets.UTF_8);
            case "base64" -> decodeBase64(data, "body.data");
            default -> throw new IllegalArgumentException("Unsupported body.encoding: " + encoding);
        };
        if (!value.path("sizeBytes").canConvertToInt() || value.path("sizeBytes").asInt() < 0) {
            throw new IllegalArgumentException("body.sizeBytes must be a non-negative integer");
        }
        if (value.path("sizeBytes").asInt() != decoded.length) {
            throw new IllegalArgumentException("body.sizeBytes does not match decoded data");
        }
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

    private ObjectNode page(String toolName, JsonNode scope, JsonNode source, String field, Integer limit, String cursor) {
        long position = cursorPosition(toolName, scope, cursor);
        if (position > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Cursor position is too large");
        }
        return JsonPaginator.page(mapper, source, field, pageSize(limit), (int) position);
    }

    private long cursorPosition(String toolName, JsonNode scope, String cursor) {
        return cursors.decode(toolName, scope, cursor);
    }

    private JsonNode boundedCollectionPage(String toolName, JsonNode scope, String cursor,
            java.util.function.Supplier<JsonNode> action) {
        try {
            return action.get();
        } catch (McpOperationException failure) {
            if (!"RESULT_TOO_LARGE".equals(failure.code())) {
                throw failure;
            }
            Map<String, Object> details = new LinkedHashMap<>(failure.details());
            details.put("cursor", cursor == null || cursor.isBlank()
                    ? cursors.encode(toolName, scope, 0) : cursor);
            throw new McpOperationException(failure.code(), failure.getMessage(), failure.retryable(),
                    failure.stateMayHaveChanged(), details);
        }
    }

    private int mappingCursorPosition(String toolName, JsonNode scope, String cursor) {
        long position = cursorPosition(toolName, scope, cursor);
        if (position > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Cursor position is too large");
        }
        return (int) position;
    }

    private int pageSize(Integer value) {
        int resolved = value == null ? config.defaultPageSize() : value;
        if (resolved < 1 || resolved > config.maxPageSize()) {
            throw new IllegalArgumentException("limit must be between 1 and " + config.maxPageSize());
        }
        return resolved;
    }

    private ObjectNode scope(String mockId, JsonNode filter) {
        ObjectNode scope = mapper.createObjectNode();
        if (mockId != null) {
            scope.put("mockId", mockId);
        }
        if (filter != null) {
            scope.set("filter", filter);
        }
        return scope;
    }

    private HttpMethod parseMethod(String value) {
        try {
            return HttpMethod.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            throw new IllegalArgumentException("Unsupported HTTP method: " + value);
        }
    }

    private ConfigApplyMode parseApplyMode(String value) {
        try {
            return ConfigApplyMode.valueOf(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("Unsupported applyMode: " + value);
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

    private McpOperationException invalidMutationResponse(String message, String mockId) {
        return new McpOperationException("INVALID_UPSTREAM_RESPONSE", message, false, true,
                Map.of("mockId", mockId));
    }

    private JsonNode requireMutationEnvelope(JsonNode view, String mockId) {
        JsonNode configView = view == null ? null : view.get("config");
        JsonNode resourceVersion = configView == null ? null : configView.get("resourceVersion");
        JsonNode apply = view == null ? null : view.get("apply");
        if (configView == null || !configView.isObject()
                || resourceVersion == null || (!resourceVersion.isTextual() && !resourceVersion.isNull())
                || apply == null || !apply.isObject()
                || !mockId.equals(apply.path("mockId").asText())
                || !("futureOnly".equals(apply.path("mode").asText())
                        || "restartActive".equals(apply.path("mode").asText()))
                || !apply.path("lifecycle").isTextual()) {
            throw invalidMutationResponse("Fleet API returned a malformed mutation response", mockId);
        }
        return configView;
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

    private static String printableUtf8(byte[] bytes) {
        try {
            String value = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            boolean printable = value.codePoints().allMatch(codePoint -> codePoint == '\n' || codePoint == '\r'
                    || codePoint == '\t' || (codePoint >= 0x20 && codePoint != 0x7f && !Character.isISOControl(codePoint)));
            return printable ? value : null;
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    private static McpOperationException tooLarge(int actual, int limit) {
        return new McpOperationException("RESULT_TOO_LARGE", "Body exceeds the configured inclusion limit", false,
                Map.of("actualBytes", actual, "limitBytes", limit));
    }
}
