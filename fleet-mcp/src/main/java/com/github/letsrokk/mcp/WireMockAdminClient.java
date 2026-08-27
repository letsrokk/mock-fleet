package com.github.letsrokk.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.http.HttpMethod;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

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
        this.transport = transport;
        this.mapper = mapper;
        this.maxPayloadBytes = maxPayloadBytes;
        this.sanitizer = new JsonSanitizer(mapper, sensitiveHeaders, metrics);
        this.configuredVersion = configuredVersion;
    }

    public WireMockVersion version(String mockId) {
        try {
            JsonNode response = getJson(mockId, "/__admin/version");
            String version = response.path("version").asText(response.asText());
            return WireMockVersion.parse(version);
        } catch (McpOperationException e) {
            if (!isMissingVersionEndpoint(e) || configuredVersion == null || configuredVersion.minor() != 0) {
                throw e;
            }
            JsonNode probe = getJson(mockId, "/__admin/mappings?limit=1&offset=0");
            if (!probe.path("mappings").isArray()) {
                throw new McpOperationException("INVALID_UPSTREAM_RESPONSE",
                        "Legacy WireMock runtime probe returned an unexpected response", false,
                        Map.of("configuredVersion", configuredVersion.toString()));
            }
            return configuredVersion;
        }
    }

    public JsonNode listStubs(String mockId, int limit, int offset) {
        ArrayNode page = mapper.createArrayNode();
        int[] visibleTotal = { 0 };
        scanMappings(mockId, mapping -> {
            if (isRecoveryMapping(mapping)) {
                return;
            }
            int visibleIndex = visibleTotal[0]++;
            if (visibleIndex >= offset && page.size() < limit) {
                page.add(mapping);
            }
        });
        ObjectNode response = mapper.createObjectNode();
        response.set("mappings", page);
        ObjectNode meta = response.putObject("meta");
        meta.put("total", visibleTotal[0]);
        meta.put("limit", limit);
        meta.put("offset", offset);
        return response;
    }

    public JsonNode listUnmatchedStubs(String mockId) {
        return withoutRecoveryMappings(getJson(mockId, "/__admin/mappings/unmatched"));
    }

    public JsonNode getStub(String mockId, String stubId) {
        return getJson(mockId, "/__admin/mappings/" + requireIdentifier(stubId, "stubId"));
    }

    public JsonNode createStub(String mockId, ObjectNode mapping) {
        ObjectNode payload = serverManagedCopy(mapping);
        payload.put("persistent", false);
        return sendJson(mockId, HttpMethod.POST, "/__admin/mappings", payload);
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
            return sendJson(mockId, HttpMethod.PUT, "/__admin/mappings/" + id, payload);
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
            send(mockId, new TransportRequest(HttpMethod.DELETE,
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

    public JsonNode countRequests(String mockId, JsonNode requestPattern) {
        return sendJson(mockId, HttpMethod.POST, "/__admin/requests/count", requestPattern);
    }

    public JsonNode listUnmatchedRequests(String mockId) {
        return sanitizer.redactHeaders(getJson(mockId, "/__admin/requests/unmatched"));
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

    public void resetRequestJournal(String mockId) {
        send(mockId, new TransportRequest(HttpMethod.DELETE, "/__admin/requests", Map.of(), new byte[0]));
    }

    public JsonNode startRecording(String mockId, ObjectNode recordingSpec) {
        ObjectNode payload = recordingSpec.deepCopy();
        payload.put("persist", false);
        payload.put("outputFormat", "IDS");
        return sendJson(mockId, HttpMethod.POST, "/__admin/recordings/start", payload);
    }

    public JsonNode recordingStatus(String mockId) {
        return getJson(mockId, "/__admin/recordings/status");
    }

    public JsonNode stopRecording(String mockId) {
        Set<String> baseline = mappingIds(mockId);
        JsonNode result = sendWithoutBodyJson(mockId, HttpMethod.POST, "/__admin/recordings/stop");
        return sanitizeRecorderCandidates(mockId, result, baseline);
    }

    public JsonNode snapshotRequests(String mockId, ObjectNode snapshotSpec) {
        Set<String> baseline = mappingIds(mockId);
        ObjectNode payload = snapshotSpec.deepCopy();
        payload.put("persist", false);
        payload.put("outputFormat", "IDS");
        return sanitizeRecorderCandidates(mockId,
                sendJson(mockId, HttpMethod.POST, "/__admin/recordings/snapshot", payload), baseline);
    }

    public JsonNode listBodyFiles(String mockId) {
        return getJson(mockId, "/__admin/files");
    }

    public TransportResponse getBodyFile(String mockId, String fileName) {
        return send(mockId, new TransportRequest(HttpMethod.GET,
                "/__admin/files/" + BodyFileName.toUrlPath(fileName), Map.of(), new byte[0]));
    }

    public void putBodyFile(String mockId, String fileName, byte[] content, String contentType) {
        Map<String, List<String>> headers = Map.of("content-type", List.of(
                contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType));
        send(mockId, new TransportRequest(HttpMethod.PUT,
                "/__admin/files/" + BodyFileName.toUrlPath(fileName), headers, content));
    }

    public void deleteBodyFile(String mockId, String fileName) {
        send(mockId, new TransportRequest(HttpMethod.DELETE,
                "/__admin/files/" + BodyFileName.toUrlPath(fileName), Map.of(), new byte[0]));
    }

    public JsonNode listScenarios(String mockId) {
        return getJson(mockId, "/__admin/scenarios");
    }

    public void resetScenarios(String mockId) {
        send(mockId, new TransportRequest(HttpMethod.POST, "/__admin/scenarios/reset", Map.of(), new byte[0]));
    }

    public TransportResponse sendRequest(String mockId, HttpMethod method, String requestTarget,
            Map<String, List<String>> headers, byte[] body) {
        return exchange(mockId, new TransportRequest(method,
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

    private JsonNode sendWithoutBodyJson(String mockId, HttpMethod method, String endpoint) {
        return parseJson(send(mockId, new TransportRequest(method, endpoint,
                Map.of("accept", List.of("application/json")), new byte[0])));
    }

    private JsonNode sanitizeRecorderCandidates(String mockId, JsonNode result, Set<String> baseline) {
        JsonNode ids = result.path("ids");
        Set<String> candidateIds = new LinkedHashSet<>();
        McpOperationException invalidIds = ids.isArray() ? null : invalidRecorderCandidateIds();
        if (ids.isArray()) {
            for (JsonNode id : ids) {
                if (!id.isTextual()) {
                    invalidIds = invalidRecorderCandidateIds();
                    continue;
                }
                try {
                    candidateIds.add(requireIdentifier(id.asText(), "recorded stub ID"));
                } catch (IllegalArgumentException e) {
                    invalidIds = invalidRecorderCandidateIds();
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
        List<String> cleanupFailedIds = new ArrayList<>();
        for (String id : candidateIds) {
            if (!removeOrNeutralizeRecorderCandidate(mockId, id)) {
                cleanupFailedIds.add(id);
            }
        }
        if (cleanupFailedIds.isEmpty()) {
            return originalFailure;
        }
        McpOperationException cleanupFailure = new McpOperationException("RECORDER_CLEANUP_FAILED",
                "Recorder candidates could not all be removed or neutralized", true,
                Map.of("candidateIds", candidateIds, "cleanupFailedIds", cleanupFailedIds));
        cleanupFailure.addSuppressed(originalFailure);
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

    private static McpOperationException invalidRecorderCandidateIds() {
        return new McpOperationException("INVALID_UPSTREAM_RESPONSE",
                "WireMock recorder result did not contain only valid candidate IDs", false, Map.of());
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
        while (true) {
            MappingPage page = mappingPage(mockId, rawOffset, pageSize);
            pageSize = page.limit();
            JsonNode mappings = page.response().path("mappings");
            if (!mappings.isArray()) {
                throw new McpOperationException("INVALID_UPSTREAM_RESPONSE",
                        "WireMock mapping page did not contain mappings", false, Map.of("offset", rawOffset));
            }
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

    private TransportResponse send(String mockId, TransportRequest request) {
        TransportResponse response = exchange(mockId, request);
        if (response.status() < 200 || response.status() >= 300) {
            throw new McpOperationException("WIREMOCK_ADMIN_ERROR", "WireMock returned HTTP " + response.status(),
                    response.status() >= 500, Map.of("status", response.status(), "body", response.bodyAsString()));
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
            deleteStub(mockId, stubId);
            if (getStubOrNull(mockId, stubId) != null) {
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
        return new McpOperationException("PERSISTENT_UPDATE_INCOMPLETE", message, true,
                reconciliationDetails(transaction, current));
    }

    private Map<String, Object> reconciliationDetails(PersistentTransaction transaction, JsonNode current) {
        return Map.of(
                "operation", transaction.operation(),
                "stubId", transaction.stubId(),
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
        if (response.body().length == 0) {
            return mapper.createObjectNode();
        }
        try {
            return mapper.readTree(response.body());
        } catch (Exception e) {
            throw new McpOperationException("INVALID_UPSTREAM_RESPONSE", "WireMock returned invalid JSON", false,
                    Map.of("status", response.status()));
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
}
