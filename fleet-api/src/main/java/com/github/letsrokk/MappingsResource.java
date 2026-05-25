package com.github.letsrokk;

import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Locale;

@Path("/__fleet/api/mappings")
@Produces(MediaType.APPLICATION_JSON)
public class MappingsResource {

    @Inject
    MappingsService mappingsService;

    @GET
    public MappingsService.MappingsView getMappings() {
        return mappingsService.view();
    }

    @GET
    @Path("/{mockId}/tree")
    public MappingsService.FileNode getTree(@PathParam("mockId") String mockId) {
        return mappingsService.tree(mockId);
    }

    @DELETE
    @Path("/{mockId}")
    public Response deleteFolder(@PathParam("mockId") String mockId) {
        mappingsService.deleteFolder(mockId);
        return Response.noContent().build();
    }

    @GET
    @Path("/{mockId}/files")
    public Response getFile(@PathParam("mockId") String mockId, @QueryParam("path") String path) {
        java.nio.file.Path file = mappingsService.file(mockId, path);
        String fileName = java.nio.file.Path.of(path).getFileName().toString().replace("\"", "");
        FileResponse fileResponse = fileResponse(fileName);
        return Response.ok(file.toFile())
                .type(fileResponse.mediaType())
                .header("Content-Disposition", fileResponse.disposition() + "; filename=\"" + fileName + "\"")
                .build();
    }

    @DELETE
    @Path("/{mockId}/files")
    public Response deleteFile(@PathParam("mockId") String mockId, @QueryParam("path") String path) {
        mappingsService.deleteFile(mockId, path);
        return Response.noContent().build();
    }

    private FileResponse fileResponse(String fileName) {
        String lowerName = fileName.toLowerCase(Locale.ROOT);
        if (lowerName.endsWith(".json")) {
            return new FileResponse(MediaType.APPLICATION_JSON, "inline");
        }
        if (lowerName.endsWith(".xml")) {
            return new FileResponse(MediaType.APPLICATION_XML, "inline");
        }
        if (lowerName.endsWith(".pdf")) {
            return new FileResponse("application/pdf", "inline");
        }
        if (isPlainText(lowerName)) {
            return new FileResponse(MediaType.TEXT_PLAIN + "; charset=utf-8", "inline");
        }
        return new FileResponse(MediaType.APPLICATION_OCTET_STREAM, "attachment");
    }

    private boolean isPlainText(String lowerName) {
        return lowerName.endsWith(".txt")
                || lowerName.endsWith(".text")
                || lowerName.endsWith(".log")
                || lowerName.endsWith(".csv")
                || lowerName.endsWith(".yaml")
                || lowerName.endsWith(".yml")
                || lowerName.endsWith(".properties")
                || lowerName.endsWith(".conf")
                || lowerName.endsWith(".ini")
                || lowerName.endsWith(".md");
    }

    private record FileResponse(String mediaType, String disposition) {
    }
}
