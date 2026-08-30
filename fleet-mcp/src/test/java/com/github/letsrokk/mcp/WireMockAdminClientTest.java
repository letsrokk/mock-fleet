package com.github.letsrokk.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.http.HttpMethod;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WireMockAdminClientTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final RecordingTransport transport = new RecordingTransport();
    private final WireMockAdminClient client = new WireMockAdminClient(
            transport, mapper, 1024 * 1024, Set.of("authorization", "cookie", "set-cookie"), null,
            67_108_864L, 100_000, new WireMockAdminClient.RecorderCleanupPolicy(1, 0, 0),
            new WireMockAdminClient.BodyFileReadPolicy(3, 0));

    @Test
    void uploadsBodyFilesAsOpaqueBytes() {
        transport.respond(201, "");

        client.putBodyFile("orders", "payload.bin", new byte[] {0, 1});

        assertEquals(List.of("application/octet-stream"), transport.last().headers().get("content-type"));
    }

    @Test
    void retriesBodyFileReadAfterStaleS3FileHandle() {
        transport.respond(500, "java.io.IOException: Stale file handle");
        transport.respond(200, "hello");

        TransportResponse response = client.getBodyFile("orders", "payload.txt");

        assertEquals("hello", response.bodyAsString());
        assertEquals(2, transport.requestCount());
        assertEquals("/__admin/files/payload.txt", transport.requestAt(0).target());
        assertEquals("/__admin/files/payload.txt", transport.requestAt(1).target());
    }

    @Test
    void retriesBodyFileReadAcrossAnS3RemountWindow() {
        WireMockAdminClient polling = new WireMockAdminClient(
                transport, mapper, 1024 * 1024, Set.of("authorization"), null,
                67_108_864L, 100_000, new WireMockAdminClient.RecorderCleanupPolicy(1, 0, 0),
                new WireMockAdminClient.BodyFileReadPolicy(5, 0));
        transport.respond(500, "java.io.IOException: Stale file handle");
        transport.respond(500, "java.io.IOException: Stale file handle");
        transport.respond(500, "java.io.IOException: Stale file handle");
        transport.respond(500, "java.io.IOException: Stale file handle");
        transport.respond(200, "hello");

        TransportResponse response = polling.getBodyFile("orders", "payload.txt");

        assertEquals("hello", response.bodyAsString());
        assertEquals(5, transport.requestCount());
    }

    @Test
    void doesNotRetryUnrelatedBodyFileServerErrors() {
        transport.respond(500, "unexpected failure");

        McpOperationException error = assertThrows(McpOperationException.class,
                () -> client.getBodyFile("orders", "payload.txt"));

        assertEquals("WIREMOCK_ADMIN_ERROR", error.code());
        assertEquals(1, transport.requestCount());
    }

    @Test
    void boundsStaleS3FileHandleRetries() {
        transport.respond(500, "java.io.IOException: Stale file handle");
        transport.respond(500, "java.io.IOException: Stale file handle");
        transport.respond(500, "java.io.IOException: Stale file handle");

        McpOperationException error = assertThrows(McpOperationException.class,
                () -> client.getBodyFile("orders", "payload.txt"));

        assertEquals("WIREMOCK_ADMIN_ERROR", error.code());
        assertTrue(error.retryable());
        assertEquals(3, transport.requestCount());
    }

    @Test
    void createStubDropsServerManagedFieldsAndAlwaysCreatesTemporaryMapping() throws Exception {
        transport.respond(201, "{\"id\":\"server-id\",\"persistent\":false}");
        ObjectNode input = (ObjectNode) mapper.readTree("""
                {"id":"client-id","uuid":"client-uuid","persistent":true,
                 "request":{"method":"GET","url":"/orders"},"response":{"status":200}}
                """);

        client.createStub("orders", input);

        ObjectNode sent = transport.lastJson(mapper);
        assertFalse(sent.has("id"));
        assertFalse(sent.has("uuid"));
        assertFalse(sent.get("persistent").booleanValue());
        assertEquals(HttpMethod.POST, transport.last().method());
        assertEquals("/__admin/mappings", transport.last().target());
    }

    @Test
    void createStubMarksInvalidSuccessResponseAsPotentiallyChanged() {
        transport.respond(201, "not-json");

        McpOperationException error = assertThrows(McpOperationException.class,
                () -> client.createStub("orders", mapper.createObjectNode()));

        assertEquals("INVALID_UPSTREAM_RESPONSE", error.code());
        assertTrue(error.stateMayHaveChanged());
        assertEquals("orders", error.details().get("mockId"));
    }

    @ParameterizedTest
    @ValueSource(strings = { "", "null", "[]", "{}", "{\"request\":{}}", "{\"id\":\"bad/id\"}" })
    void createStubRejectsSuccessResponsesWithoutAUsableMappingId(String body) {
        transport.respond(201, body);

        McpOperationException error = assertThrows(McpOperationException.class,
                () -> client.createStub("orders", mapper.createObjectNode()));

        assertEquals("INVALID_UPSTREAM_RESPONSE", error.code());
        assertTrue(error.stateMayHaveChanged());
        assertEquals("orders", error.details().get("mockId"));
    }

    @Test
    void mutationRequestRejectedBeforeDispatchDoesNotReportChangedState() {
        WireMockAdminClient limited = new WireMockAdminClient(
                transport, mapper, 1, Set.of("authorization"));

        McpOperationException error = assertThrows(McpOperationException.class,
                () -> limited.putBodyFile("orders", "payload.bin", new byte[] {0, 1}));

        assertEquals("RESULT_TOO_LARGE", error.code());
        assertFalse(error.stateMayHaveChanged());
        assertEquals(0, transport.requestCount());
    }

    @Test
    void jsonMutationRejectedBeforeDispatchDoesNotReportChangedState() {
        WireMockAdminClient limited = new WireMockAdminClient(
                transport, mapper, 1, Set.of("authorization"));

        McpOperationException error = assertThrows(McpOperationException.class,
                () -> limited.createStub("orders", mapper.createObjectNode()));

        assertEquals("RESULT_TOO_LARGE", error.code());
        assertFalse(error.stateMayHaveChanged());
        assertEquals(0, transport.requestCount());
    }

    @Test
    void authoritativeClientErrorDoesNotReportChangedMutationState() {
        transport.respond(400, "invalid mapping");

        McpOperationException error = assertThrows(McpOperationException.class,
                () -> client.createStub("orders", mapper.createObjectNode()));

        assertEquals("WIREMOCK_ADMIN_ERROR", error.code());
        assertFalse(error.stateMayHaveChanged());
    }

    @Test
    void resetRequestJournalMarksServerFailureAsPotentiallyChanged() {
        transport.respond(500, "reset response lost");

        McpOperationException error = assertThrows(McpOperationException.class,
                () -> client.resetRequestJournal("orders"));

        assertEquals("WIREMOCK_ADMIN_ERROR", error.code());
        assertTrue(error.stateMayHaveChanged());
    }

    @Test
    void updatePersistentStubUsesVerifiedRecoveryTransaction() throws Exception {
        String before = """
                {"id":"server-id","uuid":"server-id","persistent":true,
                 "request":{"method":"GET","url":"/orders"},"response":{"status":200}}
                """;
        String after = """
                {"id":"server-id","uuid":"server-id","persistent":true,
                 "request":{"method":"POST","url":"/orders"},"response":{"status":202}}
                """;
        String marker = recoveryMarker("update", before, after);
        transport.respond(404, "");
        transport.respond(200, before);
        transport.respond(201, marker);
        transport.respond(200, marker);
        transport.respond(200, before);
        transport.respond(204, "");
        transport.respond(404, "");
        transport.respond(201, after);
        transport.respond(200, after);
        transport.respond(204, "");
        transport.respond(404, "");
        ObjectNode input = (ObjectNode) mapper.readTree("""
                {"id":"client-id","persistent":false,
                 "request":{"method":"POST","url":"/orders"},"response":{"status":202}}
                """);

        JsonNode result = client.updateStub("orders", "server-id", input);

        ObjectNode recovery = transport.jsonAt(mapper, 2).withObject("/metadata/_mockFleetMcpRecovery");
        assertEquals("update", recovery.path("operation").asText());
        assertEquals("server-id", recovery.path("stubId").asText());
        assertEquals("GET", recovery.path("before").path("request").path("method").asText());
        assertEquals("POST", recovery.path("after").path("request").path("method").asText());
        assertEquals(HttpMethod.DELETE, transport.requestAt(5).method());
        assertEquals(HttpMethod.POST, transport.requestAt(7).method());
        assertEquals(HttpMethod.DELETE, transport.requestAt(9).method());
        assertEquals("POST", result.path("request").path("method").asText());
        assertEquals(11, transport.requestCount());
    }

    @Test
    void persistentUpdateRestoresAndVerifiesBeforeWhenReplacementFails() throws Exception {
        String before = """
                {"id":"server-id","uuid":"server-id","persistent":true,
                 "request":{"method":"GET","url":"/orders"},"response":{"status":200}}
                """;
        String after = """
                {"id":"server-id","uuid":"server-id","persistent":true,
                 "request":{"method":"POST","url":"/orders"},"response":{"status":202}}
                """;
        String marker = recoveryMarker("update", before, after);
        transport.respond(404, "");
        transport.respond(200, before);
        transport.respond(201, marker);
        transport.respond(200, marker);
        transport.respond(200, before);
        transport.respond(204, "");
        transport.respond(404, "");
        transport.respond(500, "replacement failed");
        transport.respond(404, "");
        transport.respond(201, before);
        transport.respond(200, before);
        transport.respond(204, "");
        transport.respond(404, "");
        ObjectNode input = (ObjectNode) mapper.readTree("""
                {"request":{"method":"POST","url":"/orders"},"response":{"status":202}}
                """);

        McpOperationException error = assertThrows(McpOperationException.class,
                () -> client.updateStub("orders", "server-id", input));

        assertEquals("WIREMOCK_ADMIN_ERROR", error.code());
        assertEquals(HttpMethod.POST, transport.requestAt(9).method());
        assertEquals("GET", transport.jsonAt(mapper, 9).path("request").path("method").asText());
        assertEquals(HttpMethod.DELETE, transport.requestAt(11).method());
        assertEquals(13, transport.requestCount());
    }

    @Test
    void persistTransitionUsesVerifiedRecoveryTransactionWithoutSavingEveryStub() throws Exception {
        String before = """
                {"id":"server-id","uuid":"server-id","persistent":false,
                 "request":{"method":"GET","url":"/orders"},"response":{"status":200}}
                """;
        String after = """
                {"id":"server-id","uuid":"server-id","persistent":true,
                 "request":{"method":"GET","url":"/orders"},"response":{"status":200}}
                """;
        enqueueSuccessfulPersistentReplacement("persist", before, after);

        JsonNode result = client.setPersistent("orders", "server-id", true);

        assertTrue(result.path("persistent").asBoolean());
        assertEquals(HttpMethod.POST, transport.requestAt(2).method());
        assertEquals(HttpMethod.DELETE, transport.requestAt(5).method());
        assertEquals(HttpMethod.POST, transport.requestAt(7).method());
        assertEquals(11, transport.requestCount());
    }

    @Test
    void unpersistTransitionRemovesTheBackingFileBeforeRecreatingTheTemporaryStub() throws Exception {
        String before = """
                {"id":"server-id","uuid":"server-id","persistent":true,
                 "request":{"method":"GET","url":"/orders"},"response":{"status":200}}
                """;
        String after = """
                {"id":"server-id","uuid":"server-id","persistent":false,
                 "request":{"method":"GET","url":"/orders"},"response":{"status":200}}
                """;
        enqueueSuccessfulPersistentReplacement("unpersist", before, after);

        client.setPersistent("orders", "server-id", false);

        assertEquals(11, transport.requestCount());
        assertEquals(HttpMethod.POST, transport.requestAt(2).method());
        assertEquals("/__admin/mappings", transport.requestAt(2).target());
        assertEquals("server-id", transport.jsonAt(mapper, 2).path("metadata")
                .path("_mockFleetMcpRecovery").path("before").path("id").asText());
        assertEquals(HttpMethod.DELETE, transport.requestAt(5).method());
        assertEquals(HttpMethod.POST, transport.requestAt(7).method());
        ObjectNode recreated = transport.jsonAt(mapper, 7);
        assertEquals("server-id", recreated.path("id").asText());
        assertFalse(recreated.path("persistent").asBoolean());
        assertEquals(HttpMethod.DELETE, transport.requestAt(9).method());
        assertEquals(transport.requestAt(0).target(), transport.requestAt(10).target());
    }

    @Test
    void unpersistResumesFromGeneralizedRecoveryMarkerWhenPersistentStubIsMissing() throws Exception {
        String before = """
                {"id":"server-id","uuid":"server-id","persistent":true,
                 "request":{"method":"GET","url":"/orders"},"response":{"status":200}}
                """;
        String after = """
                {"id":"server-id","uuid":"server-id","persistent":false,
                 "request":{"method":"GET","url":"/orders"},"response":{"status":200}}
                """;
        transport.respond(200, recoveryMarker("unpersist", before, after));
        transport.respond(404, "");
        transport.respond(201, after);
        transport.respond(200, after);
        transport.respond(204, "");
        transport.respond(404, "");

        JsonNode result = client.setPersistent("orders", "server-id", false);

        assertFalse(result.path("persistent").asBoolean(true));
        assertEquals(HttpMethod.GET, transport.requestAt(0).method());
        assertTrue(transport.requestAt(0).target().startsWith("/__admin/mappings/"));
        assertEquals(HttpMethod.POST, transport.requestAt(2).method());
        assertEquals(HttpMethod.DELETE, transport.requestAt(4).method());
        assertEquals(6, transport.requestCount());
    }

    @Test
    void unpersistResumesFromLegacyRecoveryMarkerWithoutMigratingSavedMapping() throws Exception {
        String before = """
                {"id":"server-id","uuid":"server-id","persistent":true,
                 "request":{"method":"GET","url":"/orders"},"response":{"status":200}}
                """;
        String after = """
                {"id":"server-id","uuid":"server-id","persistent":false,
                 "request":{"method":"GET","url":"/orders"},"response":{"status":200}}
                """;
        transport.respond(200, legacyRecoveryMarker(before));
        transport.respond(404, "");
        transport.respond(201, after);
        transport.respond(200, after);
        transport.respond(204, "");
        transport.respond(404, "");

        JsonNode result = client.setPersistent("orders", "server-id", false);

        assertFalse(result.path("persistent").asBoolean(true));
        assertEquals(6, transport.requestCount());
    }

    @Test
    void persistentUpdatePreservesMarkerAndRejectsUnrecognizedCurrentMapping() throws Exception {
        String before = """
                {"id":"server-id","uuid":"server-id","persistent":true,
                 "request":{"method":"GET","url":"/orders"},"response":{"status":200}}
                """;
        String after = """
                {"id":"server-id","uuid":"server-id","persistent":true,
                 "request":{"method":"POST","url":"/orders"},"response":{"status":202}}
                """;
        transport.respond(200, recoveryMarker("update", before, after));
        transport.respond(200, """
                {"id":"server-id","uuid":"server-id","persistent":true,
                 "request":{"method":"PATCH","url":"/orders"},"response":{"status":204}}
                """);
        ObjectNode input = (ObjectNode) mapper.readTree("""
                {"request":{"method":"POST","url":"/orders"},"response":{"status":202}}
                """);

        McpOperationException error = assertThrows(McpOperationException.class,
                () -> client.updateStub("orders", "server-id", input));

        assertEquals("PERSISTENT_UPDATE_CONFLICT", error.code());
        assertFalse(error.retryable());
        assertEquals("PATCH", ((JsonNode) error.details().get("current"))
                .path("request").path("method").asText());
        assertEquals(2, transport.requestCount());
    }

    @Test
    void persistentUpdateKeepsMarkerAndReportsReconciliationWhenReplacementAndRollbackAreUnverifiable()
            throws Exception {
        String before = """
                {"id":"server-id","uuid":"server-id","persistent":true,
                 "request":{"method":"GET","url":"/orders"},"response":{"status":200}}
                """;
        String after = """
                {"id":"server-id","uuid":"server-id","persistent":true,
                 "request":{"method":"POST","url":"/orders"},"response":{"status":202}}
                """;
        String marker = recoveryMarker("update", before, after);
        transport.respond(404, "");
        transport.respond(200, before);
        transport.respond(201, marker);
        transport.respond(200, marker);
        transport.respond(200, before);
        transport.respond(204, "");
        transport.respond(404, "");
        transport.respond(500, "replacement failed");
        transport.respond(404, "");
        transport.respond(500, "rollback failed");
        transport.respond(500, "rollback lookup failed");
        ObjectNode input = (ObjectNode) mapper.readTree("""
                {"request":{"method":"POST","url":"/orders"},"response":{"status":202}}
                """);

        McpOperationException error = assertThrows(McpOperationException.class,
                () -> client.updateStub("orders", "server-id", input));

        assertEquals("PERSISTENT_UPDATE_INCOMPLETE", error.code());
        assertTrue(error.retryable());
        assertTrue(error.stateMayHaveChanged());
        assertEquals(true, error.details().get("markerPreserved"));
        assertEquals("manual-reconciliation-required", error.details().get("reconciliation"));
        assertEquals("update", error.details().get("operation"));
        assertEquals(11, transport.requestCount());
    }

    @Test
    void persistentUpdateReturnsConflictWhenReplacementReadBackFindsUnrecognizedMapping() throws Exception {
        String before = """
                {"id":"server-id","uuid":"server-id","persistent":true,
                 "request":{"method":"GET","url":"/orders"},"response":{"status":200}}
                """;
        String after = """
                {"id":"server-id","uuid":"server-id","persistent":true,
                 "request":{"method":"POST","url":"/orders"},"response":{"status":202}}
                """;
        String marker = recoveryMarker("update", before, after);
        transport.respond(404, "");
        transport.respond(200, before);
        transport.respond(201, marker);
        transport.respond(200, marker);
        transport.respond(200, before);
        transport.respond(204, "");
        transport.respond(404, "");
        transport.respond(500, "replacement failed");
        transport.respond(200, """
                {"id":"server-id","uuid":"server-id","persistent":true,
                 "request":{"method":"PATCH","url":"/orders"},"response":{"status":204}}
                """);
        ObjectNode input = (ObjectNode) mapper.readTree("""
                {"request":{"method":"POST","url":"/orders"},"response":{"status":202}}
                """);

        McpOperationException error = assertThrows(McpOperationException.class,
                () -> client.updateStub("orders", "server-id", input));

        assertEquals("PERSISTENT_UPDATE_CONFLICT", error.code());
        assertFalse(error.retryable());
        assertEquals(9, transport.requestCount());
    }

    @Test
    void persistentUpdateReturnsConflictWhenSuccessfulDeleteReadBackFindsUnrecognizedMapping() throws Exception {
        String before = """
                {"id":"server-id","uuid":"server-id","persistent":true,
                 "request":{"method":"GET","url":"/orders"},"response":{"status":200}}
                """;
        String after = """
                {"id":"server-id","uuid":"server-id","persistent":true,
                 "request":{"method":"POST","url":"/orders"},"response":{"status":202}}
                """;
        String unknown = """
                {"id":"server-id","uuid":"server-id","persistent":true,
                 "request":{"method":"PATCH","url":"/orders"},"response":{"status":204}}
                """;
        String marker = recoveryMarker("update", before, after);
        transport.respond(404, "");
        transport.respond(200, before);
        transport.respond(201, marker);
        transport.respond(200, marker);
        transport.respond(200, before);
        transport.respond(204, "");
        transport.respond(200, unknown);
        ObjectNode input = (ObjectNode) mapper.readTree("""
                {"request":{"method":"POST","url":"/orders"},"response":{"status":202}}
                """);

        McpOperationException error = assertThrows(McpOperationException.class,
                () -> client.updateStub("orders", "server-id", input));

        assertEquals("PERSISTENT_UPDATE_CONFLICT", error.code());
        assertFalse(error.retryable());
        assertEquals("PATCH", ((JsonNode) error.details().get("current"))
                .path("request").path("method").asText());
        assertEquals(7, transport.requestCount());
    }

    @Test
    void persistentUpdateReturnsConflictWhenAmbiguousDeleteReadBackFindsUnrecognizedMapping() throws Exception {
        String before = """
                {"id":"server-id","uuid":"server-id","persistent":true,
                 "request":{"method":"GET","url":"/orders"},"response":{"status":200}}
                """;
        String after = """
                {"id":"server-id","uuid":"server-id","persistent":true,
                 "request":{"method":"POST","url":"/orders"},"response":{"status":202}}
                """;
        String unknown = """
                {"id":"server-id","uuid":"server-id","persistent":true,
                 "request":{"method":"PATCH","url":"/orders"},"response":{"status":204}}
                """;
        String marker = recoveryMarker("update", before, after);
        transport.respond(404, "");
        transport.respond(200, before);
        transport.respond(201, marker);
        transport.respond(200, marker);
        transport.respond(200, before);
        transport.respond(500, "delete response lost");
        transport.respond(200, unknown);
        transport.respond(200, unknown);
        ObjectNode input = (ObjectNode) mapper.readTree("""
                {"request":{"method":"POST","url":"/orders"},"response":{"status":202}}
                """);

        McpOperationException error = assertThrows(McpOperationException.class,
                () -> client.updateStub("orders", "server-id", input));

        assertEquals("PERSISTENT_UPDATE_CONFLICT", error.code());
        assertFalse(error.retryable());
        assertEquals("PATCH", ((JsonNode) error.details().get("current"))
                .path("request").path("method").asText());
        assertEquals(8, transport.requestCount());
    }

    @ParameterizedTest
    @ValueSource(ints = { 204, 500 })
    void persistentUpdateReportsIncompleteWhenDeleteReadBackCannotDetermineCurrentState(int deleteStatus)
            throws Exception {
        String before = """
                {"id":"server-id","uuid":"server-id","persistent":true,
                 "request":{"method":"GET","url":"/orders"},"response":{"status":200}}
                """;
        String after = """
                {"id":"server-id","uuid":"server-id","persistent":true,
                 "request":{"method":"POST","url":"/orders"},"response":{"status":202}}
                """;
        String marker = recoveryMarker("update", before, after);
        transport.respond(404, "");
        transport.respond(200, before);
        transport.respond(201, marker);
        transport.respond(200, marker);
        transport.respond(200, before);
        transport.respond(deleteStatus, deleteStatus == 204 ? "" : "delete response lost");
        if (deleteStatus != 204) {
            transport.respond(500, "delete helper read-back failed");
        }
        transport.respond(500, "delete read-back failed");
        ObjectNode input = (ObjectNode) mapper.readTree("""
                {"request":{"method":"POST","url":"/orders"},"response":{"status":202}}
                """);

        McpOperationException error = assertThrows(McpOperationException.class,
                () -> client.updateStub("orders", "server-id", input));

        assertEquals("PERSISTENT_UPDATE_INCOMPLETE", error.code());
        assertTrue(error.retryable());
        assertTrue(error.stateMayHaveChanged());
        assertEquals("update", error.details().get("operation"));
        assertEquals("server-id", error.details().get("stubId"));
        assertTrue(((String) error.details().get("markerId")).length() > 10);
        assertEquals(true, error.details().get("markerPreserved"));
        assertEquals("delete-verification", error.details().get("stage"));
        assertEquals(mapper.readTree(before), error.details().get("before"));
        assertEquals(mapper.readTree(after), error.details().get("after"));
        assertEquals("WIREMOCK_ADMIN_ERROR",
                ((Map<?, ?>) error.details().get("verificationError")).get("code"));
        assertEquals(deleteStatus == 204 ? 7 : 8, transport.requestCount());
    }

    @Test
    void temporaryUpdateReturnsVerifiedMappingAfterAmbiguousFailure() throws Exception {
        String before = """
                {"id":"server-id","uuid":"server-id","persistent":false,
                 "request":{"method":"GET","url":"/orders"},"response":{"status":200}}
                """;
        String after = """
                {"id":"server-id","uuid":"server-id","persistent":false,
                 "request":{"method":"POST","url":"/orders"},"response":{"status":202}}
                """;
        transport.respond(404, "");
        transport.respond(200, before);
        transport.respond(500, "response lost");
        transport.respond(200, after);
        ObjectNode input = (ObjectNode) mapper.readTree("""
                {"request":{"method":"POST","url":"/orders"},"response":{"status":202}}
                """);

        JsonNode result = client.updateStub("orders", "server-id", input);

        assertEquals("POST", result.path("request").path("method").asText());
        assertEquals(HttpMethod.GET, transport.requestAt(3).method());
        assertEquals(4, transport.requestCount());
    }

    @Test
    void temporaryUpdateRejectsMalformedSuccessResponseAfterReadBack() throws Exception {
        String before = """
                {"id":"server-id","uuid":"server-id","persistent":false,
                 "request":{"method":"GET","url":"/orders"},"response":{"status":200}}
                """;
        transport.respond(404, "");
        transport.respond(200, before);
        transport.respond(200, "{}");
        transport.respond(200, before);
        ObjectNode input = (ObjectNode) mapper.readTree("""
                {"request":{"method":"POST","url":"/orders"},"response":{"status":202}}
                """);

        McpOperationException error = assertThrows(McpOperationException.class,
                () -> client.updateStub("orders", "server-id", input));

        assertEquals("INVALID_UPSTREAM_RESPONSE", error.code());
        assertTrue(error.stateMayHaveChanged());
        assertEquals("orders", error.details().get("mockId"));
        assertEquals(4, transport.requestCount());
    }

    @Test
    void deleteReturnsNormallyWhenReadBackShowsAmbiguousFailureApplied() {
        transport.respond(500, "response lost");
        transport.respond(404, "");

        client.deleteStub("orders", "server-id");

        assertEquals(HttpMethod.DELETE, transport.requestAt(0).method());
        assertEquals(HttpMethod.GET, transport.requestAt(1).method());
        assertEquals(2, transport.requestCount());
    }

    @Test
    void persistReturnsVerifiedAfterStateWhenReplacementResponseIsLost() throws Exception {
        String before = """
                {"id":"server-id","uuid":"server-id","persistent":false,
                 "request":{"method":"GET","url":"/orders"},"response":{"status":200}}
                """;
        String after = """
                {"id":"server-id","uuid":"server-id","persistent":true,
                 "request":{"method":"GET","url":"/orders"},"response":{"status":200}}
                """;
        enqueueAmbiguousPersistentReplacement("persist", before, after);

        JsonNode result = client.setPersistent("orders", "server-id", true);

        assertTrue(result.path("persistent").asBoolean());
        assertEquals(11, transport.requestCount());
    }

    @Test
    void persistentMutationContinuesWhenMarkerCreateResponseIsLostButMarkerIsVerified() throws Exception {
        String before = """
                {"id":"server-id","uuid":"server-id","persistent":false,
                 "request":{"method":"GET","url":"/orders"},"response":{"status":200}}
                """;
        String after = """
                {"id":"server-id","uuid":"server-id","persistent":true,
                 "request":{"method":"GET","url":"/orders"},"response":{"status":200}}
                """;
        String marker = recoveryMarker("persist", before, after);
        transport.respond(404, "");
        transport.respond(200, before);
        transport.respond(500, "marker response lost");
        transport.respond(200, marker);
        transport.respond(200, before);
        transport.respond(204, "");
        transport.respond(404, "");
        transport.respond(201, after);
        transport.respond(200, after);
        transport.respond(204, "");
        transport.respond(404, "");

        JsonNode result = client.setPersistent("orders", "server-id", true);

        assertTrue(result.path("persistent").asBoolean());
        assertEquals(HttpMethod.DELETE, transport.requestAt(5).method());
        assertEquals(11, transport.requestCount());
    }

    @Test
    void persistentMutationDoesNotDeleteBeforeWhenMarkerCannotBeVerified() throws Exception {
        String before = """
                {"id":"server-id","uuid":"server-id","persistent":false,
                 "request":{"method":"GET","url":"/orders"},"response":{"status":200}}
                """;
        transport.respond(404, "");
        transport.respond(200, before);
        transport.respond(201, "{}");
        transport.respond(404, "");

        McpOperationException error = assertThrows(McpOperationException.class,
                () -> client.setPersistent("orders", "server-id", true));

        assertEquals("WIREMOCK_ADMIN_ERROR", error.code());
        assertEquals(4, transport.requestCount());
        assertEquals(HttpMethod.GET, transport.requestAt(3).method());
    }

    @Test
    void unpersistReturnsVerifiedAfterStateWhenReplacementResponseIsLost() throws Exception {
        String before = """
                {"id":"server-id","uuid":"server-id","persistent":true,
                 "request":{"method":"GET","url":"/orders"},"response":{"status":200}}
                """;
        String after = """
                {"id":"server-id","uuid":"server-id","persistent":false,
                 "request":{"method":"GET","url":"/orders"},"response":{"status":200}}
                """;
        enqueueAmbiguousPersistentReplacement("unpersist", before, after);

        JsonNode result = client.setPersistent("orders", "server-id", false);

        assertFalse(result.path("persistent").asBoolean(true));
        assertEquals(11, transport.requestCount());
    }

    @Test
    void listStubsPaginatesAfterRemovingRecoveryMappings() throws Exception {
        transport.respond(200, """
                {"mappings":[
                  {"id":"first-id","request":{"url":"/first"}},
                  {"id":"recovery-id","metadata":{"_mockFleetMcpRecovery":{"operation":"update"}}}
                ],"meta":{"total":3}}
                """);
        transport.respond(200, """
                {"mappings":[{"id":"second-id","request":{"url":"/second"}}],"meta":{"total":3}}
                """);
        transport.respond(200, """
                {"mappings":[{"id":"second-id","request":{"url":"/second"}}],"meta":{"total":3}}
                """);

        JsonNode firstPage = client.listStubs("orders", 1, 0);
        JsonNode secondPage = client.listStubs("orders", 1,
                firstPage.path("meta").path("nextPosition").asInt());

        assertEquals("first-id", firstPage.path("mappings").get(0).path("id").asText());
        assertTrue(firstPage.path("meta").path("hasMore").asBoolean());
        assertEquals(2, firstPage.path("meta").path("nextPosition").asInt());
        assertEquals("second-id", secondPage.path("mappings").get(0).path("id").asText());
        assertFalse(secondPage.path("meta").path("hasMore").asBoolean());
        assertEquals("/__admin/mappings?limit=2&offset=0", transport.requestAt(0).target());
        assertEquals("/__admin/mappings?limit=2&offset=2", transport.requestAt(1).target());
        assertEquals("/__admin/mappings?limit=2&offset=2", transport.last().target());
    }

    @Test
    void listUnmatchedStubsFiltersEveryGeneralizedRecoveryOperation() {
        transport.respond(200, """
                {"mappings":[
                  {"id":"visible-id"},
                  {"id":"persist-marker","metadata":{"_mockFleetMcpRecovery":{"operation":"persist"}}},
                  {"id":"update-marker","metadata":{"_mockFleetMcpRecovery":{"operation":"update"}}}
                ],"meta":{"total":3}}
                """);

        JsonNode result = client.listUnmatchedStubs("orders");

        assertEquals(1, result.path("mappings").size());
        assertEquals("visible-id", result.path("mappings").get(0).path("id").asText());
        assertEquals(1, result.path("meta").path("total").asInt());
    }

    @Test
    void unmatchedStubPagesKeepRawPositionsAcrossHiddenRecoveryMappings() {
        String response = """
                {"mappings":[
                  {"id":"first-id"},
                  {"id":"recovery-id","metadata":{"_mockFleetMcpRecovery":{"operation":"update"}}},
                  {"id":"second-id"}
                ]}
                """;
        transport.respond(200, response);
        transport.respond(200, response);

        JsonNode first = client.listUnmatchedStubsPage("orders", 1, 0, 1024 * 1024, 100);
        JsonNode second = client.listUnmatchedStubsPage("orders", 1,
                first.path("meta").path("nextPosition").asLong(), 1024 * 1024, 100);

        assertEquals("first-id", first.path("mappings").get(0).path("id").asText());
        assertTrue(first.path("meta").path("hasMore").asBoolean());
        assertEquals(2, first.path("meta").path("nextPosition").asLong());
        assertEquals("second-id", second.path("mappings").get(0).path("id").asText());
        assertFalse(second.path("meta").path("hasMore").asBoolean());
    }

    @Test
    void listStubsReducesTheRawPageSizeWhenAResponseExceedsThePayloadLimit() {
        String oversized = "{\"mappings\":[],\"padding\":\"" + "x".repeat(1024 * 1024) + "\",\"meta\":{\"total\":0}}";
        transport.respond(200, oversized);
        transport.respond(200, """
                {"mappings":[{"id":"visible-id"}],"meta":{"total":1}}
                """);

        JsonNode page = client.listStubs("orders", 1, 0);

        assertEquals("visible-id", page.path("mappings").get(0).path("id").asText());
        assertEquals("/__admin/mappings?limit=2&offset=0", transport.requestAt(0).target());
        assertEquals("/__admin/mappings?limit=1&offset=0", transport.requestAt(1).target());
    }

    @Test
    void recorderStartForcesReviewableNonPersistentIdOutput() throws Exception {
        transport.respond(200, "{}");
        transport.respond(200, "{\"status\":\"Recording\"}");
        ObjectNode input = (ObjectNode) mapper.readTree("""
                {"targetBaseUrl":"https://example.test","persist":true,"outputFormat":"FULL"}
                """);

        JsonNode status = client.startRecording("orders", input);

        ObjectNode sent = transport.jsonAt(mapper, 0);
        assertFalse(sent.get("persist").booleanValue());
        assertEquals("IDS", sent.get("outputFormat").textValue());
        assertEquals("/__admin/recordings/start", transport.requestAt(0).target());
        assertEquals("/__admin/recordings/status", transport.last().target());
        assertEquals("Recording", status.path("status").asText());
    }

    @Test
    void recorderStartMarksInvalidFollowUpStatusAsPotentiallyChanged() {
        transport.respond(200, "{}");
        transport.respond(200, "{}");

        McpOperationException error = assertThrows(McpOperationException.class,
                () -> client.startRecording("orders", mapper.createObjectNode()));

        assertEquals("INVALID_UPSTREAM_RESPONSE", error.code());
        assertTrue(error.stateMayHaveChanged());
    }

    @Test
    void recorderStartMarksStatusLookupFailureAsPotentiallyChanged() {
        transport.respond(200, "{}");
        transport.respond(500, "status unavailable");

        McpOperationException error = assertThrows(McpOperationException.class,
                () -> client.startRecording("orders", mapper.createObjectNode()));

        assertEquals("WIREMOCK_ADMIN_ERROR", error.code());
        assertTrue(error.stateMayHaveChanged());
    }

    @Test
    void recorderStartPreservesAuthoritativeClientRejection() {
        transport.respond(400, "invalid recording spec");

        McpOperationException error = assertThrows(McpOperationException.class,
                () -> client.startRecording("orders", mapper.createObjectNode()));

        assertEquals("WIREMOCK_ADMIN_ERROR", error.code());
        assertFalse(error.stateMayHaveChanged());
        assertEquals(1, transport.requestCount());
    }

    @Test
    void recorderBaselineHonorsTheMappingScanItemBudget() {
        WireMockAdminClient limited = new WireMockAdminClient(transport, mapper, 1024 * 1024,
                Set.of("cookie"), null, 1024 * 1024, 1);
        transport.respond(200, """
                {"mappings":[{"id":"first"},{"id":"second"}],"meta":{"total":2}}
                """);

        McpOperationException error = assertThrows(McpOperationException.class,
                () -> limited.stopRecording("orders"));

        assertEquals("RESULT_TOO_LARGE", error.code());
        assertEquals(1, error.details().get("limitItems"));
        assertEquals(1, transport.requestCount());
    }

    @Test
    void recorderStopSendsNoRequestBody() {
        transport.respond(200, "{\"mappings\":[],\"meta\":{\"total\":0}}");
        transport.respond(200, "{\"ids\":[]}");

        client.stopRecording("orders");

        assertEquals(HttpMethod.POST, transport.last().method());
        assertEquals("/__admin/recordings/stop", transport.last().target());
        assertEquals(0, transport.last().body().length);
    }

    @Test
    void recorderFinalizationRemovesSensitiveHeadersFromRuntimeCandidates() throws Exception {
        transport.respond(200, "{\"mappings\":[],\"meta\":{\"total\":0}}");
        transport.respond(200, "{\"ids\":[\"recorded-id\"]}");
        transport.respond(200, """
                {"id":"recorded-id","persistent":false,
                 "request":{"headers":{"Authorization":{"equalTo":"secret"},"Accept":{"equalTo":"text/plain"}}},
                 "response":{"headers":{"Set-Cookie":"session=secret","Content-Type":"text/plain"}}}
                """);
        transport.respond(200, "{\"id\":\"recorded-id\",\"persistent\":false}");

        client.stopRecording("orders");

        assertEquals(4, transport.requestCount());
        assertEquals(HttpMethod.PUT, transport.last().method());
        ObjectNode sanitized = transport.lastJson(mapper);
        assertFalse(sanitized.path("request").path("headers").has("Authorization"));
        assertFalse(sanitized.path("response").path("headers").has("Set-Cookie"));
        assertTrue(sanitized.path("request").path("headers").has("Accept"));
    }

    @Test
    void recorderFinalizationDiscoversAndDeletesCandidatesWhenIdsAreMissing() {
        transport.respond(200, "{\"mappings\":[{\"id\":\"existing-id\"}],\"meta\":{\"total\":1}}");
        transport.respond(200, "{\"mappings\":[]}");
        transport.respond(200, """
                {"mappings":[{"id":"existing-id"},{"id":"recorded-id"}],"meta":{"total":2}}
                """);
        transport.respond(204, "");
        transport.respond(404, "");

        McpOperationException error = assertThrows(McpOperationException.class,
                () -> client.stopRecording("orders"));

        assertEquals("INVALID_UPSTREAM_RESPONSE", error.code());
        assertTrue(error.stateMayHaveChanged());
        assertEquals(HttpMethod.DELETE, transport.requestAt(3).method());
        assertEquals("/__admin/mappings/recorded-id", transport.requestAt(3).target());
        assertEquals(HttpMethod.GET, transport.requestAt(4).method());
    }

    @Test
    void recorderFinalizationDiscoversAndDeletesCandidatesWhenResultJsonIsInvalid() {
        transport.respond(200, "{\"mappings\":[],\"meta\":{\"total\":0}}");
        transport.respond(200, "not-json");
        transport.respond(200, """
                {"mappings":[{"id":"recorded-id"}],"meta":{"total":1}}
                """);
        transport.respond(204, "");
        transport.respond(404, "");

        McpOperationException error = assertThrows(McpOperationException.class,
                () -> client.stopRecording("orders"));

        assertEquals("INVALID_UPSTREAM_RESPONSE", error.code());
        assertTrue(error.stateMayHaveChanged());
        assertEquals("/__admin/mappings/recorded-id", transport.requestAt(3).target());
        assertEquals(5, transport.requestCount());
    }

    @Test
    void recorderFinalizationDiscoversAndDeletesCandidatesWhenResultIsOversized() {
        WireMockAdminClient limited = new WireMockAdminClient(
                transport, mapper, 128, Set.of("authorization"), null,
                67_108_864L, 100_000, new WireMockAdminClient.RecorderCleanupPolicy(1, 0, 0));
        transport.respond(200, "{\"mappings\":[],\"meta\":{\"total\":0}}");
        transport.respond(200, "x".repeat(129));
        transport.respond(200, """
                {"mappings":[{"id":"recorded-id"}],"meta":{"total":1}}
                """);
        transport.respond(204, "");
        transport.respond(404, "");

        McpOperationException error = assertThrows(McpOperationException.class,
                () -> limited.stopRecording("orders"));

        assertEquals("RESULT_TOO_LARGE", error.code());
        assertTrue(error.stateMayHaveChanged());
        assertEquals("/__admin/mappings/recorded-id", transport.requestAt(3).target());
        assertEquals(5, transport.requestCount());
    }

    @Test
    void recorderFinalizationDiscoversAndDeletesCandidatesWhenResultIsLost() {
        transport.respond(200, "{\"mappings\":[],\"meta\":{\"total\":0}}");
        transport.fail(new McpOperationException("UPSTREAM_UNAVAILABLE", "response lost", true, Map.of()));
        transport.respond(200, """
                {"mappings":[{"id":"recorded-id"}],"meta":{"total":1}}
                """);
        transport.respond(204, "");
        transport.respond(404, "");

        McpOperationException error = assertThrows(McpOperationException.class,
                () -> client.stopRecording("orders"));

        assertEquals("UPSTREAM_UNAVAILABLE", error.code());
        assertTrue(error.stateMayHaveChanged());
        assertEquals("/__admin/mappings/recorded-id", transport.requestAt(3).target());
        assertEquals(5, transport.requestCount());
    }

    @Test
    void recorderFinalizationPollsForCandidatesCreatedAfterTheFirstRecoveryScan() {
        WireMockAdminClient polling = new WireMockAdminClient(
                transport, mapper, 1024 * 1024, Set.of("authorization"), null,
                67_108_864L, 100_000, new WireMockAdminClient.RecorderCleanupPolicy(4, 2, 0));
        transport.respond(200, "{\"mappings\":[],\"meta\":{\"total\":0}}");
        transport.fail(new McpOperationException("UPSTREAM_UNAVAILABLE", "response lost", true, Map.of()));
        transport.respond(200, "{\"mappings\":[],\"meta\":{\"total\":0}}");
        transport.respond(200, """
                {"mappings":[{"id":"delayed-id"}],"meta":{"total":1}}
                """);
        transport.respond(204, "");
        transport.respond(404, "");
        transport.respond(200, "{\"mappings\":[],\"meta\":{\"total\":0}}");
        transport.respond(200, "{\"mappings\":[],\"meta\":{\"total\":0}}");

        McpOperationException error = assertThrows(McpOperationException.class,
                () -> polling.stopRecording("orders"));

        assertEquals("UPSTREAM_UNAVAILABLE", error.code());
        assertTrue(error.stateMayHaveChanged());
        assertEquals("/__admin/mappings/delayed-id", transport.requestAt(4).target());
        assertEquals(8, transport.requestCount());
    }

    @Test
    void recorderFinalizationReportsCandidateDiscoveryFailure() {
        transport.respond(200, "{\"mappings\":[],\"meta\":{\"total\":0}}");
        transport.fail(new McpOperationException("UPSTREAM_UNAVAILABLE", "response lost", true, Map.of()));
        transport.fail(new McpOperationException("WIREMOCK_ADMIN_ERROR", "scan failed", true, Map.of()));

        McpOperationException error = assertThrows(McpOperationException.class,
                () -> client.stopRecording("orders"));

        assertEquals("RECORDER_CLEANUP_FAILED", error.code());
        assertTrue(error.stateMayHaveChanged());
        assertEquals("candidate-discovery", error.details().get("stage"));
        assertEquals("WIREMOCK_ADMIN_ERROR",
                ((Map<?, ?>) error.details().get("discoveryError")).get("code"));
    }

    @Test
    void recorderFinalizationLabelsObservationInterruption() {
        WireMockAdminClient polling = new WireMockAdminClient(
                transport, mapper, 1024 * 1024, Set.of("authorization"), null,
                67_108_864L, 100_000, new WireMockAdminClient.RecorderCleanupPolicy(2, 1, 1));
        transport.respond(200, "{\"mappings\":[],\"meta\":{\"total\":0}}");
        transport.fail(new McpOperationException("UPSTREAM_UNAVAILABLE", "response lost", true, Map.of()));
        transport.respond(200, "{\"mappings\":[],\"meta\":{\"total\":0}}");

        McpOperationException error;
        try {
            Thread.currentThread().interrupt();
            error = assertThrows(McpOperationException.class, () -> polling.stopRecording("orders"));
        } finally {
            Thread.interrupted();
        }

        assertEquals("RECORDER_CLEANUP_FAILED", error.code());
        assertEquals("candidate-observation", error.details().get("stage"));
        assertTrue(error.details().containsKey("observationError"));
        assertFalse(error.details().containsKey("discoveryError"));
    }

    @Test
    void recorderFinalizationReportsAmbiguousCandidateCleanupFailure() {
        transport.respond(200, "{\"mappings\":[],\"meta\":{\"total\":0}}");
        transport.fail(new McpOperationException("UPSTREAM_UNAVAILABLE", "response lost", true, Map.of()));
        transport.respond(200, """
                {"mappings":[{"id":"recorded-id"}],"meta":{"total":1}}
                """);
        transport.respond(500, "delete failed");
        transport.respond(200, "{\"id\":\"recorded-id\"}");
        transport.respond(500, "update failed");
        transport.respond(200, "{\"id\":\"recorded-id\"}");

        McpOperationException error = assertThrows(McpOperationException.class,
                () -> client.stopRecording("orders"));

        assertEquals("RECORDER_CLEANUP_FAILED", error.code());
        assertEquals("candidate-cleanup", error.details().get("stage"));
        assertEquals(List.of("recorded-id"), error.details().get("cleanupFailedIds"));
    }

    @Test
    void recorderFinalizationReportsUnstableFinalCandidateScan() {
        WireMockAdminClient polling = new WireMockAdminClient(
                transport, mapper, 1024 * 1024, Set.of("authorization"), null,
                67_108_864L, 100_000, new WireMockAdminClient.RecorderCleanupPolicy(2, 2, 0));
        transport.respond(200, "{\"mappings\":[],\"meta\":{\"total\":0}}");
        transport.fail(new McpOperationException("UPSTREAM_UNAVAILABLE", "response lost", true, Map.of()));
        transport.respond(200, "{\"mappings\":[],\"meta\":{\"total\":0}}");
        transport.respond(200, """
                {"mappings":[{"id":"late-id"}],"meta":{"total":1}}
                """);
        transport.respond(204, "");
        transport.respond(404, "");

        McpOperationException error = assertThrows(McpOperationException.class,
                () -> polling.stopRecording("orders"));

        assertEquals("RECORDER_CLEANUP_FAILED", error.code());
        assertEquals("candidate-stabilization", error.details().get("stage"));
        assertEquals(List.of("late-id"), error.details().get("candidateIds"));
    }

    @Test
    void recorderStopPreservesAuthoritativeClientRejectionWithoutCleanup() {
        transport.respond(200, "{\"mappings\":[],\"meta\":{\"total\":0}}");
        transport.respond(400, "not recording");

        McpOperationException error = assertThrows(McpOperationException.class,
                () -> client.stopRecording("orders"));

        assertEquals("WIREMOCK_ADMIN_ERROR", error.code());
        assertFalse(error.stateMayHaveChanged());
        assertEquals(2, transport.requestCount());
    }

    @Test
    void recorderAnalysisIgnoresGeneralizedRecoveryMarkers() {
        String mappings = """
                {"mappings":[
                  {"id":"existing-id"},
                  {"id":"recovery-id","metadata":{"_mockFleetMcpRecovery":{"operation":"update"}}}
                ],"meta":{"total":2}}
                """;
        transport.respond(200, mappings);
        transport.respond(200, "{\"mappings\":[]}");
        transport.respond(200, mappings);

        McpOperationException error = assertThrows(McpOperationException.class,
                () -> client.stopRecording("orders"));

        assertEquals("INVALID_UPSTREAM_RESPONSE", error.code());
        assertEquals(3, transport.requestCount());
        assertEquals(HttpMethod.GET, transport.requestAt(2).method());
    }

    @Test
    void recorderSnapshotDiscoversAndDeletesCandidatesWhenIdsAreMissing() throws Exception {
        transport.respond(200, "{\"mappings\":[],\"meta\":{\"total\":0}}");
        transport.respond(200, "{\"mappings\":[]}");
        transport.respond(200, """
                {"mappings":[{"id":"snapshot-id"}],"meta":{"total":1}}
                """);
        transport.respond(204, "");
        transport.respond(404, "");

        McpOperationException error = assertThrows(McpOperationException.class,
                () -> client.snapshotRequests("orders", mapper.createObjectNode()));

        assertEquals("INVALID_UPSTREAM_RESPONSE", error.code());
        assertTrue(error.stateMayHaveChanged());
        assertEquals(HttpMethod.POST, transport.requestAt(1).method());
        ObjectNode payload = transport.jsonAt(mapper, 1);
        assertFalse(payload.path("persist").asBoolean(true));
        assertEquals("IDS", payload.path("outputFormat").asText());
        assertEquals("/__admin/mappings/snapshot-id", transport.requestAt(3).target());
    }

    @Test
    void recorderSnapshotCleansCandidatesAfterAmbiguousServerFailure() {
        transport.respond(200, "{\"mappings\":[],\"meta\":{\"total\":0}}");
        transport.respond(500, "snapshot response lost");
        transport.respond(200, """
                {"mappings":[{"id":"snapshot-id"}],"meta":{"total":1}}
                """);
        transport.respond(204, "");
        transport.respond(404, "");

        McpOperationException error = assertThrows(McpOperationException.class,
                () -> client.snapshotRequests("orders", mapper.createObjectNode()));

        assertEquals("WIREMOCK_ADMIN_ERROR", error.code());
        assertTrue(error.stateMayHaveChanged());
        assertEquals("/__admin/mappings/snapshot-id", transport.requestAt(3).target());
        assertEquals(5, transport.requestCount());
    }

    @Test
    void recorderSnapshotPreservesAuthoritativeClientRejectionWithoutCleanup() {
        transport.respond(200, "{\"mappings\":[],\"meta\":{\"total\":0}}");
        transport.respond(400, "invalid snapshot spec");

        McpOperationException error = assertThrows(McpOperationException.class,
                () -> client.snapshotRequests("orders", mapper.createObjectNode()));

        assertEquals("WIREMOCK_ADMIN_ERROR", error.code());
        assertFalse(error.stateMayHaveChanged());
        assertEquals(2, transport.requestCount());
    }

    @Test
    void recorderStopDoesNotTreatStubsCreatedDuringTheSessionAsCandidates() {
        transport.respond(200, "{}");
        transport.respond(200, "{\"status\":\"Recording\"}");
        transport.respond(200, """
                {"mappings":[{"id":"existing-id"},{"id":"normal-id"}],"meta":{"total":2}}
                """);
        transport.respond(200, "{\"mappings\":[]}");
        transport.respond(200, """
                {"mappings":[{"id":"existing-id"},{"id":"normal-id"},{"id":"recorded-id"}],"meta":{"total":3}}
                """);
        transport.respond(204, "");
        transport.respond(404, "");

        client.startRecording("orders", mapper.createObjectNode());
        McpOperationException error = assertThrows(McpOperationException.class,
                () -> client.stopRecording("orders"));

        assertEquals("INVALID_UPSTREAM_RESPONSE", error.code());
        assertEquals("/__admin/recordings/stop", transport.requestAt(3).target());
        assertEquals("/__admin/mappings/recorded-id", transport.requestAt(5).target());
        assertFalse(transport.requestAt(5).target().contains("normal-id"));
        assertEquals(7, transport.requestCount());
    }

    @Test
    void recorderFinalizationDeletesEveryCandidateWhenSanitizationFails() throws Exception {
        transport.respond(200, "{\"mappings\":[],\"meta\":{\"total\":0}}");
        transport.respond(200, "{\"ids\":[\"first-id\",\"second-id\"]}");
        transport.respond(200, """
                {"id":"first-id","persistent":false,
                 "request":{"headers":{"Authorization":{"equalTo":"secret"}}},"response":{"status":200}}
                """);
        transport.respond(200, "{\"id\":\"first-id\",\"persistent\":false}");
        transport.respond(500, "lookup failed");
        transport.respond(204, "");
        transport.respond(404, "");
        transport.respond(204, "");
        transport.respond(404, "");

        McpOperationException error = assertThrows(McpOperationException.class,
                () -> client.stopRecording("orders"));

        assertTrue(error.stateMayHaveChanged());
        assertEquals(HttpMethod.DELETE, transport.requestAt(5).method());
        assertEquals("/__admin/mappings/first-id", transport.requestAt(5).target());
        assertEquals(HttpMethod.GET, transport.requestAt(6).method());
        assertEquals(HttpMethod.DELETE, transport.requestAt(7).method());
        assertEquals("/__admin/mappings/second-id", transport.requestAt(7).target());
        assertEquals(HttpMethod.GET, transport.requestAt(8).method());
    }

    @Test
    void recorderFinalizationCleansValidSiblingsWhenAnIdIsMalformed() {
        transport.respond(200, "{\"mappings\":[],\"meta\":{\"total\":0}}");
        transport.respond(200, "{\"ids\":[\"first-id\",{\"invalid\":true},\"second-id\"]}");
        transport.respond(200, """
                {"mappings":[{"id":"first-id"},{"id":"second-id"}],"meta":{"total":2}}
                """);
        transport.respond(204, "");
        transport.respond(404, "");
        transport.respond(204, "");
        transport.respond(404, "");

        McpOperationException error = assertThrows(McpOperationException.class,
                () -> client.stopRecording("orders"));

        assertEquals("INVALID_UPSTREAM_RESPONSE", error.code());
        assertTrue(error.stateMayHaveChanged());
        assertEquals("/__admin/mappings/first-id", transport.requestAt(3).target());
        assertEquals("/__admin/mappings/second-id", transport.requestAt(5).target());
        assertEquals(7, transport.requestCount());
    }

    @Test
    void recorderFinalizationNeutralizesCandidateWhenDeletionFails() throws Exception {
        transport.respond(200, "{\"mappings\":[],\"meta\":{\"total\":0}}");
        transport.respond(200, "{\"ids\":[\"recorded-id\"]}");
        transport.respond(500, "lookup failed");
        transport.respond(500, "delete failed");
        transport.respond(200, """
                {"id":"recorded-id","request":{"headers":{"Authorization":{"equalTo":"secret"}}}}
                """);
        transport.respond(200, """
                {"id":"recorded-id","request":{"headers":{"Authorization":{"equalTo":"secret"}}}}
                """);
        transport.respond(200, "{}");
        transport.respond(200, """
                {"id":"recorded-id","metadata":{"_mockFleetMcpRecorderDiscarded":true}}
                """);

        assertThrows(McpOperationException.class, () -> client.stopRecording("orders"));

        assertEquals(HttpMethod.PUT, transport.requestAt(6).method());
        ObjectNode tombstone = transport.jsonAt(mapper, 6);
        assertTrue(tombstone.path("metadata").path("_mockFleetMcpRecorderDiscarded").asBoolean());
        assertFalse(tombstone.toString().contains("Authorization"));
        assertEquals(HttpMethod.GET, transport.requestAt(7).method());
    }

    @Test
    void recorderFinalizationReportsCandidatesThatCannotBeRemovedOrNeutralized() {
        transport.respond(200, "{\"mappings\":[],\"meta\":{\"total\":0}}");
        transport.respond(200, "{\"ids\":[\"recorded-id\"]}");
        transport.respond(500, "lookup failed");
        transport.respond(500, "delete failed");
        transport.respond(200, "{\"id\":\"recorded-id\"}");
        transport.respond(500, "update failed");
        transport.respond(200, "{\"id\":\"recorded-id\"}");

        McpOperationException error = assertThrows(McpOperationException.class,
                () -> client.stopRecording("orders"));

        assertEquals("RECORDER_CLEANUP_FAILED", error.code());
        assertTrue(error.stateMayHaveChanged());
        assertEquals("orders", error.details().get("mockId"));
        assertEquals(List.of("recorded-id"), error.details().get("cleanupFailedIds"));
    }

    @Test
    void ordinaryTrafficReturnsRedirectAndErrorResponsesWithoutAdminFailure() {
        transport.respond(404, "missing");
        transport.respond(500, "failed");

        TransportResponse notFound = client.sendRequest("orders", HttpMethod.GET, "/missing", Map.of(), new byte[0]);
        TransportResponse failed = client.sendRequest("orders", HttpMethod.POST, "/failed", Map.of(), new byte[0]);

        assertEquals(404, notFound.status());
        assertEquals("missing", notFound.bodyAsString());
        assertEquals(500, failed.status());
        assertEquals("failed", failed.bodyAsString());
    }

    @Test
    void ordinaryTrafficMarksOversizedResponseAsPotentiallyChanged() {
        WireMockAdminClient limited = new WireMockAdminClient(
                transport, mapper, 8, Set.of("authorization"));
        transport.respond(200, "response-too-large");

        McpOperationException error = assertThrows(McpOperationException.class,
                () -> limited.sendRequest("orders", HttpMethod.GET, "/orders", Map.of(), new byte[0]));

        assertEquals("RESULT_TOO_LARGE", error.code());
        assertTrue(error.stateMayHaveChanged());
    }

    @Test
    void resolvesLegacyThreeZeroRuntimeFromTheTargetFleetSupplier() {
        transport.respond(404, "");
        transport.respond(200, "{\"mappings\":[],\"meta\":{\"total\":0}}");

        WireMockVersion version = client.version("orders", () -> new WireMockVersion(3, 0, 4));

        assertEquals(new WireMockVersion(3, 0, 4), version);
        assertEquals("/__admin/version", transport.requestAt(0).target());
        assertEquals("/__admin/mappings?limit=1&offset=0", transport.requestAt(1).target());
    }

    private void enqueueAmbiguousPersistentReplacement(String operation, String before, String after) throws Exception {
        String marker = recoveryMarker(operation, before, after);
        transport.respond(404, "");
        transport.respond(200, before);
        transport.respond(201, marker);
        transport.respond(200, marker);
        transport.respond(200, before);
        transport.respond(204, "");
        transport.respond(404, "");
        transport.respond(500, "response lost");
        transport.respond(200, after);
        transport.respond(204, "");
        transport.respond(404, "");
    }

    private void enqueueSuccessfulPersistentReplacement(String operation, String before, String after) throws Exception {
        String marker = recoveryMarker(operation, before, after);
        transport.respond(404, "");
        transport.respond(200, before);
        transport.respond(201, marker);
        transport.respond(200, marker);
        transport.respond(200, before);
        transport.respond(204, "");
        transport.respond(404, "");
        transport.respond(201, after);
        transport.respond(200, after);
        transport.respond(204, "");
        transport.respond(404, "");
    }

    private String recoveryMarker(String operation, String before, String after) throws Exception {
        ObjectNode marker = mapper.createObjectNode();
        marker.put("id", "recovery-id");
        marker.put("persistent", true);
        ObjectNode recovery = marker.putObject("metadata").putObject("_mockFleetMcpRecovery");
        recovery.put("operation", operation);
        recovery.put("stubId", "server-id");
        recovery.set("before", mapper.readTree(before));
        recovery.set("after", mapper.readTree(after));
        return mapper.writeValueAsString(marker);
    }

    private String legacyRecoveryMarker(String mapping) throws Exception {
        ObjectNode marker = mapper.createObjectNode();
        marker.put("id", "recovery-id");
        marker.put("persistent", true);
        ObjectNode recovery = marker.putObject("metadata").putObject("_mockFleetMcpRecovery");
        recovery.put("operation", "unpersist");
        recovery.put("stubId", "server-id");
        recovery.set("mapping", mapper.readTree(mapping));
        return mapper.writeValueAsString(marker);
    }

    private static final class RecordingTransport implements FleetProxyTransport {
        private final ArrayDeque<Object> responses = new ArrayDeque<>();
        private final List<TransportRequest> requests = new ArrayList<>();

        void respond(int status, String body) {
            responses.add(new TransportResponse(status, Map.of("content-type", List.of("application/json")),
                    body.getBytes(StandardCharsets.UTF_8)));
        }

        void fail(RuntimeException failure) {
            responses.add(failure);
        }

        TransportRequest last() {
            return requests.getLast();
        }

        ObjectNode lastJson(ObjectMapper mapper) throws Exception {
            return (ObjectNode) mapper.readTree(last().body());
        }

        ObjectNode jsonAt(ObjectMapper mapper, int index) throws Exception {
            return (ObjectNode) mapper.readTree(requestAt(index).body());
        }

        TransportRequest requestAt(int index) {
            return requests.get(index);
        }

        int requestCount() {
            return requests.size();
        }

        @Override
        public TransportResponse execute(String mockId, TransportRequest request) {
            requests.add(request);
            Object response = responses.removeFirst();
            if (response instanceof RuntimeException failure) {
                throw failure;
            }
            return (TransportResponse) response;
        }
    }
}
