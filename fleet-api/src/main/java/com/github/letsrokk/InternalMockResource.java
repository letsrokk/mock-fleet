package com.github.letsrokk;

import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/internal/mocks")
@Produces(MediaType.APPLICATION_JSON)
public class InternalMockResource {

    @Inject
    PodManager podManager;

    @POST
    @Path("/{mockId}/upstream")
    public UpstreamResponse resolveUpstream(@PathParam("mockId") String mockId) {
        return new UpstreamResponse(podManager.getUpstreamBaseUrl(mockId));
    }

    public record UpstreamResponse(String baseUrl) {
    }
}
