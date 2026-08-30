package com.github.letsrokk;

import jakarta.inject.Inject;
import io.smallrye.mutiny.Multi;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.RestStreamElementType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Path("/__fleet/api/mocks")
@Produces(MediaType.APPLICATION_JSON)
public class FleetResource {

    @Inject
    PodManager podManager;

    @Inject
    PodState podState;

    @Inject
    WireMockOptions wireMockOptions;

    @GET
    public List<MockRow> listActiveMocks() {
        return activeMocksSnapshot();
    }

    @GET
    @Path("/stream")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    public Multi<List<MockRow>> streamActiveMocks() {
        Multi<List<MockRow>> initialSnapshot = Multi.createFrom().item(this::activeMocksSnapshot);
        Multi<List<MockRow>> updates = podState.podChanges()
                .onItem().transform(ignored -> activeMocksSnapshot());

        return Multi.createBy().concatenating().streams(initialSnapshot, updates)
                .skip().repetitions();
    }

    private List<MockRow> activeMocksSnapshot() {
        return podManager.listMocks().stream()
                .map(mock -> new MockRow(mock.mockId(), mock.podName(), mock.status(), mock.message(),
                        desiredVersion(mock.mockId()), mock.runtimeVersion()))
                .toList();
    }

    private String desiredVersion(String mockId) {
        return wireMockOptions == null ? null : wireMockOptions.desiredVersionFor(mockId).toString();
    }

    @DELETE
    @Path("/{mockId}")
    public Response deleteMock(@PathParam("mockId") String mockId) {
        WireMockConfigService.validateMockId(mockId);
        return switch (podManager.deleteMock(mockId)) {
            case DELETED, NOT_FOUND, STOPPED -> Response.ok(lifecycleResponse(
                    new PodManager.MockPodStatus(mockId, null, MockLifecycleStatus.STOPPED, null))).build();
            case FAILED -> Response.serverError()
                    .entity(new ApiError("MOCK_STOP_FAILED", "Failed to stop mock pod.", true, true,
                            java.util.Map.of("mockId", mockId)))
                    .build();
        };
    }

    @POST
    @Path("/{mockId}/start")
    public Response startMock(@PathParam("mockId") String mockId) {
        WireMockConfigService.validateMockId(mockId);
        PodManager.MockPodStatus status = podManager.startMock(mockId);
        return switch (status.status()) {
            case RUNNING -> Response.ok(lifecycleResponse(status)).build();
            case STARTING -> Response.accepted(lifecycleResponse(status)).build();
            case FAILED -> startError(Response.Status.SERVICE_UNAVAILABLE, "MOCK_START_FAILED",
                    status.message() == null || status.message().isBlank() ? "Mock startup failed." : status.message(),
                    status, false);
            case STOPPED -> startError(Response.Status.CONFLICT, "MOCK_START_STOPPED",
                    "Mock startup was stopped.", status, true);
        };
    }

    private Response startError(Response.Status httpStatus, String code, String message,
                                PodManager.MockPodStatus status, boolean stateMayHaveChanged) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("mockId", status.mockId());
        details.put("status", status.status().name());
        if (status.podName() != null) {
            details.put("podName", status.podName());
        }
        return Response.status(httpStatus)
                .entity(new ApiError(code, message, true, stateMayHaveChanged, details))
                .build();
    }

    private MockLifecycleResponse lifecycleResponse(PodManager.MockPodStatus status) {
        return new MockLifecycleResponse(status.mockId(), status.status(), status.podName(), status.message(),
                status.status() == MockLifecycleStatus.STARTING ? 1000 : null);
    }

    public record MockRow(String mockId, String podName, MockLifecycleStatus status, String message,
                          String wireMockVersion, String runtimeVersion) {
        public MockRow(String mockId, String podName, MockLifecycleStatus status, String message) {
            this(mockId, podName, status, message, null, null);
        }
    }

    public record MockLifecycleResponse(String mockId, MockLifecycleStatus status, String podName, String message,
                                        Integer retryAfterMs) {
    }
}
