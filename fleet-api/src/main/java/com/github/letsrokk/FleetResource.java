package com.github.letsrokk;

import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

@Path("/__fleet/api/mocks")
@Produces(MediaType.APPLICATION_JSON)
public class FleetResource {

    @Inject
    PodManager podManager;

    @GET
    public List<MockRow> listActiveMocks() {
        return podManager.listActiveMocks().stream()
                .map(activeMockPod -> new MockRow(activeMockPod.mockId(), activeMockPod.podName()))
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

    public record MockRow(String mockId, String podName) {
    }
}
