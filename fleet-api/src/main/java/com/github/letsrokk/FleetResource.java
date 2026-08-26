package com.github.letsrokk;

import jakarta.inject.Inject;
import io.smallrye.mutiny.Multi;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.RestStreamElementType;

import java.util.List;

@Path("/__fleet/api/mocks")
@Produces(MediaType.APPLICATION_JSON)
public class FleetResource {

    @Inject
    PodManager podManager;

    @Inject
    PodState podState;

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
                .map(mock -> new MockRow(mock.mockId(), mock.podName(), mock.status(), mock.message()))
                .toList();
    }

    @DELETE
    @Path("/{mockId}")
    public Response deleteMock(@PathParam("mockId") String mockId) {
        return switch (podManager.deleteMock(mockId)) {
            case DELETED -> Response.noContent().build();
            case NOT_FOUND -> Response.status(Response.Status.NOT_FOUND).build();
            case FAILED -> Response.serverError()
                    .type(MediaType.TEXT_PLAIN_TYPE)
                    .entity("Failed to delete mock pod.")
                    .build();
        };
    }

    public record MockRow(String mockId, String podName, MockLifecycleStatus status, String message) {
    }
}
