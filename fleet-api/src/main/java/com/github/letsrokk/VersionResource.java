package com.github.letsrokk;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@Path("/__fleet/api/version")
@Produces(MediaType.APPLICATION_JSON)
public class VersionResource {

    @ConfigProperty(name = "quarkus.application.version", defaultValue = "unknown")
    String version;

    @GET
    public VersionInfo version() {
        return new VersionInfo("api", version);
    }

    public record VersionInfo(String component, String version) {
    }
}
