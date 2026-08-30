package com.github.letsrokk.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.http.HttpMethod;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class WireMockAdminClient {

    private static final String RECOVERY_METADATA_KEY = "_mockFleetMcpRecovery";
    private static final String RECORDER_DISCARD_METADATA_KEY = "_mockFleetMcpRecorderDiscarded";
    private static final int MAPPING_SCAN_PAGE_SIZE = 200;

    private static final Map<String, List<String>> JSON_HEADERS = Map.of(
            "accept", List.of("application/json"),
            "content-type", List.of("application/json"));

    private final FleetProxyTransport transport;
    private final ObjectMapper mapper;
    private final int maxPayloadBytes;
    private final JsonSanitizer sanitizer;
    private final WireMockVersion configuredVersion;
    private final long maxCollectionScanBytes;
    private final int maxCollectionScanItems;
    private final RecorderCleanupPolicy recorderCleanupPolicy;
    private final BodyFileReadPolicy bodyFileReadPolicy;

    public WireMockAdminClient(FleetProxyTransport transport, ObjectMapper mapper, int maxPayloadBytes,
            Set<String> sensitiveHeaders) {
        this(transport, mapper, maxPayloadBytes, sensitiveHeaders, null, null);
    }

    public WireMockAdminClient(FleetProxyTransport transport, ObjectMapper mapper, int maxPayloadBytes,
            Set<String> sensitiveHeaders, McpMetrics metrics) {
        this(transport, mapper, maxPayloadBytes, sensitiveHeaders, metrics, null);
    }

    public WireMockAdminClient(FleetProxyTransport transport, ObjectMapper mapper, int maxPayloadBytes,
            Set<String> sensitiveHeaders, McpMetrics metrics, WireMockVersion configuredVersion) {
        this(transport, mapper, maxPayloadBytes, sensitiveHeaders, metrics, configuredVersion,
                67_108_864L, 100_000);
    }

    public WireMockAdminClient(FleetProxyTransport transport, ObjectMapper mapper, int maxPayloadBytes,
            Set<String> sensitiveHeaders, McpMetrics metrics, WireMockVersion configuredVersion,
            long maxCollectionScanBytes, int maxCollectionScanItems) {
        this(transport, mapper, maxPayloadBytes, sensitiveHeaders, metrics, configuredVersion,
                maxCollectionScanBytes, maxCollectionScanItems, RecorderCleanupPolicy.production(),
                BodyFileReadPolicy.production());
    }

    WireMockAdminClient(FleetProxyTransport transport, ObjectMapper mapper, int maxPayloadBytes,
            Set<String> sensitiveHeaders, McpMetrics metrics, WireMockVersion configuredVersion,
            long maxCollectionScanBytes, int maxCollectionScanItems, RecorderCleanupPolicy recorderCleanupPolicy) {
        this(transport, mapper, maxPayloadBytes, sensitiveHeaders, metrics, configuredVersion,
                maxCollectionScanBytes, maxCollectionScanItems, recorderCleanupPolicy,
                BodyFileReadPolicy.production());
    }

    WireMockAdminClient(FleetProxyTransport transport, ObjectMapper mapper, int maxPayloadBytes,
            Set<String> sensitiveHeaders, McpMetrics metrics, WireMockVersion configuredVersion,
            long maxCollectionScanBytes, int maxCollectionScanItems, RecorderCleanupPolicy recorderCleanupPolicy,
            BodyFileReadPolicy bodyFileReadPolicy) {
        this.transport = transport;
        this.mapper = mapper;
        this.maxPayloadBytes = maxPayloadBytes;
        this.sanitizer = new JsonSanitizer(mapper, sensitiveHeaders, metrics);
        this.configuredVersion = configuredVersion;
        this.maxCollectionScanBytes = maxCollectionScanBytes;
        this.maxCollectionScanItems = maxCollectionScanItems;
        this.recorderCleanupPolicy = recorderCleanupPolicy;
        this.bodyFileReadPolicy = bodyFileReadPolicy;
    }

    public WireMockVersion version(String mockId) {
        return version(mockId, () -> configuredVersion);
    }

    public WireMockVersion version(String mockId, Supplier<WireMockVersion> runtimeFallback) {
        try {
            JsonNode response = getJson(mockId, "/__admin/version");
            String version = response.path("version").asText(response.asText());
            return WireMockVersion.parse(version);
        } catch (McpOperationException e) {
            if (!isMissingVersionEndpoint(e)) {
                throw e;
            }
            WireMockVersion fallback = runtimeFallback.get();
            if (fallback == null || fallback.minor() != 0) {
                throw e;
            }
            JsonNode probe = getJson(mockId, "/__admin/mappings?limit=1&offset=0");
            if (!probe.path("mappings").isArray()) {
                throw new McpOperationException("INVALID_UPSTREAM_RESPONSE",
                        "Legacy WireMock runtime probe returned an unexpected response", false,
                        Map.of("runtimeVersion", fallback.toString()));
            }
            return fallback;
        }
    }

    public JsonNode listStubs(String mockId, int limit, int rawOffset) {
        ArrayNode page = mapper.createArrayNode();
        int position = rawOffset;
        int nextPosition = rawOffset;
        boolean hasMore = false;
        while (true) {
            MappingPage rawPage = mappingPage(mockId, position, Math.min(MAPPING_SCAN_PAGE_SIZE, limit + 1));
            JsonNode mappings = rawPage.response().path("mappings");
            if (!mappings.isArray()) {
                throw new McpOperationException("INVALID_UPSTREAM_RESPONSE",
                        "WireMock mapping page did not contain mappings", false, Map.of("position", position));
            }
            for (JsonNode mapping : mappings) {
                int itemPosition = position++;
                if (isRecoveryMapping(mapping)) {
                    nextPosition = position;
                    continue;
                }
                if (page.size() == limit) {
                    hasMore = true;
                    nextPosition = itemPosition;
                    break;
                }
                page.add(mapping);
                nextPosition = position;
            }
            if (hasMore) {
                break;
            }
            int total = rawPage.response().path("meta").path("total").asInt(position);
            if (mappings.isEmpty() || position >= total) {
                break;
            }
        }
        ObjectNode response = mapper.createObjectNode();
        response.set("mappings", page);
        ObjectNode meta = response.putObject("meta");
        meta.put("limit", limit);
        meta.put("returned", page.size());
        meta.put("hasMore", hasMore);
        meta.put("nextPosition", nextPosition);
        return response;
    }

    public JsonNode listUnmatchedStubs(String mockId) {
        return withoutRecoveryMappings(getJson(mockId, "/__admin/mappings/unmatched"));
    }

    public JsonNode listUnmatchedStubsPage(String mockId, int limit, long position, long maxBytes, int maxItems) {
        return visibleCollectionPage(mockId,
                new TransportRequest(HttpMethod.GET, "/__admin/mappings/unmatched", JSON_HEADERS, new byte[0]),
                "mappings", limit, position, maxBytes, maxItems, true);
    }

    public JsonNode getStub(String mockId, String stubId) {
        return getJson(mockId, "/__admin/mappings/" + requireIdentifier(stubId, "stubId"));
    }

    public JsonNode createStub(String mockId, ObjectNode mapping) {
        ObjectNode payload = serverManagedCopy(mapping);
        payload.put("persistent", false);
        return requireMutationMapping(mockId, null,
                sendMutationJson(mockId, HttpMethod.POST, "/__admin/mappings", payload));
    }

    public JsonNode updateStub(String mockId, String stubId, ObjectNode mapping) {
        String id = requireIdentifier(stubId, "stubId");
        PersistentTransaction pending = loadRecoveryTransaction(mockId, id);
        JsonNode current = pending == null ? getStub(mockId, id) : pending.before();
        ObjectNode payload = serverManagedCopy(mapping);
        copyIfPresent(current, payload, "id");
        copyIfPresent(current, payload, "uuid");
        payload.put("persistent", pending == null
                ? current.path("persistent").asBoolean(false)
                : pending.after().path("persistent").asBoolean(false));
        if (payload.path("persistent").asBoolean(false)) {
            return persistentMutation(mockId, "update", id, (ObjectNode) current, payload, pending);
        }
        try {
            return requireMutationMapping(mockId, id,
                    sendMutationJson(mockId, HttpMethod.PUT, "/__admin/mappings/" + id, payload));
        } catch (RuntimeException updateFailure) {
            JsonNode verified = getStubAfterAmbiguousFailure(mockId, id, updateFailure);
            if (mappingMatches(verified, payload)) {
                return verified;
            }
            throw updateFailure;
        }
    }

    public void deleteStub(String mockId, String stubId) {
        String id = requireIdentifier(stubId, "stubId");
        try {
            sendMutation(mockId, new TransportRequest(HttpMethod.DELETE,
                    "/__admin/mappings/" + id, Map.of(), new byte[0]));
        } catch (RuntimeException deleteFailure) {
            if (getStubAfterAmbiguousFailure(mockId, id, deleteFailure) == null) {
                return;
            }
            throw deleteFailure;
        }
    }

    public JsonNode setPersistent(String mockId, String stubId, boolean persistent) {
        String id = requireIdentifier(stubId, "stubId");
        PersistentTransaction pending = loadRecoveryTransaction(mockId, id);
        if (pending != null) {
            String operation = persistent ? "persist" : "unpersist";
            if (!operation.equals(pending.operation())
                    || pending.after().path("persistent").asBoolean(false) != persistent) {
                throw persistentUpdateConflict(pending, getStubOrNull(mockId, id));
            }
            return persistentMutation(mockId, operation, id,
                    pending.before(), pending.after(), pending);
        }
        if (persistent) {
            JsonNode current = getStub(mockId, id);
            if (current.path("persistent").asBoolean(false)) {
                return current;
            }
            ObjectNode payload = (ObjectNode) current.deepCopy();
            payload.put("persistent", true);
            return persistentMutation(mockId, "persist", id, (ObjectNode) current, payload, null);
        }

        JsonNode current = getStub(mockId, id);
        if (!current.path("persistent").asBoolean(false)) {
            return current;
        }
        ObjectNode payload = (ObjectNode) current.deepCopy();
        payload.put("persistent", false);
        return persistentMutation(mockId, "unpersist", id, (ObjectNode) current, payload, null);
    }

    public JsonNode findRequests(String mockId, JsonNode requestPattern) {
        return sanitizer.redactHeaders(sendJson(mockId, HttpMethod.POST, "/__admin/requests/find", requestPattern));
    }

    public JsonNode findRequestsPage(String mockId, JsonNode requestPattern, int limit, long position,
            long maxBytes, int maxItems) {
        return sanitizedCollectionPage(mockId, collectionRequest(HttpMethod.POST, "/__admin/requests/find", requestPattern),
                "requests", limit, position, maxBytes, maxItems);
    }

    public JsonNode countRequests(String mockId, JsonNode requestPattern) {
        return sendJson(mockId, HttpMethod.POST, "/__admin/requests/count", requestPattern);
    }

    public JsonNode listUnmatchedRequests(String mockId) {
        return sanitizer.redactHeaders(getJson(mockId, "/__admin/requests/unmatched"));
    }

    public JsonNode listUnmatchedRequestsPage(String mockId, int limit, long position, long maxBytes, int maxItems) {
        return sanitizedCollectionPage(mockId,
                new TransportRequest(HttpMethod.GET, "/__admin/requests/unmatched", JSON_HEADERS, new byte[0]),
                "requests", limit, position, maxBytes, maxItems);
    }

    public JsonNode getNearMisses(String mockId, JsonNode requestPattern) {
        String endpoint = requestPattern == null || requestPattern.isNull()
                ? "/__admin/requests/unmatched/near-misses"
                : "/__admin/near-misses/request-pattern";
        JsonNode response = requestPattern == null || requestPattern.isNull()
                ? getJson(mockId, endpoint)
                : sendJson(mockId, HttpMethod.POST, endpoint, requestPattern);
        return sanitizer.redactHeaders(response);
    }

    public JsonNode getNearMissesPage(String mockId, JsonNode requestPattern, int limit, long position,
            long maxBytes, int maxItems) {
        TransportRequest request = requestPattern == null || requestPattern.isNull()
                ? new TransportRequest(HttpMethod.GET, "/__admin/requests/unmatched/near-misses", JSON_HEADERS, new byte[0])
                : collectionRequest(HttpMethod.POST, "/__admin/near-misses/request-pattern", requestPattern);
        return sanitizedCollectionPage(mockId, request, "nearMisses", limit, position, maxBytes, maxItems);
    }

    public void resetRequestJournal(String mockId) {
        sendMutation(mockId, new TransportRequest(HttpMethod.DELETE, "/__admin/requests", Map.of(), new byte[0]));
    }

    public JsonNode startRecording(String mockId, ObjectNode recordingSpec) {
        ObjectNode payload = recordingSpec.deepCopy();
        payload.put("persist", false);
        payload.put("outputFormat", "IDS");
        sendMutationJson(mockId, HttpMethod.POST, "/__admin/recordings/start", payload);
        try {
            JsonNode status = recordingStatus(mockId);
            if (!status.isObject() || !status.path("status").isTextual()) {
                throw new McpOperationException("INVALID_UPSTREAM_RESPONSE",
                        "WireMock did not return a verifiable recording status", false, Map.of("mockId", mockId));
            }
            return status;
        } catch (RuntimeException failure) {
            throw mutationFailure(mockId, failure);
        }
    }

    public JsonNode recordingStatus(String mockId) {
        return getJson(mockId, "/__admin/recordings/status");
    }

    public JsonNode stopRecording(String mockId) {
        Set<String> baseline = mappingIds(mockId);
        JsonNode result;
        try {
            result = sendMutationWithoutBodyJson(mockId, HttpMethod.POST, "/__admin/recordings/stop");
        } catch (RuntimeException failure) {
            throw recorderMutationFailure(mockId, baseline, failure);
        }
        try {
            return sanitizeRecorderCandidates(mockId, result, baseline);
        } catch (RuntimeException failure) {
            throw mutationFailure(mockId, failure);
        }
    }

    public JsonNode snapshotRequests(String mockId, ObjectNode snapshotSpec) {
        Set<String> baseline = mappingIds(mockId);
        ObjectNode payload = snapshotSpec.deepCopy();
        payload.put("persist", false);
        payload.put("outputFormat", "IDS");
        JsonNode result;
        try {
            result = sendMutationJson(mockId, HttpMethod.POST, "/__admin/recordings/snapshot", payload);
        } catch (RuntimeException failure) {
            throw recorderMutationFailure(mockId, baseline, failure);
        }
        try {
            return sanitizeRecorderCandidates(mockId, result, baseline);
        } catch (RuntimeException failure) {
            throw mutationFailure(mockId, failure);
        }
    }

    public JsonNode listBodyFiles(String mockId) {
        return getJson(mockId, "/__admin/files");
    }

    public JsonNode listBodyFilesPage(String mockId, int limit, long position, long maxBytes, int maxItems) {
        return collectionPage(mockId, new TransportRequest(HttpMethod.GET, "/__admin/files", JSON_HEADERS, new byte[0]),
                "files", limit, position, maxBytes, maxItems);
    }

    public TransportResponse getBodyFile(String mockId, String fileName) {
        TransportRequest request = new TransportRequest(HttpMethod.GET,
                "/__admin/files/" + BodyFileName.toUrlPath(fileName), Map.of(), new byte[0]);
        McpOperationException lastFailure = null;
        for (int attempt = 0; attempt < bodyFileReadPolicy.attempts(); attempt++) {
            try {
                return send(mockId, request);
            } catch (McpOperationException failure) {
                if (!isStaleFileHandle(failure)) {
                    throw failure;
                }
                lastFailure = failure;
                if (attempt + 1 < bodyFileReadPolicy.attempts() && bodyFileReadPolicy.intervalMillis() > 0) {
                    try {
                        Thread.sleep(bodyFileReadPolicy.intervalMillis());
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw failure;
                    }
                }
            }
        }
        throw lastFailure;
    }

    public void putBodyFile(String mockId, String fileName, byte[] content) {
        Map<String, List<String>> headers = Map.of("content-type", List.of("application/octet-stream"));
        sendMutation(mockId, new TransportRequest(HttpMethod.PUT,
                "/__admin/files/" + BodyFileName.toUrlPath(fileName), headers, content));
    }

    public void deleteBodyFile(String mockId, String fileName) {
        sendMutation(mockId, new TransportRequest(HttpMethod.DELETE,
                "/__admin/files/" + BodyFileName.toUrlPath(fileName), Map.of(), new byte[0]));
    }

    public JsonNode listScenarios(String mockId) {
        return getJson(mockId, "/__admin/scenarios");
    }

    public JsonNode listScenariosPage(String mockId, int limit, long position, long maxBytes, int maxItems) {
        return collectionPage(mockId,
                new TransportRequest(HttpMethod.GET, "/__admin/scenarios", JSON_HEADERS, new byte[0]),
                "scenarios", limit, position, maxBytes, maxItems);
    }

    public void resetScenarios(String mockId) {
        sendMutation(mockId,
                new TransportRequest(HttpMethod.POST, "/__admin/scenarios/reset", Map.of(), new byte[0]));
    }

    public TransportResponse sendRequest(String mockId, HttpMethod method, String requestTarget,
            Map<String, List<String>> headers, byte[] body) {
        return exchangeStateChanging(mockId, new TransportRequest(method,
                RequestTargetValidator.requireMockTraffic(requestTarget), headers, body));
    }

    private ObjectNode serverManagedCopy(ObjectNode mapping) {
        if (mapping == null) {
            throw new IllegalArgumentException("mapping is required");
        }
        ObjectNode copy = mapping.deepCopy();
        copy.remove(List.of("id", "uuid", "persistent"));
        return copy;
    }

    private JsonNode getJson(String mockId, String endpoint) {
        TransportResponse response = send(mockId,
                new TransportRequest(HttpMethod.GET, endpoint, Map.of("accept", List.of("application/json")), new byte[0]));
        return parseJson(response);
    }

    private JsonNode sendJson(String mockId, HttpMethod method, String endpoint, JsonNode payload) {
        byte[] body;
        try {
            body = mapper.writeValueAsBytes(payload);
        } catch (JsonProcessingException e) {
            throw new McpOperationException("INVALID_JSON", "Unable to serialize WireMock request JSON", false, Map.of());
        }
        return parseJson(send(mockId, new TransportRequest(method, endpoint, JSON_HEADERS, body)));
    }

    private JsonNode sendMutationJson(String mockId, HttpMethod method, String endpoint, JsonNode payload) {
        byte[] body;
        try {
            body = mapper.writeValueAsBytes(payload);
        } catch (JsonProcessingException e) {
            throw new McpOperationException("INVALID_JSON", "Unable to serialize WireMock request JSON", false,
                    Map.of());
        }
        TransportResponse response = sendMutation(mockId, new TransportRequest(method, endpoint, JSON_HEADERS, body));
        try {
            return parseJson(response, true);
        } catch (RuntimeException failure) {
            throw mutationFailure(mockId, failure);
        }
    }

    private JsonNode sendWithoutBodyJson(String mockId, HttpMethod method, String endpoint) {
        return parseJson(send(mockId, new TransportRequest(method, endpoint,
                Map.of("accept", List.of("application/json")), new byte[0])));
    }

    private JsonNode sendMutationWithoutBodyJson(String mockId, HttpMethod method, String endpoint) {
        TransportResponse response = sendMutation(mockId, new TransportRequest(method, endpoint,
                Map.of("accept", List.of("application/json")), new byte[0]));
        try {
            return parseJson(response, true);
        } catch (RuntimeException failure) {
            throw mutationFailure(mockId, failure);
        }
    }

    private RuntimeException recorderMutationFailure(String mockId, Set<String> baseline, RuntimeException failure) {
        if (!(failure instanceof McpOperationException operationFailure)
                || !operationFailure.stateMayHaveChanged()) {
            return failure;
        }
        McpOperationException uncertain = mutationFailure(mockId, failure);
        Set<String> candidateIds = new LinkedHashSet<>();
        int stableScans = 0;
        for (int poll = 0; poll < recorderCleanupPolicy.polls(); poll++) {
            Set<String> current;
            try {
                current = candidatesCreatedSince(mockId, baseline);
            } catch (RuntimeException discoveryFailure) {
                return recorderRecoveryFailure(mockId, "candidate-discovery", candidateIds, List.of(),
                        discoveryFailure, uncertain);
            }
            candidateIds.addAll(current);
            if (current.isEmpty()) {
                stableScans++;
            } else {
                stableScans = 0;
                List<String> cleanupFailedIds = cleanupRecorderCandidates(mockId, List.copyOf(current));
                if (!cleanupFailedIds.isEmpty()) {
                    return recorderRecoveryFailure(mockId, "candidate-cleanup", candidateIds, cleanupFailedIds,
                            null, uncertain);
                }
            }
            if (poll + 1 < recorderCleanupPolicy.polls() && recorderCleanupPolicy.intervalMillis() > 0) {
                try {
                    Thread.sleep(recorderCleanupPolicy.intervalMillis());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return recorderRecoveryFailure(mockId, "candidate-observation", candidateIds, List.of(),
                            new RuntimeException("Recorder cleanup polling was interrupted", interrupted), uncertain);
                }
            }
        }
        if (stableScans < recorderCleanupPolicy.requiredStableScans()) {
            return recorderRecoveryFailure(mockId, "candidate-stabilization", candidateIds, List.of(), null,
                    uncertain);
        }
        return uncertain;
    }

    private JsonNode sanitizeRecorderCandidates(String mockId, JsonNode result, Set<String> baseline) {
        JsonNode ids = result.path("ids");
        Set<String> candidateIds = new LinkedHashSet<>();
        McpOperationException invalidIds = ids.isArray() ? null : invalidRecorderCandidateIds(mockId);
        if (ids.isArray()) {
            for (JsonNode id : ids) {
                if (!id.isTextual()) {
                    invalidIds = invalidRecorderCandidateIds(mockId);
                    continue;
                }
                try {
                    candidateIds.add(requireIdentifier(id.asText(), "recorded stub ID"));
                } catch (IllegalArgumentException e) {
                    invalidIds = invalidRecorderCandidateIds(mockId);
                }
            }
        }
        if (invalidIds != null) {
            candidateIds.addAll(candidatesCreatedSince(mockId, baseline));
            throw recorderCleanupFailure(mockId, List.copyOf(candidateIds), invalidIds);
        }
        try {
            for (String id : candidateIds) {
                JsonNode sanitized = sanitizer.removeSensitiveHeaders(getStub(mockId, id));
                sendJson(mockId, HttpMethod.PUT, "/__admin/mappings/" + id, sanitized);
            }
        } catch (RuntimeException sanitizationFailure) {
            throw recorderCleanupFailure(mockId, List.copyOf(candidateIds), sanitizationFailure);
        }
        return result;
    }

    private RuntimeException recorderCleanupFailure(String mockId, List<String> candidateIds,
            RuntimeException originalFailure) {
        List<String> cleanupFailedIds = cleanupRecorderCandidates(mockId, candidateIds);
        if (cleanupFailedIds.isEmpty()) {
            return mutationFailure(mockId, originalFailure);
        }
        McpOperationException cleanupFailure = new McpOperationException("RECORDER_CLEANUP_FAILED",
                "Recorder candidates could not all be removed or neutralized", true, true,
                Map.of("mockId", mockId, "candidateIds", candidateIds, "cleanupFailedIds", cleanupFailedIds));
        cleanupFailure.addSuppressed(originalFailure);
        return cleanupFailure;
    }

    private List<String> cleanupRecorderCandidates(String mockId, List<String> candidateIds) {
        List<String> cleanupFailedIds = new ArrayList<>();
        for (String id : candidateIds) {
            if (!removeOrNeutralizeRecorderCandidate(mockId, id)) {
                cleanupFailedIds.add(id);
            }
        }
        return List.copyOf(cleanupFailedIds);
    }

    private McpOperationException recorderRecoveryFailure(String mockId, String stage, Set<String> candidateIds,
            List<String> cleanupFailedIds, RuntimeException recoveryFailure, RuntimeException originalFailure) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("mockId", mockId);
        details.put("stage", stage);
        details.put("candidateIds", List.copyOf(candidateIds));
        if (!cleanupFailedIds.isEmpty()) {
            details.put("cleanupFailedIds", List.copyOf(cleanupFailedIds));
        }
        if (recoveryFailure != null) {
            String errorField = "candidate-discovery".equals(stage) ? "discoveryError" : "observationError";
            details.put(errorField, failureDetails(recoveryFailure));
        }
        McpOperationException cleanupFailure = new McpOperationException("RECORDER_CLEANUP_FAILED",
                "Recorder candidate cleanup could not be verified", true, true, details);
        cleanupFailure.addSuppressed(originalFailure);
        if (recoveryFailure != null) {
            cleanupFailure.addSuppressed(recoveryFailure);
        }
        return cleanupFailure;
    }

    private boolean removeOrNeutralizeRecorderCandidate(String mockId, String id) {
        try {
            deleteStub(mockId, id);
        } catch (RuntimeException ignored) {
            // Verify the result before deciding whether a safe replacement is required.
        }
        JsonNode remaining = getStubForCleanup(mockId, id);
        if (remaining == null) {
            return true;
        }

        try {
            sendJson(mockId, HttpMethod.PUT, "/__admin/mappings/" + id, recorderDiscardMapping(id));
        } catch (RuntimeException ignored) {
            // A lost response can still mean the replacement succeeded, so verify below.
        }
        JsonNode verified = getStubForCleanup(mockId, id);
        return verified == null || verified.path("metadata").path(RECORDER_DISCARD_METADATA_KEY).asBoolean(false);
    }

    private JsonNode getStubForCleanup(String mockId, String id) {
        try {
            return getStubOrNull(mockId, id);
        } catch (RuntimeException ignored) {
            return mapper.missingNode();
        }
    }

    private ObjectNode recorderDiscardMapping(String id) {
        ObjectNode tombstone = mapper.createObjectNode();
        tombstone.put("id", id);
        tombstone.put("uuid", id);
        tombstone.put("persistent", false);
        ObjectNode request = tombstone.putObject("request");
        request.put("method", "OPTIONS");
        request.put("urlPath", "/__mock_fleet_mcp/discarded-recording/" + id);
        request.putObject("headers").putObject("X-Mock-Fleet-MCP-Discarded").put("equalTo", id);
        tombstone.putObject("response").put("status", 404);
        tombstone.putObject("metadata").put(RECORDER_DISCARD_METADATA_KEY, true);
        return tombstone;
    }

    private static McpOperationException invalidRecorderCandidateIds(String mockId) {
        return new McpOperationException("INVALID_UPSTREAM_RESPONSE",
                "WireMock recorder result did not contain only valid candidate IDs", false, true,
                Map.of("mockId", mockId));
    }

    private Set<String> candidatesCreatedSince(String mockId, Set<String> baseline) {
        Set<String> candidates = new LinkedHashSet<>(mappingIds(mockId));
        candidates.removeAll(baseline);
        return candidates;
    }

    private Set<String> mappingIds(String mockId) {
        Set<String> ids = new LinkedHashSet<>();
        scanMappings(mockId, mapping -> {
            if (isRecoveryMapping(mapping)
                    || mapping.path("metadata").path(RECORDER_DISCARD_METADATA_KEY).asBoolean(false)) {
                return;
            }
            JsonNode id = mapping.path("id");
            if (id.isTextual()) {
                try {
                    ids.add(requireIdentifier(id.asText(), "mapping ID"));
                } catch (IllegalArgumentException ignored) {
                    // WireMock-generated mapping IDs are UUIDs; malformed entries cannot be addressed safely.
                }
            }
        });
        return Set.copyOf(ids);
    }

    private void scanMappings(String mockId, Consumer<JsonNode> visitor) {
        int rawOffset = 0;
        int pageSize = MAPPING_SCAN_PAGE_SIZE;
        long scannedBytes = 0;
        int scannedItems = 0;
        while (true) {
            MappingPage page = mappingPage(mockId, rawOffset, pageSize);
            pageSize = page.limit();
            JsonNode mappings = page.response().path("mappings");
            if (!mappings.isArray()) {
                throw new McpOperationException("INVALID_UPSTREAM_RESPONSE",
                        "WireMock mapping page did not contain mappings", false, Map.of("offset", rawOffset));
            }
            try {
                scannedBytes += mapper.writeValueAsBytes(page.response()).length;
            } catch (JsonProcessingException failure) {
                throw new IllegalStateException("Unable to measure WireMock mapping page", failure);
            }
            scannedItems += mappings.size();
            enforceMappingScanBudget(scannedBytes, scannedItems, rawOffset);
            mappings.forEach(visitor);
            int returned = mappings.size();
            rawOffset += returned;
            int rawTotal = page.response().path("meta").path("total").asInt(rawOffset);
            if (rawOffset >= rawTotal) {
                return;
            }
            if (returned == 0) {
                throw new McpOperationException("INVALID_UPSTREAM_RESPONSE",
                        "WireMock mapping pagination made no progress", false,
                        Map.of("offset", rawOffset, "total", rawTotal));
            }
        }
    }

    private void enforceMappingScanBudget(long bytes, int items, int position) {
        if (bytes > maxCollectionScanBytes) {
            throw new McpOperationException("RESULT_TOO_LARGE", "Mapping scan byte limit exceeded", false,
                    Map.of("limitBytes", maxCollectionScanBytes, "position", position));
        }
        if (items > maxCollectionScanItems) {
            throw new McpOperationException("RESULT_TOO_LARGE", "Mapping scan item limit exceeded", false,
                    Map.of("limitItems", maxCollectionScanItems, "position", position));
        }
    }

    private MappingPage mappingPage(String mockId, int offset, int initialLimit) {
        int limit = initialLimit;
        while (true) {
            try {
                return new MappingPage(getJson(mockId,
                        "/__admin/mappings?limit=" + limit + "&offset=" + offset), limit);
            } catch (McpOperationException failure) {
                if (!"RESULT_TOO_LARGE".equals(failure.code()) || limit == 1) {
                    throw failure;
                }
                limit = Math.max(1, limit / 2);
            }
        }
    }

    private record MappingPage(JsonNode response, int limit) {
    }

    private JsonNode sanitizedCollectionPage(String mockId, TransportRequest request, String field, int limit,
            long position, long maxBytes, int maxItems) {
        return sanitizer.redactHeaders(collectionPage(mockId, request, field, limit, position, maxBytes, maxItems));
    }

    private ObjectNode collectionPage(String mockId, TransportRequest request, String field, int limit,
            long position, long maxBytes, int maxItems) {
        CollectionScan scan = transport.scanCollection(mockId, request, mapper, field, position, limit,
                maxBytes, maxItems);
        return collectionPage(field, limit, scan.items(), scan.nextPosition(), scan.hasMore());
    }

    private ObjectNode visibleCollectionPage(String mockId, TransportRequest request, String field, int limit,
            long position, long maxBytes, int maxItems, boolean hideRecoveryMappings) {
        ArrayNode visible = mapper.createArrayNode();
        long nextPosition = position;
        boolean hasMore = false;
        long remainingBytes = maxBytes;
        int remainingItems = maxItems;
        while (visible.size() <= limit) {
            int batchSize = Math.min(MAPPING_SCAN_PAGE_SIZE, Math.max(limit + 1, 16));
            CollectionScan scan = transport.scanCollection(mockId, request, mapper, field, nextPosition, batchSize,
                    remainingBytes, remainingItems);
            remainingBytes -= scan.scannedBytes();
            remainingItems -= scan.scannedItems();
            for (JsonNode item : scan.items()) {
                long itemPosition = nextPosition++;
                if (hideRecoveryMappings && isRecoveryMapping(item)) {
                    continue;
                }
                if (visible.size() == limit) {
                    hasMore = true;
                    nextPosition = itemPosition;
                    break;
                }
                visible.add(item);
            }
            if (hasMore) {
                break;
            }
            nextPosition = scan.nextPosition();
            if (!scan.hasMore()) {
                break;
            }
        }
        return collectionPage(field, limit, visible, nextPosition, hasMore);
    }

    private ObjectNode collectionPage(String field, int limit, ArrayNode items, long nextPosition, boolean hasMore) {
        ObjectNode result = mapper.createObjectNode();
        result.set(field, items);
        ObjectNode meta = result.putObject("meta");
        meta.put("limit", limit);
        meta.put("returned", items.size());
        meta.put("hasMore", hasMore);
        meta.put("nextPosition", nextPosition);
        return result;
    }

    private TransportRequest collectionRequest(HttpMethod method, String target, JsonNode body) {
        try {
            return new TransportRequest(method, target, JSON_HEADERS,
                    body == null ? new byte[0] : mapper.writeValueAsBytes(body));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Unable to serialize collection request");
        }
    }

    private TransportResponse send(String mockId, TransportRequest request) {
        TransportResponse response = exchange(mockId, request);
        if (response.status() < 200 || response.status() >= 300) {
            throw new McpOperationException("WIREMOCK_ADMIN_ERROR", "WireMock returned HTTP " + response.status(),
                    response.status() >= 500, Map.of("status", response.status(), "body", response.bodyAsString()));
        }
        return response;
    }

    private TransportResponse sendMutation(String mockId, TransportRequest request) {
        TransportResponse response = exchangeStateChanging(mockId, request);
        if (response.status() < 200 || response.status() >= 300) {
            throw new McpOperationException("WIREMOCK_ADMIN_ERROR", "WireMock returned HTTP " + response.status(),
                    response.status() >= 500, response.status() >= 500,
                    Map.of("mockId", mockId, "status", response.status(), "body", response.bodyAsString()));
        }
        return response;
    }

    private TransportResponse exchange(String mockId, TransportRequest request) {
        MockIdValidator.requireValid(mockId);
        if (request.body().length > maxPayloadBytes) {
            throw new McpOperationException("RESULT_TOO_LARGE", "Request payload exceeds the configured limit", false,
                    Map.of("limitBytes", maxPayloadBytes));
        }
        TransportResponse response = transport.execute(mockId, request);
        if (response.body().length > maxPayloadBytes) {
            throw new McpOperationException("RESULT_TOO_LARGE", "Response payload exceeds the configured limit", false,
                    Map.of("limitBytes", maxPayloadBytes));
        }
        return response;
    }

    private TransportResponse exchangeStateChanging(String mockId, TransportRequest request) {
        MockIdValidator.requireValid(mockId);
        if (request.body().length > maxPayloadBytes) {
            throw new McpOperationException("RESULT_TOO_LARGE", "Request payload exceeds the configured limit", false,
                    Map.of("limitBytes", maxPayloadBytes));
        }
        TransportResponse response;
        try {
            response = transport.execute(mockId, request);
        } catch (RuntimeException failure) {
            throw mutationFailure(mockId, failure);
        }
        if (response.body().length > maxPayloadBytes) {
            throw new McpOperationException("RESULT_TOO_LARGE", "Response payload exceeds the configured limit", false,
                    true, Map.of("mockId", mockId, "limitBytes", maxPayloadBytes));
        }
        return response;
    }

    private static McpOperationException mutationFailure(String mockId, RuntimeException failure) {
        if (failure instanceof McpOperationException operationFailure) {
            Map<String, Object> details = new LinkedHashMap<>(operationFailure.details());
            details.putIfAbsent("mockId", mockId);
            return new McpOperationException(operationFailure.code(), operationFailure.getMessage(),
                    operationFailure.retryable(), true, details);
        }
        return new McpOperationException("UPSTREAM_UNAVAILABLE",
                "WireMock mutation result is uncertain: " + failure.getMessage(), true, true,
                Map.of("mockId", mockId, "cause", failure.getClass().getSimpleName()));
    }

    private static JsonNode requireMutationMapping(String mockId, String expectedId, JsonNode response) {
        String actualId = response != null && response.isObject() && response.path("id").isTextual()
                ? response.path("id").asText() : null;
        try {
            requireIdentifier(actualId, "returned mapping ID");
        } catch (IllegalArgumentException invalidId) {
            throw new McpOperationException("INVALID_UPSTREAM_RESPONSE",
                    "WireMock mutation response did not contain a usable mapping ID", false, true,
                    Map.of("mockId", mockId));
        }
        if (expectedId != null && !expectedId.equals(actualId)) {
            throw new McpOperationException("INVALID_UPSTREAM_RESPONSE",
                    "WireMock mutation response contained an unexpected mapping ID", false, true,
                    Map.of("mockId", mockId, "expectedId", expectedId, "actualId", actualId));
        }
        return response;
    }

    private PersistentTransaction loadRecoveryTransaction(String mockId, String stubId) {
        JsonNode marker = getStubOrNull(mockId, recoveryMappingId(stubId));
        if (marker == null) {
            return null;
        }
        JsonNode recovery = marker.path("metadata").path(RECOVERY_METADATA_KEY);
        JsonNode before = recovery.path("before");
        JsonNode after = recovery.path("after");
        String operation = recovery.path("operation").asText();
        if ("unpersist".equals(operation) && !before.isObject() && !after.isObject()
                && recovery.path("mapping").isObject()) {
            before = recovery.path("mapping");
            ObjectNode legacyAfter = (ObjectNode) before.deepCopy();
            legacyAfter.put("persistent", false);
            after = legacyAfter;
        }
        if (operation.isBlank() || !stubId.equals(recovery.path("stubId").asText())
                || !before.isObject() || !after.isObject()
                || !stubId.equals(before.path("id").asText()) || !stubId.equals(after.path("id").asText())) {
            throw new McpOperationException("INVALID_UPSTREAM_RESPONSE",
                    "Stored persistent recovery mapping is invalid", false, Map.of("stubId", stubId));
        }
        return new PersistentTransaction(operation, stubId,
                (ObjectNode) before.deepCopy(), (ObjectNode) after.deepCopy());
    }

    private JsonNode getStubOrNull(String mockId, String stubId) {
        try {
            return getStub(mockId, stubId);
        } catch (McpOperationException e) {
            if (isNotFound(e)) {
                return null;
            }
            throw e;
        }
    }

    private JsonNode getStubAfterAmbiguousFailure(String mockId, String stubId, RuntimeException mutationFailure) {
        try {
            return getStubOrNull(mockId, stubId);
        } catch (RuntimeException verificationFailure) {
            mutationFailure.addSuppressed(verificationFailure);
            throw mutationFailure;
        }
    }

    private void writeRecoveryMapping(String mockId, PersistentTransaction transaction) {
        String stubId = transaction.stubId();
        String markerId = recoveryMappingId(stubId);
        ObjectNode marker = mapper.createObjectNode();
        marker.put("id", markerId);
        marker.put("uuid", markerId);
        marker.put("persistent", true);
        ObjectNode request = marker.putObject("request");
        request.put("method", "OPTIONS");
        request.put("urlPath", "/__mock_fleet_mcp/recovery/" + stubId);
        request.putObject("headers").putObject("X-Mock-Fleet-MCP-Recovery").put("equalTo", markerId);
        marker.putObject("response").put("status", 503);
        ObjectNode recovery = marker.putObject("metadata").putObject(RECOVERY_METADATA_KEY);
        recovery.put("operation", transaction.operation());
        recovery.put("stubId", stubId);
        recovery.set("before", transaction.before().deepCopy());
        recovery.set("after", transaction.after().deepCopy());
        RuntimeException creationFailure = null;
        try {
            sendJson(mockId, HttpMethod.POST, "/__admin/mappings", marker);
        } catch (RuntimeException failure) {
            creationFailure = failure;
        }
        PersistentTransaction stored;
        try {
            stored = loadRecoveryTransaction(mockId, stubId);
        } catch (RuntimeException verificationFailure) {
            if (creationFailure != null) {
                creationFailure.addSuppressed(verificationFailure);
                throw creationFailure;
            }
            throw verificationFailure;
        }
        if (!transaction.equals(stored)) {
            if (stored == null) {
                if (creationFailure != null) {
                    throw creationFailure;
                }
                throw new McpOperationException("WIREMOCK_ADMIN_ERROR",
                        "Persistent recovery marker creation could not be verified", true,
                        Map.of("stubId", stubId, "recoveryMarkerId", markerId));
            }
            throw persistentUpdateIncomplete(transaction, getStubOrNull(mockId, stubId),
                    "Recovery marker creation could not be verified");
        }
    }

    private void deleteRecoveryMapping(String mockId, String stubId) {
        deleteStub(mockId, recoveryMappingId(stubId));
        if (getStubOrNull(mockId, recoveryMappingId(stubId)) != null) {
            throw new McpOperationException("WIREMOCK_ADMIN_ERROR",
                    "Persistent recovery marker removal could not be verified", true,
                    Map.of("stubId", stubId, "recoveryMarkerId", recoveryMappingId(stubId)));
        }
    }

    private JsonNode persistentMutation(String mockId, String operation, String stubId,
            ObjectNode requestedBefore, ObjectNode requestedAfter, PersistentTransaction transaction) {
        if (transaction == null) {
            transaction = new PersistentTransaction(operation, stubId,
                    requestedBefore.deepCopy(), requestedAfter.deepCopy());
            writeRecoveryMapping(mockId, transaction);
        } else if (!operation.equals(transaction.operation()) || !transaction.after().equals(requestedAfter)) {
            throw persistentUpdateConflict(transaction, getStubOrNull(mockId, stubId));
        }

        JsonNode current = getStubOrNull(mockId, stubId);
        if (mappingMatches(current, transaction.after())) {
            deleteRecoveryMapping(mockId, stubId);
            return current;
        }
        if (current != null && !mappingMatches(current, transaction.before())) {
            throw persistentUpdateConflict(transaction, current);
        }
        if (current != null) {
            RuntimeException deleteFailure = null;
            try {
                deleteStub(mockId, stubId);
            } catch (RuntimeException failure) {
                deleteFailure = failure;
            }
            JsonNode afterDelete;
            try {
                afterDelete = getStubOrNull(mockId, stubId);
            } catch (RuntimeException verificationFailure) {
                McpOperationException incomplete = persistentUpdateIncomplete(transaction, null,
                        "Persistent mapping deletion could not be verified", "delete-verification",
                        verificationFailure);
                if (deleteFailure != null) {
                    incomplete.addSuppressed(deleteFailure);
                }
                incomplete.addSuppressed(verificationFailure);
                throw incomplete;
            }
            if (mappingMatches(afterDelete, transaction.after())) {
                deleteRecoveryMapping(mockId, stubId);
                return afterDelete;
            }
            if (afterDelete != null && !mappingMatches(afterDelete, transaction.before())) {
                McpOperationException conflict = persistentUpdateConflict(transaction, afterDelete);
                if (deleteFailure != null) {
                    conflict.addSuppressed(deleteFailure);
                }
                throw conflict;
            }
            if (afterDelete != null) {
                if (deleteFailure != null) {
                    throw deleteFailure;
                }
                throw persistentUpdateIncomplete(transaction, current,
                        "Persistent mapping deletion could not be verified");
            }
        }
        RuntimeException replacementFailure = null;
        try {
            sendJson(mockId, HttpMethod.POST, "/__admin/mappings", transaction.after());
        } catch (RuntimeException failure) {
            replacementFailure = failure;
        }
        JsonNode replacement;
        try {
            replacement = getStubOrNull(mockId, stubId);
        } catch (RuntimeException verificationFailure) {
            McpOperationException incomplete = persistentUpdateIncomplete(transaction, null,
                    "Neither persistent replacement nor rollback can be verified");
            incomplete.addSuppressed(verificationFailure);
            if (replacementFailure != null) {
                incomplete.addSuppressed(replacementFailure);
            }
            throw incomplete;
        }
        if (!mappingMatches(replacement, transaction.after())) {
            RuntimeException failure = replacementFailure == null
                    ? new McpOperationException("WIREMOCK_ADMIN_ERROR",
                            "Persistent replacement creation could not be verified", true,
                            reconciliationDetails(transaction, replacement))
                    : replacementFailure;
            throw restoreBeforeAndRethrow(mockId, transaction, replacement, failure);
        }
        deleteRecoveryMapping(mockId, stubId);
        return replacement;
    }

    private RuntimeException restoreBeforeAndRethrow(String mockId, PersistentTransaction transaction, JsonNode current,
            RuntimeException replacementFailure) {
        if (current != null && !mappingMatches(current, transaction.before())) {
            McpOperationException conflict = persistentUpdateConflict(transaction, current);
            conflict.addSuppressed(replacementFailure);
            throw conflict;
        }
        RuntimeException rollbackFailure = null;
        if (current == null) {
            try {
                sendJson(mockId, HttpMethod.POST, "/__admin/mappings", transaction.before());
            } catch (RuntimeException failure) {
                rollbackFailure = failure;
            }
        }
        JsonNode restored;
        try {
            restored = getStubOrNull(mockId, transaction.stubId());
        } catch (RuntimeException verificationFailure) {
            McpOperationException incomplete = persistentUpdateIncomplete(transaction, null,
                    "Persistent replacement and rollback could not be verified");
            incomplete.addSuppressed(replacementFailure);
            incomplete.addSuppressed(verificationFailure);
            if (rollbackFailure != null) {
                incomplete.addSuppressed(rollbackFailure);
            }
            throw incomplete;
        }
        if (!mappingMatches(restored, transaction.before())) {
            McpOperationException incomplete = persistentUpdateIncomplete(transaction, restored,
                    "Persistent replacement and rollback could not be verified");
            incomplete.addSuppressed(replacementFailure);
            if (rollbackFailure != null) {
                incomplete.addSuppressed(rollbackFailure);
            }
            throw incomplete;
        }
        deleteRecoveryMapping(mockId, transaction.stubId());
        throw replacementFailure;
    }

    private static boolean mappingMatches(JsonNode actual, JsonNode expected) {
        return actual != null && actual.equals(expected);
    }

    private McpOperationException persistentUpdateConflict(PersistentTransaction transaction, JsonNode current) {
        return new McpOperationException("PERSISTENT_UPDATE_CONFLICT",
                "Persistent mapping differs from both transaction states", false,
                reconciliationDetails(transaction, current));
    }

    private McpOperationException persistentUpdateIncomplete(PersistentTransaction transaction, JsonNode current,
            String message) {
        return new McpOperationException("PERSISTENT_UPDATE_INCOMPLETE", message, true, true,
                reconciliationDetails(transaction, current));
    }

    private McpOperationException persistentUpdateIncomplete(PersistentTransaction transaction, JsonNode current,
            String message, String stage, RuntimeException verificationFailure) {
        Map<String, Object> details = new LinkedHashMap<>(reconciliationDetails(transaction, current));
        details.put("stage", stage);
        details.put("verificationError", failureDetails(verificationFailure));
        return new McpOperationException("PERSISTENT_UPDATE_INCOMPLETE", message, true, true, details);
    }

    private Map<String, Object> failureDetails(RuntimeException failure) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("type", failure.getClass().getSimpleName());
        if (failure.getMessage() != null) {
            details.put("message", failure.getMessage());
        }
        if (failure instanceof McpOperationException operationFailure) {
            details.put("code", operationFailure.code());
            details.put("retryable", operationFailure.retryable());
            details.put("stateMayHaveChanged", operationFailure.stateMayHaveChanged());
        }
        return details;
    }

    private Map<String, Object> reconciliationDetails(PersistentTransaction transaction, JsonNode current) {
        return Map.of(
                "operation", transaction.operation(),
                "stubId", transaction.stubId(),
                "markerId", recoveryMappingId(transaction.stubId()),
                "recoveryMarkerId", recoveryMappingId(transaction.stubId()),
                "markerPreserved", true,
                "reconciliation", "manual-reconciliation-required",
                "before", transaction.before(),
                "after", transaction.after(),
                "current", current == null ? mapper.nullNode() : current);
    }

    private record PersistentTransaction(String operation, String stubId, ObjectNode before, ObjectNode after) {
    }

    private JsonNode withoutRecoveryMappings(JsonNode response) {
        JsonNode mappings = response.path("mappings");
        if (!response.isObject() || !mappings.isArray()) {
            return response;
        }
        ObjectNode filtered = (ObjectNode) response.deepCopy();
        var visible = mapper.createArrayNode();
        mappings.forEach(mapping -> {
            if (!isRecoveryMapping(mapping)) {
                visible.add(mapping);
            }
        });
        int removed = mappings.size() - visible.size();
        filtered.set("mappings", visible);
        if (removed > 0 && filtered.path("meta").isObject()) {
            ObjectNode meta = (ObjectNode) filtered.path("meta");
            meta.put("total", Math.max(0, meta.path("total").asInt(mappings.size()) - removed));
        }
        return filtered;
    }

    private static boolean isRecoveryMapping(JsonNode mapping) {
        return mapping.path("metadata").path(RECOVERY_METADATA_KEY).isObject();
    }

    private static String recoveryMappingId(String stubId) {
        // Keep the original namespace so retries can find markers written by earlier releases.
        return UUID.nameUUIDFromBytes(("mock-fleet-mcp:unpersist:" + stubId)
                .getBytes(StandardCharsets.UTF_8)).toString();
    }

    private JsonNode parseJson(TransportResponse response) {
        return parseJson(response, false);
    }

    private JsonNode parseJson(TransportResponse response, boolean stateMayHaveChanged) {
        if (response.body().length == 0) {
            return mapper.createObjectNode();
        }
        try {
            return mapper.readTree(response.body());
        } catch (Exception e) {
            throw new McpOperationException("INVALID_UPSTREAM_RESPONSE", "WireMock returned invalid JSON", false,
                    stateMayHaveChanged, Map.of("status", response.status()));
        }
    }

    private static String requireIdentifier(String value, String name) {
        if (value == null || !value.matches("[0-9A-Za-z-]{1,128}")) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return value;
    }

    private static void copyIfPresent(JsonNode source, ObjectNode target, String field) {
        if (source.hasNonNull(field)) {
            target.set(field, source.get(field));
        }
    }

    private static boolean isMissingVersionEndpoint(McpOperationException error) {
        return "WIREMOCK_ADMIN_ERROR".equals(error.code()) && Integer.valueOf(404).equals(error.details().get("status"));
    }

    private static boolean isNotFound(McpOperationException error) {
        return "WIREMOCK_ADMIN_ERROR".equals(error.code()) && Integer.valueOf(404).equals(error.details().get("status"));
    }

    private static boolean isStaleFileHandle(McpOperationException error) {
        return "WIREMOCK_ADMIN_ERROR".equals(error.code())
                && Integer.valueOf(500).equals(error.details().get("status"))
                && String.valueOf(error.details().get("body")).contains("Stale file handle");
    }

    record RecorderCleanupPolicy(int polls, int requiredStableScans, long intervalMillis) {
        RecorderCleanupPolicy {
            if (polls < 1 || requiredStableScans < 0 || requiredStableScans > polls || intervalMillis < 0) {
                throw new IllegalArgumentException("Invalid recorder cleanup policy");
            }
        }

        static RecorderCleanupPolicy production() {
            return new RecorderCleanupPolicy(26, 3, 200);
        }
    }

    record BodyFileReadPolicy(int attempts, long intervalMillis) {
        BodyFileReadPolicy {
            if (attempts < 1 || intervalMillis < 0) {
                throw new IllegalArgumentException("Invalid body-file read policy");
            }
        }

        static BodyFileReadPolicy production() {
            return new BodyFileReadPolicy(26, 200);
        }
    }
}
