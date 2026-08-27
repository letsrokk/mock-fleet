package com.github.letsrokk;

import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.core.MediaType;

@Path("/__fleet/api/config")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class WireMockConfigResource {

    @Inject
    WireMockConfigService configService;

    @GET
    public WireMockConfigService.ConfigView getConfig() {
        return configService.view();
    }

    @PUT
    @Path("/{mockId}")
    public WireMockConfigService.ConfigMutationResult upsertMockConfig(@PathParam("mockId") String mockId,
                                                                       WireMockConfigService.ConfigUpdateRequest request) {
        return configService.upsertMockConfig(mockId, request);
    }

    @DELETE
    @Path("/{mockId}")
    public WireMockConfigService.ConfigMutationResult deleteMockConfig(@PathParam("mockId") String mockId,
                                                                       WireMockConfigService.ConfigUpdateRequest request) {
        return configService.deleteMockConfig(mockId, request);
    }
}
