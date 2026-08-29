package com.github.letsrokk;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.Config;
import org.hamcrest.Matcher;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@QuarkusTest
class MappingsResourceTest {

    @InjectMock
    MappingsService mappingsService;

    @Inject
    Config config;

    @Test
    void configuresDefaultMappingTraversalLimits() {
        assertThat(config.getValue("mock-fleet.mappings.max-depth", Integer.class), is(32));
        assertThat(config.getValue("mock-fleet.mappings.max-entries", Integer.class), is(10_000));
    }

    @Test
    void getsMappingsView() {
        when(mappingsService.view()).thenReturn(new MappingsService.MappingsView(
                true,
                List.of("demo"),
                null,
                new MappingsService.RoutingView("HOST", "mock-fleet.localhost")));

        given()
        .when()
                .get("/__fleet/api/mappings")
        .then()
                .statusCode(200)
                .body("enabled", is(true))
                .body("mockIds[0]", is("demo"))
                .body("routing.mode", is("HOST"))
                .body("routing.host", is("mock-fleet.localhost"));

        verify(mappingsService).view();
    }

    @Test
    void getsMappingTree() {
        MappingsService.FileNode child = new MappingsService.FileNode("mapping.json", "mapping.json", false, List.of());
        when(mappingsService.tree("demo"))
                .thenReturn(new MappingsService.FileNode("demo", "", true, List.of(child)));

        given()
        .when()
                .get("/__fleet/api/mappings/demo/tree")
        .then()
                .statusCode(200)
                .body("name", is("demo"))
                .body("children[0].path", is("mapping.json"));

        verify(mappingsService).tree("demo");
    }

    @Test
    void streamsMappingFile() throws IOException {
        Path file = Files.createTempFile("mapping", ".json");
        Files.writeString(file, "{\"request\":{}}");
        when(mappingsService.file("demo", "mapping.json")).thenReturn(opened("mapping.json", file));

        given()
                .queryParam("path", "mapping.json")
        .when()
                .get("/__fleet/api/mappings/demo/files")
        .then()
                .statusCode(200)
                .body(is("{\"request\":{}}"));

        verify(mappingsService).file("demo", "mapping.json");
    }

    @Test
    void streamsInlineMappingFileTypes() throws IOException {
        assertInlineFileResponse("mapping.JSON", equalTo("application/json"));
        assertInlineFileResponse("mapping.xml", equalTo("application/xml"));
        assertInlineFileResponse("mapping.txt", startsWith("text/plain"));
        assertInlineFileResponse("mapping.pdf", equalTo("application/pdf"));
    }

    @Test
    void streamsUnknownFileTypesAsAttachments() throws IOException {
        Path file = Files.createTempFile("mapping", ".bin");
        Files.writeString(file, "binary");
        when(mappingsService.file("demo", "mapping.bin")).thenReturn(opened("mapping.bin", file));

        given()
                .queryParam("path", "mapping.bin")
        .when()
                .get("/__fleet/api/mappings/demo/files")
        .then()
                .statusCode(200)
                .contentType("application/octet-stream")
                .header("Content-Disposition", startsWith("attachment;"));

        verify(mappingsService).file("demo", "mapping.bin");
    }

    @Test
    void deletesMappingFile() {
        given()
                .queryParam("path", "mapping.json")
        .when()
                .delete("/__fleet/api/mappings/demo/files")
        .then()
                .statusCode(204);

        verify(mappingsService).deleteFile("demo", "mapping.json");
    }

    @Test
    void deletesMappingFolder() {
        given()
        .when()
                .delete("/__fleet/api/mappings/demo")
        .then()
                .statusCode(204);

        verify(mappingsService).deleteFolder("demo");
    }

    @Test
    void returnsStableTraversalLimitError() {
        doThrow(new ApiException(Response.Status.BAD_REQUEST,
                new ApiError("MAPPINGS_TRAVERSAL_LIMIT", "Mappings traversal limit exceeded.", false, false,
                        Map.of("mockId", "demo", "limit", "maxEntries", "maximum", 10_000))))
                .when(mappingsService).deleteFolder("demo");

        given()
        .when()
                .delete("/__fleet/api/mappings/demo")
        .then()
                .statusCode(400)
                .body("code", is("MAPPINGS_TRAVERSAL_LIMIT"))
                .body("retryable", is(false))
                .body("stateMayHaveChanged", is(false))
                .body("details.mockId", is("demo"))
                .body("details.limit", is("maxEntries"))
                .body("details.maximum", is(10_000));
    }

    private void assertInlineFileResponse(String fileName, Matcher<? super String> contentTypeMatcher) throws IOException {
        Path file = Files.createTempFile("mapping", ".tmp");
        Files.writeString(file, "content");
        when(mappingsService.file("demo", fileName)).thenReturn(opened(fileName, file));

        given()
                .queryParam("path", fileName)
        .when()
                .get("/__fleet/api/mappings/demo/files")
        .then()
                .statusCode(200)
                .contentType(contentTypeMatcher)
                .header("Content-Disposition", startsWith("inline;"));

        verify(mappingsService).file("demo", fileName);
    }

    private MappingsService.OpenedFile opened(String fileName, Path file) throws IOException {
        return new MappingsService.OpenedFile(fileName, Files.newByteChannel(file, StandardOpenOption.READ));
    }
}
