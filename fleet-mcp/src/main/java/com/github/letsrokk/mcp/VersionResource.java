package com.github.letsrokk.mcp;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.io.IOException;
import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.Properties;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@Path("/__fleet/mcp/version")
@Produces(MediaType.APPLICATION_JSON)
public final class VersionResource {

    private final String version;
    private final BuildInfo build;

    public VersionResource(@ConfigProperty(name = "quarkus.application.version", defaultValue = "unknown")
            String version) {
        this.version = version;
        this.build = loadBuildInfo();
    }

    @GET
    public VersionInfo version() {
        return new VersionInfo("mcp", version, build.revision(), build.buildTime());
    }

    private static BuildInfo loadBuildInfo() {
        Properties properties = new Properties();
        try (InputStream stream = VersionResource.class.getClassLoader().getResourceAsStream("git.properties")) {
            if (stream == null) {
                throw new IllegalStateException("git.properties is missing from the MCP artifact");
            }
            properties.load(stream);
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to read MCP build metadata", failure);
        }
        String revision = require(properties, "git.commit.id.full");
        if (!revision.matches("[0-9a-fA-F]{40}")) {
            throw new IllegalStateException("MCP build revision is not a full Git commit ID");
        }
        String buildTime = require(properties, "git.build.time");
        OffsetDateTime.parse(buildTime);
        return new BuildInfo(revision, buildTime);
    }

    private static String require(Properties properties, String name) {
        String value = properties.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is missing from MCP build metadata");
        }
        return value;
    }

    public record VersionInfo(String component, String version, String revision, String buildTime) {
    }

    private record BuildInfo(String revision, String buildTime) {
    }
}
