package com.github.letsrokk.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkiverse.mcp.server.ToolResponse;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpMethod;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FleetMcpToolsTrafficTest {

    @Test
    void createStubCannotRaceRecorderSnapshotForTheSameMock() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var transport = new BlockingRecorderTransport();
        var registry = new SimpleMeterRegistry();
        var metrics = new McpMetrics(registry);
        var wireMock = new WireMockAdminClient(transport, mapper, 4096, Set.of("authorization"), metrics,
                new WireMockVersion(3, 13, 2));
        try (var fleet = new FleetApiHarness()) {
            fleet.respond(200, running());
            fleet.respond(200, running());
            var tools = new FleetMcpTools(fleet.client(), wireMock, config(false), mapper,
                    new OutboundTargetValidator(new TargetUrlPolicy(Set.of())), new PerMockCoordinator(),
                    new McpToolExecutor(registry), metrics);

            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var create = executor.submit(() -> tools.createStub("orders", Map.of()));
                assertTrue(transport.createEntered.await(1, TimeUnit.SECONDS));

                var snapshot = executor.submit(() -> tools.snapshotRequests("orders", Map.of()));
                assertFalse(transport.snapshotScanEntered.await(100, TimeUnit.MILLISECONDS));

                transport.releaseCreate.countDown();
                assertFalse(create.get(1, TimeUnit.SECONDS).isError());
                assertFalse(snapshot.get(1, TimeUnit.SECONDS).isError());
                assertTrue(transport.snapshotScanEntered.await(1, TimeUnit.SECONDS));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { 404, 500 })
    void sendRequestReturnsIntentionalNonSuccessResponsesAsToolResults(int status) {
        ObjectMapper mapper = new ObjectMapper();
        var transport = new QueuedTransport();
        transport.respond(200, "{\"version\":\"3.13.2\"}");
        transport.respond(status, "upstream-body");
        var registry = new SimpleMeterRegistry();
        var metrics = new McpMetrics(registry);
        var wireMock = new WireMockAdminClient(transport, mapper, 4096, Set.of("authorization"), metrics,
                new WireMockVersion(3, 13, 2));
        try (var fleet = new FleetApiHarness()) {
            fleet.respond(200, running());
            var tools = new FleetMcpTools(fleet.client(), wireMock, config(false), mapper, null, null,
                    new McpToolExecutor(registry), metrics);

            ToolResponse response = tools.sendRequest("orders", "GET", "/expected-error", null, null);

            assertFalse(response.isError());
            ObjectNode structured = (ObjectNode) response.structuredContent();
            assertEquals("orders", structured.path("mockId").asText());
            assertEquals(status, structured.path("response").path("status").asInt());
            assertEquals("utf8", structured.path("response").path("body").path("encoding").asText());
            assertEquals("upstream-body", structured.path("response").path("body").path("data").asText());
        }
    }

    @Test
    void sendRequestMarksPostDispatchInclusionFailureAsPotentiallyChanged() {
        ObjectMapper mapper = new ObjectMapper();
        var transport = new QueuedTransport();
        transport.respond(200, "{\"version\":\"3.13.2\"}");
        transport.respond(200, "hello");
        try (var fleet = new FleetApiHarness()) {
            fleet.respond(200, running());

            ToolResponse response = tools(fleet.client(), transport, mapper, config(false, 4))
                    .sendRequest("orders", "GET", "/body", null, null);

            assertTrue(response.isError());
            McpToolExecutor.ErrorContent error =
                    ((McpToolExecutor.ErrorEnvelope) response.structuredContent()).error();
            assertEquals("RESULT_TOO_LARGE", error.code());
            assertTrue(error.stateMayHaveChanged());
            assertEquals("orders", error.details().get("mockId"));
        }
    }

    @Test
    void sendRequestRejectsInvalidHeaderNameBeforeTrafficDispatch() {
        assertInvalidRequestHeader(Map.of("Bad\nName", "value"));
    }

    @Test
    void sendRequestRejectsInvalidHeaderValueBeforeTrafficDispatch() {
        assertInvalidRequestHeader(Map.of("X-Test", "line-one\r\nline-two"));
    }

    @Test
    void coldStartReturnsRetryableErrorWithoutProxyTrafficThenSucceeds() {
        ObjectMapper mapper = new ObjectMapper();
        var transport = new QueuedTransport();
        transport.respond(200, "{\"version\":\"3.13.2\"}");
        transport.respond(200, "{\"mappings\":[{\"id\":\"stub-1\"}],\"meta\":{\"total\":1}}");
        try (var fleet = new FleetApiHarness()) {
            fleet.respond(202, starting());
            fleet.respond(200, running());
            FleetMcpTools tools = tools(fleet.client(), transport, mapper);

            ToolResponse starting = tools.listStubs("orders", 50, null);
            assertTrue(starting.isError());
            McpToolExecutor.ErrorContent error = ((McpToolExecutor.ErrorEnvelope) starting.structuredContent()).error();
            assertEquals("MOCK_STARTING", error.code());
            assertTrue(error.retryable());
            assertEquals(1000, error.details().get("retryAfterMs"));
            assertEquals(0, transport.requestCount());

            ToolResponse running = tools.listStubs("orders", 50, null);
            assertFalse(running.isError());
            ObjectNode result = (ObjectNode) running.structuredContent();
            assertEquals("orders", result.path("mockId").asText());
            assertEquals("stub-1", result.path("stubs").get(0).path("id").asText());
            assertEquals(1, result.path("page").path("returned").asInt());
            assertFalse(result.path("page").path("hasMore").asBoolean());
            assertEquals(2, transport.requestCount());
        }
    }

    @Test
    void terminalFleetStartFailureKeepsStructuredDiagnosticsAndSkipsProxy() {
        ObjectMapper mapper = new ObjectMapper();
        var transport = new QueuedTransport();
        try (var fleet = new FleetApiHarness()) {
            fleet.respond(503, """
                    {"code":"MOCK_START_FAILED","message":"ImagePullBackOff: denied","retryable":true,
                     "stateMayHaveChanged":false,"details":{"mockId":"orders","status":"FAILED"}}
                    """);
            FleetMcpTools tools = tools(fleet.client(), transport, mapper);

            ToolResponse response = tools.getStub("orders", "stub-1");

            assertTrue(response.isError());
            McpToolExecutor.ErrorContent error = ((McpToolExecutor.ErrorEnvelope) response.structuredContent()).error();
            assertEquals("MOCK_START_FAILED", error.code());
            assertEquals("FAILED", error.details().get("status"));
            assertEquals(0, transport.requestCount());
        }
    }

    @Test
    void startMockExposesStartingLifecycleWithoutProxyTraffic() {
        ObjectMapper mapper = new ObjectMapper();
        var transport = new QueuedTransport();
        try (var fleet = new FleetApiHarness()) {
            fleet.respond(202, starting());
            FleetMcpTools tools = tools(fleet.client(), transport, mapper);

            ToolResponse response = tools.startMock("orders");

            assertFalse(response.isError());
            ObjectNode result = (ObjectNode) response.structuredContent();
            assertEquals("STARTING", result.path("status").asText());
            assertEquals(1000, result.path("retryAfterMs").asInt());
            assertEquals(0, transport.requestCount());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "\"podName\":42",
            "\"message\":true",
            "\"retryAfterMs\":\"1000\"",
            "\"retryAfterMs\":-1",
            "\"retryAfterMs\":1.5"
    })
    void startMockRejectsMalformedOptionalLifecycleFields(String malformedField) {
        ObjectMapper mapper = new ObjectMapper();
        var transport = new QueuedTransport();
        try (var fleet = new FleetApiHarness()) {
            fleet.respond(200, "{\"mockId\":\"orders\",\"status\":\"RUNNING\"," + malformedField + "}");
            FleetMcpTools tools = tools(fleet.client(), transport, mapper);

            ToolResponse response = tools.startMock("orders");

            assertTrue(response.isError());
            McpToolExecutor.ErrorContent error = ((McpToolExecutor.ErrorEnvelope) response.structuredContent()).error();
            assertEquals("INVALID_UPSTREAM_RESPONSE", error.code());
            assertTrue(error.stateMayHaveChanged());
            assertEquals("orders", error.details().get("mockId"));
            assertEquals(0, transport.requestCount());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = { "podName", "message", "retryAfterMs" })
    void startMockRejectsMissingRequiredNullableLifecycleFields(String missingField) {
        ObjectMapper mapper = new ObjectMapper();
        var transport = new QueuedTransport();
        ObjectNode lifecycle = mapper.createObjectNode().put("mockId", "orders").put("status", "RUNNING");
        lifecycle.putNull("podName").putNull("message").putNull("retryAfterMs").remove(missingField);
        try (var fleet = new FleetApiHarness()) {
            fleet.respond(200, lifecycle.toString());
            FleetMcpTools tools = tools(fleet.client(), transport, mapper);

            ToolResponse response = tools.startMock("orders");

            assertTrue(response.isError());
            McpToolExecutor.ErrorContent error = ((McpToolExecutor.ErrorEnvelope) response.structuredContent()).error();
            assertEquals("INVALID_UPSTREAM_RESPONSE", error.code());
            assertTrue(error.stateMayHaveChanged());
            assertEquals("orders", error.details().get("mockId"));
            assertEquals(0, transport.requestCount());
        }
    }

    @Test
    void stopMockReturnsOnlyTheValidatedFleetStopLifecycleWithoutPreflightTraffic() {
        ObjectMapper mapper = new ObjectMapper();
        var transport = new QueuedTransport();
        try (var fleet = new FleetApiHarness()) {
            fleet.respond(200, """
                    {"mockId":"orders","status":"STOPPED","podName":null,
                     "message":null,"retryAfterMs":null}
                    """);
            FleetMcpTools tools = tools(fleet.client(), transport, mapper);

            ToolResponse response = tools.stopMock("orders");

            assertFalse(response.isError());
            ObjectNode result = (ObjectNode) response.structuredContent();
            assertEquals("orders", result.path("mockId").asText());
            assertEquals("STOPPED", result.path("status").asText());
            assertTrue(result.path("podName").isNull());
            assertTrue(result.path("message").isNull());
            assertTrue(result.path("retryAfterMs").isNull());
            assertEquals(List.of("DELETE /__fleet/api/mocks/orders"), fleet.requests());
            assertEquals(0, transport.requestCount());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[]",
            "{\"status\":\"STOPPED\"}",
            "{\"mockId\":\"payments\",\"status\":\"STOPPED\"}",
            "{\"mockId\":\"orders\",\"status\":\"RUNNING\"}",
            "{\"mockId\":\"orders\",\"status\":\"STOPPED\",\"podName\":42}",
            "{\"mockId\":\"orders\",\"status\":\"STOPPED\",\"message\":true}",
            "{\"mockId\":\"orders\",\"status\":\"STOPPED\",\"retryAfterMs\":\"0\"}",
            "{\"mockId\":\"orders\",\"status\":\"STOPPED\",\"retryAfterMs\":-1}",
            "{\"mockId\":\"orders\",\"status\":\"STOPPED\",\"retryAfterMs\":1.5}"
    })
    void stopMockRejectsMalformedOrMismatchedFleetResponses(String upstreamResponse) {
        ObjectMapper mapper = new ObjectMapper();
        var transport = new QueuedTransport();
        try (var fleet = new FleetApiHarness()) {
            fleet.respond(200, upstreamResponse);
            FleetMcpTools tools = tools(fleet.client(), transport, mapper);

            ToolResponse response = tools.stopMock("orders");

            assertTrue(response.isError());
            McpToolExecutor.ErrorContent error = ((McpToolExecutor.ErrorEnvelope) response.structuredContent()).error();
            assertEquals("INVALID_UPSTREAM_RESPONSE", error.code());
            assertTrue(error.stateMayHaveChanged());
            assertEquals("orders", error.details().get("mockId"));
            assertEquals(List.of("DELETE /__fleet/api/mocks/orders"), fleet.requests());
            assertEquals(0, transport.requestCount());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = { "podName", "message", "retryAfterMs" })
    void stopMockRejectsMissingRequiredNullableLifecycleFields(String missingField) {
        ObjectMapper mapper = new ObjectMapper();
        var transport = new QueuedTransport();
        ObjectNode lifecycle = mapper.createObjectNode().put("mockId", "orders").put("status", "STOPPED");
        lifecycle.putNull("podName").putNull("message").putNull("retryAfterMs").remove(missingField);
        try (var fleet = new FleetApiHarness()) {
            fleet.respond(200, lifecycle.toString());
            FleetMcpTools tools = tools(fleet.client(), transport, mapper);

            ToolResponse response = tools.stopMock("orders");

            assertTrue(response.isError());
            McpToolExecutor.ErrorContent error = ((McpToolExecutor.ErrorEnvelope) response.structuredContent()).error();
            assertEquals("INVALID_UPSTREAM_RESPONSE", error.code());
            assertTrue(error.stateMayHaveChanged());
            assertEquals("orders", error.details().get("mockId"));
            assertEquals(List.of("DELETE /__fleet/api/mocks/orders"), fleet.requests());
            assertEquals(0, transport.requestCount());
        }
    }

    @Test
    void adminPreflightRejectsMismatchedLifecycleMockIdWithoutProxyTraffic() {
        ObjectMapper mapper = new ObjectMapper();
        var transport = new QueuedTransport();
        try (var fleet = new FleetApiHarness()) {
            fleet.respond(200, """
                    {"mockId":"payments","status":"RUNNING","podName":"mock-payments-1",
                     "message":null,"retryAfterMs":null}
                    """);
            FleetMcpTools tools = tools(fleet.client(), transport, mapper);

            ToolResponse response = tools.getStub("orders", "stub-1");

            assertTrue(response.isError());
            McpToolExecutor.ErrorContent error = ((McpToolExecutor.ErrorEnvelope) response.structuredContent()).error();
            assertEquals("INVALID_UPSTREAM_RESPONSE", error.code());
            assertEquals("orders", error.details().get("mockId"));
            assertEquals(0, transport.requestCount());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = { "", "application/octet-stream" })
    void infersPrintableUtf8WhenContentTypeIsMissingOrGeneric(String contentType) {
        ObjectMapper mapper = new ObjectMapper();
        var transport = new QueuedTransport();
        transport.respond(200, "{\"version\":\"3.13.2\"}");
        transport.respond(200, contentType, "hello\nworld".getBytes(StandardCharsets.UTF_8));
        try (var fleet = new FleetApiHarness()) {
            fleet.respond(200, running());
            ToolResponse response = tools(fleet.client(), transport, mapper)
                    .sendRequest("orders", "GET", "/body", null, null);

            JsonNode body = ((ObjectNode) response.structuredContent()).path("response").path("body");
            assertEquals("utf8", body.path("encoding").asText());
            assertEquals("hello\nworld", body.path("data").asText());
            assertEquals(11, body.path("sizeBytes").asInt());
        }
    }

    @Test
    void encodesNonPrintableOrInvalidUtf8AsBase64() {
        ObjectMapper mapper = new ObjectMapper();
        var transport = new QueuedTransport();
        byte[] bytes = { 0, (byte) 0xff, 1 };
        transport.respond(200, "{\"version\":\"3.13.2\"}");
        transport.respond(200, "application/octet-stream", bytes);
        try (var fleet = new FleetApiHarness()) {
            fleet.respond(200, running());
            ToolResponse response = tools(fleet.client(), transport, mapper)
                    .sendRequest("orders", "GET", "/binary", null, null);

            JsonNode body = ((ObjectNode) response.structuredContent()).path("response").path("body");
            assertEquals("base64", body.path("encoding").asText());
            assertEquals(Base64.getEncoder().encodeToString(bytes), body.path("data").asText());
            assertEquals(3, body.path("sizeBytes").asInt());
        }
    }

    @Test
    void bodyFilesUseTheSameEncodedBodyContract() {
        ObjectMapper mapper = new ObjectMapper();
        var transport = new QueuedTransport();
        transport.respond(200, "{\"version\":\"3.13.2\"}");
        transport.respond(200, "", "file text".getBytes(StandardCharsets.UTF_8));
        try (var fleet = new FleetApiHarness()) {
            fleet.respond(200, running());

            ToolResponse response = tools(fleet.client(), transport, mapper).getBodyFile("orders", "result.txt");

            JsonNode result = mapper.valueToTree(response.structuredContent());
            assertEquals("orders", result.path("mockId").asText());
            assertEquals("result.txt", result.path("fileName").asText());
            assertFalse(result.has("contentType"));
            assertEquals("utf8", result.path("body").path("encoding").asText());
            assertEquals("file text", result.path("body").path("data").asText());
            assertEquals(9, result.path("body").path("sizeBytes").asInt());
        }
    }

    @Test
    void replacesPersistentMockPodAfterBodyFileWrite() {
        ObjectMapper mapper = new ObjectMapper();
        var transport = new QueuedTransport();
        transport.respond(200, "{\"version\":\"3.13.2\"}");
        transport.respond(201, "");
        try (var fleet = new FleetApiHarness()) {
            fleet.respond(200, running());
            fleet.respond(200, stopped());
            fleet.respond(202, starting());

            ToolResponse response = tools(fleet.client(), transport, mapper, config(true))
                    .putBodyFile("orders", "result.txt",
                            Map.of("encoding", "utf8", "data", "hello", "sizeBytes", 5));

            assertFalse(response.isError());
            assertEquals(List.of(
                    "POST /__fleet/api/mocks/orders/start",
                    "DELETE /__fleet/api/mocks/orders",
                    "POST /__fleet/api/mocks/orders/start"), fleet.requests());
            assertEquals(2, transport.requestCount());
        }
    }

    @Test
    void leavesMockRunningAfterBodyFileWriteWhenRemountIsDisabled() {
        ObjectMapper mapper = new ObjectMapper();
        var transport = new QueuedTransport();
        transport.respond(200, "{\"version\":\"3.13.2\"}");
        transport.respond(201, "");
        try (var fleet = new FleetApiHarness()) {
            fleet.respond(200, running());

            ToolResponse response = tools(fleet.client(), transport, mapper, config(false))
                    .putBodyFile("orders", "result.txt",
                            Map.of("encoding", "utf8", "data", "hello", "sizeBytes", 5));

            assertFalse(response.isError());
            assertEquals(List.of("POST /__fleet/api/mocks/orders/start"), fleet.requests());
        }
    }

    @Test
    void reportsStoredBodyFileWhenPersistentPodReplacementFails() {
        ObjectMapper mapper = new ObjectMapper();
        var transport = new QueuedTransport();
        transport.respond(200, "{\"version\":\"3.13.2\"}");
        transport.respond(201, "");
        try (var fleet = new FleetApiHarness()) {
            fleet.respond(200, running());
            fleet.respond(503, """
                    {"code":"MOCK_STOP_FAILED","message":"pod deletion timed out","retryable":true,
                     "stateMayHaveChanged":false,"details":{"mockId":"orders"}}
                    """);

            ToolResponse response = tools(fleet.client(), transport, mapper, config(true))
                    .putBodyFile("orders", "result.txt",
                            Map.of("encoding", "utf8", "data", "hello", "sizeBytes", 5));

            assertTrue(response.isError());
            McpToolExecutor.ErrorContent error =
                    ((McpToolExecutor.ErrorEnvelope) response.structuredContent()).error();
            assertEquals("MOCK_STOP_FAILED", error.code());
            assertTrue(error.retryable());
            assertTrue(error.stateMayHaveChanged());
            assertEquals(true, error.details().get("bodyFileStored"));
            assertEquals("result.txt", error.details().get("fileName"));
            assertEquals(List.of(
                    "POST /__fleet/api/mocks/orders/start",
                    "DELETE /__fleet/api/mocks/orders"), fleet.requests());
            assertEquals(2, transport.requestCount());
        }
    }

    @Test
    void forceDeletingBodyFileSkipsReferenceScan() {
        ObjectMapper mapper = new ObjectMapper();
        var transport = new QueuedTransport();
        transport.respond(200, "{\"version\":\"3.13.2\"}");
        transport.respond(204, "");
        try (var fleet = new FleetApiHarness()) {
            fleet.respond(200, running());

            ToolResponse response = tools(fleet.client(), transport, mapper)
                    .deleteBodyFile("orders", "payload.bin", true);

            assertFalse(response.isError());
            JsonNode result = mapper.valueToTree(response.structuredContent());
            assertTrue(result.path("deleted").asBoolean());
            assertTrue(result.path("forced").asBoolean());
            assertEquals(2, transport.requestCount());
        }
    }

    @Test
    void bodyFileReferenceErrorReportsOnlyTheFirstStub() {
        ObjectMapper mapper = new ObjectMapper();
        var transport = new QueuedTransport();
        transport.respond(200, "{\"version\":\"3.13.2\"}");
        transport.respond(200, """
                {"mappings":[
                  {"id":"first","response":{"bodyFileName":"payload.bin"}},
                  {"id":"second","response":{"bodyFileName":"payload.bin"}}],
                 "meta":{"total":2}}
                """);
        try (var fleet = new FleetApiHarness()) {
            fleet.respond(200, running());

            ToolResponse response = tools(fleet.client(), transport, mapper)
                    .deleteBodyFile("orders", "payload.bin", false);

            assertTrue(response.isError());
            McpToolExecutor.ErrorContent error = ((McpToolExecutor.ErrorEnvelope) response.structuredContent()).error();
            assertEquals("BODY_FILE_REFERENCED", error.code(), error.message());
            assertEquals("first", error.details().get("stubId"));
            assertFalse(error.details().containsKey("stubIds"));
            assertEquals(2, transport.requestCount());
        }
    }

    @Test
    void malformedBodyEncodingReturnsStructuredInvalidArgument() {
        ObjectMapper mapper = new ObjectMapper();
        var transport = new QueuedTransport();
        transport.respond(200, "{\"version\":\"3.13.2\"}");
        try (var fleet = new FleetApiHarness()) {
            fleet.respond(200, running());

            ToolResponse response = tools(fleet.client(), transport, mapper).sendRequest(
                    "orders", "POST", "/body", null,
                    Map.of("encoding", "hex", "data", "00", "sizeBytes", 1));

            assertTrue(response.isError());
            McpToolExecutor.ErrorContent error = ((McpToolExecutor.ErrorEnvelope) response.structuredContent()).error();
            assertEquals("INVALID_ARGUMENT", error.code());
            assertEquals("Unsupported body.encoding: hex", error.message());
            assertEquals(1, transport.requestCount());
        }
    }

    @Test
    void stopRecordingReturnsAnExplicitZeroMatchResult() {
        ObjectMapper mapper = new ObjectMapper();
        var transport = new QueuedTransport();
        transport.respond(200, "{\"version\":\"3.13.2\"}");
        transport.respond(200, "{\"mappings\":[],\"meta\":{\"total\":0}}");
        transport.respond(200, "{\"ids\":[]}");
        try (var fleet = new FleetApiHarness()) {
            fleet.respond(200, running());

            ToolResponse response = tools(fleet.client(), transport, mapper).stopRecording("orders");

            ObjectNode result = (ObjectNode) response.structuredContent();
            assertFalse(response.isError());
            assertEquals(mapper.createArrayNode(), result.path("candidateIds"));
            assertEquals(0, result.path("candidateCount").asInt());
            assertFalse(result.path("matchedRequests").asBoolean(true));
        }
    }

    @Test
    void stopRecordingMarksMalformedCandidateResponseAsPotentiallyChanged() {
        ObjectMapper mapper = new ObjectMapper();
        var transport = new QueuedTransport();
        transport.respond(200, "{\"version\":\"3.13.2\"}");
        transport.respond(200, "{\"mappings\":[],\"meta\":{\"total\":0}}");
        transport.respond(200, "{}");
        transport.respond(200, "{\"mappings\":[],\"meta\":{\"total\":0}}");
        try (var fleet = new FleetApiHarness()) {
            fleet.respond(200, running());

            ToolResponse response = tools(fleet.client(), transport, mapper).stopRecording("orders");

            assertTrue(response.isError());
            McpToolExecutor.ErrorContent error = ((McpToolExecutor.ErrorEnvelope) response.structuredContent()).error();
            assertEquals("INVALID_UPSTREAM_RESPONSE", error.code());
            assertTrue(error.stateMayHaveChanged());
            assertEquals("orders", error.details().get("mockId"));
        }
    }

    @Test
    void snapshotReturnsSanitizedCandidateIdsAndCount() {
        ObjectMapper mapper = new ObjectMapper();
        var transport = new QueuedTransport();
        transport.respond(200, "{\"version\":\"3.13.2\"}");
        transport.respond(200, "{\"mappings\":[],\"meta\":{\"total\":0}}");
        transport.respond(200, "{\"ids\":[\"candidate-1\"]}");
        transport.respond(200, "{\"id\":\"candidate-1\",\"request\":{},\"response\":{\"status\":200}}");
        transport.respond(200, "{\"id\":\"candidate-1\"}");
        try (var fleet = new FleetApiHarness()) {
            fleet.respond(200, running());

            ToolResponse response = tools(fleet.client(), transport, mapper).snapshotRequests("orders", Map.of());

            ObjectNode result = (ObjectNode) response.structuredContent();
            assertFalse(response.isError());
            assertEquals("candidate-1", result.path("candidateIds").get(0).asText());
            assertEquals(1, result.path("candidateCount").asInt());
            assertTrue(result.path("matchedRequests").asBoolean());
        }
    }

    private static FleetMcpTools tools(FleetApiClient fleetApi, FleetProxyTransport transport, ObjectMapper mapper) {
        return tools(fleetApi, transport, mapper, config(false));
    }

    private static FleetMcpTools tools(FleetApiClient fleetApi, FleetProxyTransport transport, ObjectMapper mapper,
            FleetMcpConfig config) {
        var registry = new SimpleMeterRegistry();
        var metrics = new McpMetrics(registry);
        var wireMock = new WireMockAdminClient(transport, mapper, 4096, Set.of("authorization"), metrics,
                new WireMockVersion(3, 13, 2));
        return new FleetMcpTools(fleetApi, wireMock, config, mapper,
                new OutboundTargetValidator(new TargetUrlPolicy(Set.of())), new PerMockCoordinator(),
                new McpToolExecutor(registry), metrics);
    }

    private static void assertInvalidRequestHeader(Map<String, Object> headers) {
        ObjectMapper mapper = new ObjectMapper();
        var transport = new QueuedTransport();
        transport.respond(200, "{\"version\":\"3.13.2\"}");
        try (var fleet = new FleetApiHarness()) {
            fleet.respond(200, running());

            ToolResponse response = tools(fleet.client(), transport, mapper)
                    .sendRequest("orders", "GET", "/body", headers, null);

            assertTrue(response.isError());
            McpToolExecutor.ErrorContent error =
                    ((McpToolExecutor.ErrorEnvelope) response.structuredContent()).error();
            assertEquals("INVALID_ARGUMENT", error.code());
            assertFalse(error.stateMayHaveChanged());
            assertEquals(1, transport.requestCount());
        }
    }

    private static String running() {
        return "{\"mockId\":\"orders\",\"status\":\"RUNNING\",\"podName\":\"mock-orders-1\",\"message\":null,\"retryAfterMs\":null}";
    }

    private static String starting() {
        return "{\"mockId\":\"orders\",\"status\":\"STARTING\",\"podName\":null,\"message\":null,\"retryAfterMs\":1000}";
    }

    private static String stopped() {
        return "{\"mockId\":\"orders\",\"status\":\"STOPPED\",\"podName\":null,\"message\":null,\"retryAfterMs\":null}";
    }

    private static FleetMcpConfig config(boolean storageEnabled) {
        return config(storageEnabled, 4096);
    }

    private static FleetMcpConfig config(boolean storageEnabled, int includedBodyBytes) {
        return new FleetMcpConfig() {
            @Override public URI apiBaseUrl() { return URI.create("http://api"); }
            @Override public URI proxyBaseUrl() { return URI.create("http://proxy"); }
            @Override public RoutingMode routingMode() { return RoutingMode.PATH; }
            @Override public Optional<String> fleetHost() { return Optional.empty(); }
            @Override public String wiremockImage() { return "wiremock/wiremock:3.13.2-2"; }
            @Override public boolean storageEnabled() { return storageEnabled; }
            @Override public Duration timeout() { return Duration.ofSeconds(1); }
            @Override public Duration lifecycleTimeout() { return Duration.ofSeconds(2); }
            @Override public int defaultPageSize() { return 50; }
            @Override public int maxPageSize() { return 200; }
            @Override public int maxPayloadBytes() { return 4096; }
            @Override public int includedBodyBytes() { return includedBodyBytes; }
            @Override public Duration dependencyHealthTimeout() { return Duration.ofSeconds(1); }
            @Override public long maxCollectionScanBytes() { return 64 * 1024 * 1024; }
            @Override public int maxCollectionScanItems() { return 100_000; }
            @Override public List<String> sensitiveHeaders() { return List.of("Authorization"); }
            @Override public Optional<List<String>> outboundExceptions() { return Optional.empty(); }
            @Override public Optional<List<String>> outboundAllowedListeners() { return Optional.empty(); }
        };
    }

    private static final class QueuedTransport implements FleetProxyTransport {
        private final ArrayDeque<TransportResponse> responses = new ArrayDeque<>();

        void respond(int status, String body) {
            responses.add(new TransportResponse(status, Map.of("content-type", List.of("text/plain")),
                    body.getBytes(StandardCharsets.UTF_8)));
        }

        void respond(int status, String contentType, byte[] body) {
            Map<String, List<String>> headers = contentType.isEmpty()
                    ? Map.of() : Map.of("content-type", List.of(contentType));
            responses.add(new TransportResponse(status, headers, body));
        }

        int requestCount() {
            return requests;
        }

        private int requests;
        @Override
        public TransportResponse execute(String mockId, TransportRequest request) {
            requests++;
            return responses.removeFirst();
        }
    }

    private static final class FleetApiHarness implements AutoCloseable {
        private final ObjectMapper mapper = new ObjectMapper();
        private final ArrayDeque<FleetResponse> responses = new ArrayDeque<>();
        private final Vertx vertx = Vertx.vertx();
        private final HttpServer server;
        private final FleetApiClient client;
        private final List<String> requests = new java.util.concurrent.CopyOnWriteArrayList<>();

        private FleetApiHarness() {
            server = vertx.createHttpServer().requestHandler(request -> request.bodyHandler(ignored -> {
                requests.add(request.method().name() + " " + request.path());
                FleetResponse response = responses.isEmpty() ? new FleetResponse(200, running()) : responses.removeFirst();
                request.response().setStatusCode(response.status()).putHeader("Content-Type", "application/json")
                        .end(response.body());
            })).listen(0, "127.0.0.1").toCompletionStage().toCompletableFuture().join();
            client = new FleetApiClient(vertx, URI.create("http://127.0.0.1:" + server.actualPort()),
                    Duration.ofSeconds(2), 4096, mapper, new McpMetrics(new SimpleMeterRegistry()));
        }

        void respond(int status, String body) {
            responses.add(new FleetResponse(status, body));
        }

        FleetApiClient client() {
            return client;
        }

        List<String> requests() {
            return List.copyOf(requests);
        }

        @Override
        public void close() {
            client.close();
            server.close().toCompletionStage().toCompletableFuture().join();
            vertx.close().toCompletionStage().toCompletableFuture().join();
        }

        private record FleetResponse(int status, String body) {}
    }

    private static final class BlockingRecorderTransport implements FleetProxyTransport {
        private final CountDownLatch createEntered = new CountDownLatch(1);
        private final CountDownLatch releaseCreate = new CountDownLatch(1);
        private final CountDownLatch snapshotScanEntered = new CountDownLatch(1);

        @Override
        public TransportResponse execute(String mockId, TransportRequest request) {
            String body;
            if ("/__admin/version".equals(request.target())) {
                body = "{\"version\":\"3.13.2\"}";
            } else if (request.method() == HttpMethod.POST && "/__admin/mappings".equals(request.target())) {
                createEntered.countDown();
                await(releaseCreate);
                body = "{\"id\":\"created-id\",\"persistent\":false}";
            } else if (request.method() == HttpMethod.GET
                    && request.target().startsWith("/__admin/mappings?limit=")) {
                snapshotScanEntered.countDown();
                body = "{\"mappings\":[],\"meta\":{\"total\":0}}";
            } else if (request.method() == HttpMethod.POST
                    && "/__admin/recordings/snapshot".equals(request.target())) {
                body = "{\"ids\":[]}";
            } else {
                throw new AssertionError("Unexpected request: " + request.method() + " " + request.target());
            }
            return new TransportResponse(200, Map.of("content-type", List.of("application/json")),
                    body.getBytes(StandardCharsets.UTF_8));
        }

        private static void await(CountDownLatch latch) {
            try {
                latch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }
}
