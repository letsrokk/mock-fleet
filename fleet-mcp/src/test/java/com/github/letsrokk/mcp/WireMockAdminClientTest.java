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

class WireMockAdminClientTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final RecordingTransport transport = new RecordingTransport();
    private final WireMockAdminClient client = new WireMockAdminClient(
            transport, mapper, 1024 * 1024, Set.of("authorization", "cookie", "set-cookie"));

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
    void updateStubUsesExistingIdentityAndPersistenceState() throws Exception {
        transport.respond(200, "{\"id\":\"server-id\",\"uuid\":\"server-id\",\"persistent\":true}");
        transport.respond(200, "{\"id\":\"server-id\",\"persistent\":true}");
        ObjectNode input = (ObjectNode) mapper.readTree("""
                {"id":"client-id","persistent":false,
                 "request":{"method":"POST","url":"/orders"},"response":{"status":202}}
                """);

        client.updateStub("orders", "server-id", input);

        ObjectNode sent = transport.jsonAt(mapper, 1);
        assertEquals("server-id", sent.get("id").textValue());
        assertEquals("server-id", sent.get("uuid").textValue());
        assertTrue(sent.get("persistent").booleanValue());
        assertEquals("/__admin/mappings/server-id", transport.requestAt(1).target());
        assertEquals(2, transport.requestCount());
    }

    @Test
    void persistTransitionUsesImmediatePersistentUpdateWithoutSavingEveryStub() throws Exception {
        transport.respond(200, "{\"id\":\"server-id\",\"persistent\":false}");
        transport.respond(200, "{\"id\":\"server-id\",\"persistent\":true}");

        client.setPersistent("orders", "server-id", true);

        assertEquals(2, transport.requestCount());
        assertEquals(HttpMethod.PUT, transport.last().method());
        assertTrue(transport.lastJson(mapper).path("persistent").asBoolean());
    }

    @Test
    void unpersistTransitionRemovesTheBackingFileBeforeRecreatingTheTemporaryStub() throws Exception {
        transport.respond(404, "");
        transport.respond(200, """
                {"id":"server-id","uuid":"server-id","persistent":true,
                 "request":{"method":"GET","url":"/orders"},"response":{"status":200}}
                """);
        transport.respond(201, "{}");
        transport.respond(200, "{}");
        transport.respond(201, "{\"id\":\"server-id\",\"persistent\":false}");
        transport.respond(204, "");

        client.setPersistent("orders", "server-id", false);

        assertEquals(6, transport.requestCount());
        assertEquals(HttpMethod.POST, transport.requestAt(2).method());
        assertEquals("/__admin/mappings", transport.requestAt(2).target());
        assertEquals("server-id", transport.jsonAt(mapper, 2).path("metadata")
                .path("_mockFleetMcpRecovery").path("mapping").path("id").asText());
        assertEquals(HttpMethod.DELETE, transport.requestAt(3).method());
        assertEquals(HttpMethod.POST, transport.requestAt(4).method());
        ObjectNode recreated = transport.jsonAt(mapper, 4);
        assertEquals("server-id", recreated.path("id").asText());
        assertFalse(recreated.path("persistent").asBoolean());
        assertEquals(HttpMethod.DELETE, transport.last().method());
        assertEquals(transport.requestAt(0).target(), transport.last().target());
    }

    @Test
    void unpersistResumesFromDurableRecoveryMarkerWhenThePersistentStubIsMissing() throws Exception {
        transport.respond(200, """
                {"id":"recovery-id","persistent":true,
                 "metadata":{"_mockFleetMcpRecovery":{"operation":"unpersist","stubId":"server-id",
                   "mapping":{"id":"server-id","uuid":"server-id","persistent":true,
                     "request":{"method":"GET","url":"/orders"},"response":{"status":200}}}}}
                """);
        transport.respond(404, "");
        transport.respond(201, "{\"id\":\"server-id\",\"persistent\":false}");
        transport.respond(204, "");

        JsonNode result = client.setPersistent("orders", "server-id", false);

        assertFalse(result.path("persistent").asBoolean(true));
        assertEquals(HttpMethod.GET, transport.requestAt(0).method());
        assertTrue(transport.requestAt(0).target().startsWith("/__admin/mappings/"));
        assertEquals(HttpMethod.POST, transport.requestAt(2).method());
        assertEquals(HttpMethod.DELETE, transport.requestAt(3).method());
    }

    @Test
    void listStubsPaginatesAfterRemovingRecoveryMappings() throws Exception {
        transport.respond(200, """
                {"mappings":[
                  {"id":"first-id","request":{"url":"/first"}},
                  {"id":"recovery-id","metadata":{"_mockFleetMcpRecovery":{"operation":"unpersist"}}}
                ],"meta":{"total":3}}
                """);
        transport.respond(200, """
                {"mappings":[{"id":"second-id","request":{"url":"/second"}}],"meta":{"total":3}}
                """);

        JsonNode page = client.listStubs("orders", 1, 1);

        assertEquals("second-id", page.path("mappings").get(0).path("id").asText());
        assertEquals(2, page.path("meta").path("total").asInt());
        assertEquals(1, page.path("meta").path("limit").asInt());
        assertEquals(1, page.path("meta").path("offset").asInt());
        assertEquals("/__admin/mappings?limit=200&offset=0", transport.requestAt(0).target());
        assertEquals("/__admin/mappings?limit=200&offset=2", transport.last().target());
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
        assertEquals("/__admin/mappings?limit=200&offset=0", transport.requestAt(0).target());
        assertEquals("/__admin/mappings?limit=100&offset=0", transport.requestAt(1).target());
    }

    @Test
    void recorderStartForcesReviewableNonPersistentIdOutput() throws Exception {
        transport.respond(200, "{}");
        ObjectNode input = (ObjectNode) mapper.readTree("""
                {"targetBaseUrl":"https://example.test","persist":true,"outputFormat":"FULL"}
                """);

        client.startRecording("orders", input);

        ObjectNode sent = transport.lastJson(mapper);
        assertFalse(sent.get("persist").booleanValue());
        assertEquals("IDS", sent.get("outputFormat").textValue());
        assertEquals("/__admin/recordings/start", transport.last().target());
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
        assertEquals(HttpMethod.DELETE, transport.requestAt(3).method());
        assertEquals("/__admin/mappings/recorded-id", transport.requestAt(3).target());
        assertEquals(HttpMethod.GET, transport.requestAt(4).method());
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
        assertEquals(HttpMethod.POST, transport.requestAt(1).method());
        ObjectNode payload = transport.jsonAt(mapper, 1);
        assertFalse(payload.path("persist").asBoolean(true));
        assertEquals("IDS", payload.path("outputFormat").asText());
        assertEquals("/__admin/mappings/snapshot-id", transport.requestAt(3).target());
    }

    @Test
    void recorderStopDoesNotTreatStubsCreatedDuringTheSessionAsCandidates() {
        transport.respond(200, "{}");
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
        assertEquals("/__admin/recordings/stop", transport.requestAt(2).target());
        assertEquals("/__admin/mappings/recorded-id", transport.requestAt(4).target());
        assertFalse(transport.requestAt(4).target().contains("normal-id"));
        assertEquals(6, transport.requestCount());
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

        assertThrows(McpOperationException.class, () -> client.stopRecording("orders"));

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
        transport.respond(200, "{}");
        transport.respond(200, """
                {"id":"recorded-id","metadata":{"_mockFleetMcpRecorderDiscarded":true}}
                """);

        assertThrows(McpOperationException.class, () -> client.stopRecording("orders"));

        assertEquals(HttpMethod.PUT, transport.requestAt(5).method());
        ObjectNode tombstone = transport.jsonAt(mapper, 5);
        assertTrue(tombstone.path("metadata").path("_mockFleetMcpRecorderDiscarded").asBoolean());
        assertFalse(tombstone.toString().contains("Authorization"));
        assertEquals(HttpMethod.GET, transport.requestAt(6).method());
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
    void verifiesLegacyThreeZeroRuntimeWithAdminProbeWhenVersionEndpointIsAbsent() {
        var legacyClient = new WireMockAdminClient(transport, mapper, 1024 * 1024,
                Set.of("authorization"), null, new WireMockVersion(3, 0, 4));
        transport.respond(404, "");
        transport.respond(200, "{\"mappings\":[],\"meta\":{\"total\":0}}");

        WireMockVersion version = legacyClient.version("orders");

        assertEquals(new WireMockVersion(3, 0, 4), version);
        assertEquals("/__admin/version", transport.requestAt(0).target());
        assertEquals("/__admin/mappings?limit=1&offset=0", transport.requestAt(1).target());
    }

    private static final class RecordingTransport implements FleetProxyTransport {
        private final ArrayDeque<TransportResponse> responses = new ArrayDeque<>();
        private final List<TransportRequest> requests = new ArrayList<>();

        void respond(int status, String body) {
            responses.add(new TransportResponse(status, Map.of("content-type", List.of("application/json")),
                    body.getBytes(StandardCharsets.UTF_8)));
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
            return responses.removeFirst();
        }
    }
}
