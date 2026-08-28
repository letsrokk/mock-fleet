package com.github.letsrokk;

import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.concurrent.CompletionStage;

@Path("/internal/mocks")
@Produces(MediaType.APPLICATION_JSON)
public class InternalMockResource {

    @Inject
    PodManager podManager;

    @POST
    @Path("/{mockId}/upstream")
    public CompletionStage<UpstreamResponse> resolveUpstream(@PathParam("mockId") String mockId) {
        return podManager.getUpstreamBaseUrlAsync(mockId).thenApply(UpstreamResponse::new);
    }

    public record UpstreamResponse(String baseUrl) {
    }
}
