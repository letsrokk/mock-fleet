package com.github.letsrokk;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import org.hamcrest.Matcher;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@QuarkusTest
class MappingsResourceTest {

    @InjectMock
    MappingsService mappingsService;

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
        when(mappingsService.file("demo", "mapping.json")).thenReturn(file);

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
        when(mappingsService.file("demo", "mapping.bin")).thenReturn(file);

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

    private void assertInlineFileResponse(String fileName, Matcher<? super String> contentTypeMatcher) throws IOException {
        Path file = Files.createTempFile("mapping", ".tmp");
        Files.writeString(file, "content");
        when(mappingsService.file("demo", fileName)).thenReturn(file);

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
}
