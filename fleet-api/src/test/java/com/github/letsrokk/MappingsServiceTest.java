package com.github.letsrokk;

import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MappingsServiceTest {

    @TempDir
    Path mappingsRoot;

    @Test
    void disabledStorageReturnsDisabledView() {
        MappingsService service = service(false);

        MappingsService.MappingsView view = service.view();

        assertFalse(view.enabled());
        assertEquals(List.of(), view.mockIds());
        assertEquals("HOST", view.routing().mode());
        assertEquals("mock-fleet.localhost", view.routing().host());
    }

    @Test
    void disabledStorageRejectsTreeAndFileOperations() {
        MappingsService service = service(false);

        ApiException treeException = assertThrows(ApiException.class,
                () -> service.tree("demo"));
        ApiException fileException = assertThrows(ApiException.class,
                () -> service.file("demo", "mapping.json"));
        ApiException folderException = assertThrows(ApiException.class,
                () -> service.deleteFolder("demo"));

        assertApiError(treeException, 503, "MAPPINGS_STORAGE_DISABLED", false, Map.of());
        assertApiError(fileException, 503, "MAPPINGS_STORAGE_DISABLED", false, Map.of());
        assertApiError(folderException, 503, "MAPPINGS_STORAGE_DISABLED", false, Map.of());
    }

    @Test
    void listsOnlyValidNonEmptyMockDirectories() throws IOException {
        Files.createDirectories(mappingsRoot.resolve("beta/nested"));
        Files.createDirectories(mappingsRoot.resolve("alpha"));
        Files.createDirectories(mappingsRoot.resolve("not_valid"));
        Files.createDirectories(mappingsRoot.resolve("empty/nested"));
        Files.writeString(mappingsRoot.resolve("beta/nested/mapping.json"), "{}");
        Files.writeString(mappingsRoot.resolve("alpha/mapping.json"), "{}");
        Files.writeString(mappingsRoot.resolve("not_valid/mapping.json"), "{}");
        Files.writeString(mappingsRoot.resolve("file.json"), "{}");
        MappingsService service = service(true);

        MappingsService.MappingsView view = service.view();

        assertTrue(view.enabled());
        assertEquals(List.of("alpha", "beta"), view.mockIds());
        assertEquals("HOST", view.routing().mode());
        assertEquals("mock-fleet.localhost", view.routing().host());
    }

    @Test
    void returnsSortedFileTree() throws IOException {
        Files.createDirectories(mappingsRoot.resolve("demo/nested"));
        Files.writeString(mappingsRoot.resolve("demo/z.json"), "{}");
        Files.writeString(mappingsRoot.resolve("demo/a.json"), "{}");
        Files.writeString(mappingsRoot.resolve("demo/nested/b.json"), "{}");
        MappingsService service = service(true);

        MappingsService.FileNode tree = service.tree("demo");

        assertEquals("demo", tree.name());
        assertEquals("", tree.path());
        assertTrue(tree.directory());
        assertEquals(List.of("nested", "a.json", "z.json"),
                tree.children().stream().map(MappingsService.FileNode::name).toList());
        assertEquals("nested/b.json", tree.children().getFirst().children().getFirst().path());
    }

    @Test
    void returnsTreeWithEntryAtMaximumDepth() throws IOException {
        createDirectoryChain("demo", 2);
        MappingsService service = service(true, 2, 100);

        MappingsService.FileNode tree = service.tree("demo");

        MappingsService.FileNode node = tree;
        for (int depth = 1; depth <= 2; depth++) {
            node = node.children().getFirst();
        }
        assertEquals("d002", node.name());
        assertTrue(node.directory());
    }

    @Test
    void rejectsTreeWithEntryBeyondMaximumDepth() throws IOException {
        createDirectoryChain("demo", 3);
        MappingsService service = service(true, 2, 100);

        ApiException exception = assertThrows(ApiException.class, () -> service.tree("demo"));

        assertApiError(exception, 400, "MAPPINGS_TRAVERSAL_LIMIT", false,
                Map.of("mockId", "demo", "limit", "maxDepth", "maximum", 2));
    }

    @Test
    void rejectsDeepTreeWithoutRecursing() throws IOException {
        createDirectoryChain("demo", 128);
        MappingsService service = service(true, 64, 1_000);

        ApiException exception = assertThrows(ApiException.class, () -> service.tree("demo"));

        assertApiError(exception, 400, "MAPPINGS_TRAVERSAL_LIMIT", false,
                Map.of("mockId", "demo", "limit", "maxDepth", "maximum", 64));
    }

    @Test
    void returnsTreeWithExactlyMaximumEntries() throws IOException {
        Path root = Files.createDirectories(mappingsRoot.resolve("demo"));
        createFiles(root, 3);
        MappingsService service = service(true, 10, 4);

        MappingsService.FileNode tree = service.tree("demo");

        assertEquals(3, tree.children().size());
    }

    @Test
    void rejectsTreeWithEntryBeyondMaximumEntries() throws IOException {
        Path root = Files.createDirectories(mappingsRoot.resolve("demo"));
        createFiles(root, 4);
        MappingsService service = service(true, 10, 4);

        ApiException exception = assertThrows(ApiException.class, () -> service.tree("demo"));

        assertApiError(exception, 400, "MAPPINGS_TRAVERSAL_LIMIT", false,
                Map.of("mockId", "demo", "limit", "maxEntries", "maximum", 4));
    }

    @Test
    void countsDirectoriesAndRegularFilesTowardMaximumEntries() throws IOException {
        Path nested = Files.createDirectories(mappingsRoot.resolve("demo/nested"));
        Files.writeString(mappingsRoot.resolve("demo/root.json"), "{}");
        Files.writeString(nested.resolve("nested.json"), "{}");
        MappingsService service = service(true, 10, 3);

        ApiException exception = assertThrows(ApiException.class, () -> service.tree("demo"));

        assertApiError(exception, 400, "MAPPINGS_TRAVERSAL_LIMIT", false,
                Map.of("mockId", "demo", "limit", "maxEntries", "maximum", 3));
    }

    @Test
    void rejectsBroadTreeAtEntryBudget() throws IOException {
        Path root = Files.createDirectories(mappingsRoot.resolve("demo"));
        createFiles(root, 128);
        MappingsService service = service(true, 10, 64);

        ApiException exception = assertThrows(ApiException.class, () -> service.tree("demo"));

        assertApiError(exception, 400, "MAPPINGS_TRAVERSAL_LIMIT", false,
                Map.of("mockId", "demo", "limit", "maxEntries", "maximum", 64));
    }

    @Test
    void resolvesAndDeletesFiles() throws IOException {
        Files.createDirectories(mappingsRoot.resolve("demo"));
        Path file = mappingsRoot.resolve("demo/mapping.json");
        Files.writeString(file, "{}");
        MappingsService service = service(true);

        assertEquals(file.toAbsolutePath().normalize(), service.file("demo", "mapping.json"));

        service.deleteFile("demo", "mapping.json");

        assertFalse(Files.exists(file));
    }

    @Test
    void deletesMappingFolderRecursively() throws IOException {
        Files.createDirectories(mappingsRoot.resolve("demo/nested"));
        Files.writeString(mappingsRoot.resolve("demo/mapping.json"), "{}");
        Files.writeString(mappingsRoot.resolve("demo/nested/child.json"), "{}");
        Files.createDirectories(mappingsRoot.resolve("other"));
        MappingsService service = service(true);

        service.deleteFolder("demo");

        assertFalse(Files.exists(mappingsRoot.resolve("demo")));
        assertTrue(Files.exists(mappingsRoot.resolve("other")));
    }

    @Test
    void overDepthDeleteRemovesNoPath() throws IOException {
        Path root = Files.createDirectories(mappingsRoot.resolve("demo"));
        Path topLevelFile = Files.writeString(root.resolve("mapping.json"), "{}");
        Path deepest = createDirectoryChain("demo", 3);
        Path deepestFile = Files.writeString(deepest.resolve("deep.json"), "{}");
        MappingsService service = service(true, 2, 100);

        ApiException exception = assertThrows(ApiException.class, () -> service.deleteFolder("demo"));

        assertApiError(exception, 400, "MAPPINGS_TRAVERSAL_LIMIT", false,
                Map.of("mockId", "demo", "limit", "maxDepth", "maximum", 2));
        assertTrue(Files.exists(topLevelFile));
        assertTrue(Files.exists(deepestFile));
    }

    @Test
    void reportsStorageFailureDuringDelete() throws IOException {
        Assumptions.assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        Path root = Files.createDirectories(mappingsRoot.resolve("demo/nested"));
        Path file = Files.writeString(root.resolve("mapping.json"), "{}");
        MappingsService service = service(true);
        Set<PosixFilePermission> originalPermissions = Files.getPosixFilePermissions(root);
        Files.setPosixFilePermissions(root, Set.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_EXECUTE));

        ApiException exception;
        try {
            exception = assertThrows(ApiException.class, () -> service.deleteFolder("demo"));
        } finally {
            Files.setPosixFilePermissions(root, originalPermissions);
        }

        assertApiError(exception, 503, "MAPPINGS_STORAGE_ERROR", true, true, Map.of("mockId", "demo"));
        assertTrue(Files.exists(file));
    }

    @Test
    void deletingMissingMappingFolderIsNoop() {
        MappingsService service = service(true);

        service.deleteFolder("missing");

        assertFalse(Files.exists(mappingsRoot.resolve("missing")));
    }

    @Test
    void rejectsUnsafePaths() throws IOException {
        Files.createDirectories(mappingsRoot.resolve("demo"));
        MappingsService service = service(true);

        assertEquals(400, assertThrows(WebApplicationException.class,
                () -> service.file("demo", "../other.json")).getResponse().getStatus());
        assertEquals(400, assertThrows(WebApplicationException.class,
                () -> service.file("demo", mappingsRoot.resolve("demo/mapping.json").toString())).getResponse().getStatus());
        assertEquals(400, assertThrows(WebApplicationException.class,
                () -> service.tree("demo_1")).getResponse().getStatus());
        assertEquals(400, assertThrows(WebApplicationException.class,
                () -> service.deleteFolder("demo_1")).getResponse().getStatus());
    }

    @Test
    void unsafePathsAndMockIdsReturnStructuredErrors() throws IOException {
        Files.createDirectories(mappingsRoot.resolve("demo"));
        MappingsService service = service(true);

        ApiException unsafePath = assertThrows(ApiException.class,
                () -> service.file("demo", "../other.json"));
        ApiException invalidMockId = assertThrows(ApiException.class,
                () -> service.tree("demo_1"));

        assertApiError(unsafePath, 400, "INVALID_MAPPING_PATH", false,
                Map.of("mockId", "demo", "path", "../other.json"));
        assertApiError(invalidMockId, 400, "INVALID_MOCK_ID", false,
                Map.of("mockId", "demo_1"));
    }

    @Test
    void rejectsDirectoriesAsFiles() throws IOException {
        Files.createDirectories(mappingsRoot.resolve("demo/nested"));
        MappingsService service = service(true);

        WebApplicationException exception = assertThrows(WebApplicationException.class,
                () -> service.file("demo", "nested"));

        assertEquals(404, exception.getResponse().getStatus());
    }

    @Test
    void missingFilesReturnStructuredErrors() {
        MappingsService service = service(true);

        ApiException exception = assertThrows(ApiException.class,
                () -> service.file("demo", "missing.json"));

        assertApiError(exception, 404, "MAPPING_FILE_NOT_FOUND", false,
                Map.of("mockId", "demo", "path", "missing.json"));
    }

    private void assertApiError(ApiException exception, int status, String code, boolean retryable,
                                Map<String, Object> details) {
        assertApiError(exception, status, code, retryable, false, details);
    }

    private void assertApiError(ApiException exception, int status, String code, boolean retryable,
                                boolean stateMayHaveChanged, Map<String, Object> details) {
        assertEquals(status, exception.getResponse().getStatus());
        assertEquals("application/json", exception.getResponse().getMediaType().toString());
        ApiError error = (ApiError) exception.getResponse().getEntity();
        assertEquals(code, error.code());
        assertEquals(retryable, error.retryable());
        assertEquals(stateMayHaveChanged, error.stateMayHaveChanged());
        assertEquals(details, error.details());
    }

    private Path createDirectoryChain(String mockId, int depth) throws IOException {
        Path path = Files.createDirectories(mappingsRoot.resolve(mockId));
        for (int level = 1; level <= depth; level++) {
            path = Files.createDirectory(path.resolve("d%03d".formatted(level)));
        }
        return path;
    }

    private void createFiles(Path root, int count) throws IOException {
        for (int index = 0; index < count; index++) {
            Files.writeString(root.resolve("mapping-%05d.json".formatted(index)), "{}");
        }
    }

    private MappingsService service(boolean persistent) {
        return service(persistent, 32, 10_000);
    }

    private MappingsService service(boolean persistent, int maxDepth, int maxEntries) {
        MockFleetConfig config = mock(MockFleetConfig.class);
        MockFleetConfig.StorageConfig storage = mock(MockFleetConfig.StorageConfig.class);
        MockFleetConfig.MappingsConfig mappings = mock(MockFleetConfig.MappingsConfig.class);
        MockFleetConfig.ProxyConfig proxy = mock(MockFleetConfig.ProxyConfig.class);
        MockFleetConfig.RoutingConfig routing = mock(MockFleetConfig.RoutingConfig.class);
        when(config.storage()).thenReturn(storage);
        when(config.mappings()).thenReturn(mappings);
        when(config.proxy()).thenReturn(proxy);
        when(proxy.routing()).thenReturn(routing);
        when(routing.mode()).thenReturn(MockFleetConfig.RoutingMode.HOST);
        when(routing.host()).thenReturn("mock-fleet.localhost");
        when(storage.persistent()).thenReturn(persistent);
        when(storage.mappingsPath()).thenReturn(mappingsRoot.toString());
        when(mappings.maxDepth()).thenReturn(maxDepth);
        when(mappings.maxEntries()).thenReturn(maxEntries);

        MappingsService service = new MappingsService();
        service.config = config;
        return service;
    }
}
