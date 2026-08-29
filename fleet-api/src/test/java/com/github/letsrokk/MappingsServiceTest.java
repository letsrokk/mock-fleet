package com.github.letsrokk;

import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.ClosedDirectoryStreamException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NotDirectoryException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
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
    void rejectsMappingsIndexBeyondTheSharedDepthBudget() throws IOException {
        createDirectoryChain("demo", 3);
        MappingsService service = service(true, 2, 100);

        ApiException exception = assertThrows(ApiException.class, service::view);

        assertApiError(exception, 400, "MAPPINGS_TRAVERSAL_LIMIT", false,
                Map.of("limit", "maxDepth", "maximum", 2));
    }

    @Test
    void rejectsMappingsIndexBeyondOneRequestWideEntryBudget() throws IOException {
        for (String mockId : List.of("alpha", "beta", "gamma")) {
            Path root = Files.createDirectories(mappingsRoot.resolve(mockId));
            Files.writeString(root.resolve("mapping.json"), "{}");
        }
        MappingsService service = service(true, 10, 5);

        ApiException exception = assertThrows(ApiException.class, service::view);

        assertApiError(exception, 400, "MAPPINGS_TRAVERSAL_LIMIT", false,
                Map.of("limit", "maxEntries", "maximum", 5));
    }

    @Test
    void mappingsIndexAcceptsExactlyOneRequestWideEntryBudget() throws IOException {
        for (String mockId : List.of("alpha", "beta")) {
            Path root = Files.createDirectories(mappingsRoot.resolve(mockId));
            Files.writeString(root.resolve("mapping.json"), "{}");
        }
        MappingsService service = service(true, 10, 4);

        MappingsService.MappingsView view = service.view();

        assertEquals(List.of("alpha", "beta"), view.mockIds());
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
    void rejectsSymlinkMappingRootWithoutTraversingTarget() throws IOException {
        Path target = Files.createDirectories(mappingsRoot.resolve("target"));
        Path targetFile = Files.writeString(target.resolve("mapping.json"), "{}");
        createSymbolicLink(mappingsRoot.resolve("demo"), target);
        MappingsService service = service(true);

        ApiException exception = assertThrows(ApiException.class, () -> service.tree("demo"));

        assertApiError(exception, 503, "MAPPINGS_STORAGE_ERROR", true,
                Map.of("mockId", "demo", "path", ""));
        assertTrue(Files.exists(targetFile));
    }

    @Test
    void countsIgnoredSymlinksTowardMaximumEntries() throws IOException {
        Path root = Files.createDirectories(mappingsRoot.resolve("demo"));
        Path target = Files.writeString(mappingsRoot.resolve("target.json"), "{}");
        for (int index = 0; index < 4; index++) {
            createSymbolicLink(root.resolve("link-" + index), target);
        }
        MappingsService service = service(true, 10, 4);

        ApiException exception = assertThrows(ApiException.class, () -> service.tree("demo"));

        assertApiError(exception, 400, "MAPPINGS_TRAVERSAL_LIMIT", false,
                Map.of("mockId", "demo", "limit", "maxEntries", "maximum", 4));
    }

    @Test
    void doesNotOpenMockRootByFollowDefaultPath() throws IOException {
        Path root = Files.createDirectories(mappingsRoot.resolve("demo"));
        Files.writeString(root.resolve("mapping.json"), "{}");
        AtomicBoolean unsafeOpenObserved = new AtomicBoolean();
        MappingsService service = service(true);

        MappingsService.FileNode tree;
        try (MockedStatic<Files> files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            files.when(() -> Files.newDirectoryStream(root)).thenAnswer(invocation -> {
                unsafeOpenObserved.set(true);
                return root.getFileSystem().provider().newDirectoryStream(root, ignored -> true);
            });

            tree = service.tree("demo");
        }

        assertEquals(List.of("mapping.json"),
                tree.children().stream().map(MappingsService.FileNode::name).toList());
        assertFalse(unsafeOpenObserved.get());
    }

    @Test
    void rejectsProviderWithoutSecureDirectoryHandlesBeforeEnumeration() throws IOException {
        Files.createDirectories(mappingsRoot.resolve("demo"));
        AtomicBoolean enumerationObserved = new AtomicBoolean();
        DirectoryStream<Path> unsupportedParent = directoryStreamThatFailsOnEnumeration(enumerationObserved);
        MappingsService service = service(true, 32, 10_000, ignored -> unsupportedParent);

        ApiException exception;
        exception = assertThrows(ApiException.class, () -> service.tree("demo"));

        assertApiError(exception, 503, "MAPPINGS_STORAGE_ERROR", true,
                Map.of("mockId", "demo", "path", ""));
        assertFalse(enumerationObserved.get());
    }

    @Test
    void doesNotValidateDescendantsThroughPathResolution() throws IOException {
        Path root = Files.createDirectories(mappingsRoot.resolve("demo/nested"));
        Path file = Files.writeString(root.resolve("mapping.json"), "{}");
        AtomicBoolean unsafeAttributeReadObserved = new AtomicBoolean();
        MappingsService service = service(true);

        MappingsService.FileNode tree;
        try (MockedStatic<Files> files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            files.when(() -> Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS))
                    .thenAnswer(invocation -> {
                        unsafeAttributeReadObserved.set(true);
                        return file.getFileSystem().provider().readAttributes(file, BasicFileAttributes.class,
                                LinkOption.NOFOLLOW_LINKS);
                    });

            tree = service.tree("demo");
        }

        assertEquals("nested/mapping.json", tree.children().getFirst().children().getFirst().path());
        assertFalse(unsafeAttributeReadObserved.get());
    }

    @Test
    void resolvesAndDeletesFiles() throws IOException {
        Files.createDirectories(mappingsRoot.resolve("demo"));
        Path file = mappingsRoot.resolve("demo/mapping.json");
        Files.writeString(file, "{}");
        MappingsService service = service(true);

        try (MappingsService.OpenedFile opened = service.file("demo", "mapping.json")) {
            assertEquals("{}", new String(Channels.newInputStream(opened.channel()).readAllBytes()));
        }

        service.deleteFile("demo", "mapping.json");

        assertFalse(Files.exists(file));
    }

    @Test
    void rejectsFinalSymlinksForFileReadsAndDeletes() throws IOException {
        Path root = Files.createDirectories(mappingsRoot.resolve("demo"));
        Path target = Files.writeString(mappingsRoot.resolve("target.json"), "outside");
        Path link = createSymbolicLink(root.resolve("mapping.json"), target);
        MappingsService service = service(true);

        ApiException readError = assertThrows(ApiException.class,
                () -> service.file("demo", "mapping.json"));
        ApiException deleteError = assertThrows(ApiException.class,
                () -> service.deleteFile("demo", "mapping.json"));

        assertApiError(readError, 404, "MAPPING_FILE_NOT_FOUND", false,
                Map.of("mockId", "demo", "path", "mapping.json"));
        assertApiError(deleteError, 404, "MAPPING_FILE_NOT_FOUND", false,
                Map.of("mockId", "demo", "path", "mapping.json"));
        assertTrue(Files.isSymbolicLink(link));
        assertEquals("outside", Files.readString(target));
    }

    @Test
    void rejectsIntermediateSymlinksForFileReadsAndDeletes() throws IOException {
        Path root = Files.createDirectories(mappingsRoot.resolve("demo"));
        Path target = Files.createDirectories(mappingsRoot.resolve("target"));
        Path targetFile = Files.writeString(target.resolve("mapping.json"), "outside");
        Path link = createSymbolicLink(root.resolve("nested"), target);
        MappingsService service = service(true);

        ApiException readError = assertThrows(ApiException.class,
                () -> service.file("demo", "nested/mapping.json"));
        ApiException deleteError = assertThrows(ApiException.class,
                () -> service.deleteFile("demo", "nested/mapping.json"));

        assertApiError(readError, 404, "MAPPING_FILE_NOT_FOUND", false,
                Map.of("mockId", "demo", "path", "nested/mapping.json"));
        assertApiError(deleteError, 404, "MAPPING_FILE_NOT_FOUND", false,
                Map.of("mockId", "demo", "path", "nested/mapping.json"));
        assertTrue(Files.isSymbolicLink(link));
        assertEquals("outside", Files.readString(targetFile));
    }

    @Test
    void openedFileCannotBeRedirectedByAPathSwapBeforeStreaming() throws IOException {
        Path root = Files.createDirectories(mappingsRoot.resolve("demo"));
        Path original = Files.writeString(root.resolve("mapping.json"), "original");
        Path moved = root.resolve("moved.json");
        Path target = Files.writeString(mappingsRoot.resolve("target.json"), "outside");
        MappingsService service = service(true);

        Object opened = service.file("demo", "mapping.json");
        Files.move(original, moved, StandardCopyOption.ATOMIC_MOVE);
        createSymbolicLink(original, target);

        assertEquals("original", readOpenedFile(opened));
    }

    @Test
    void deleteUsesTheVerifiedIntermediateDirectoryHandleDuringAPathSwap() throws IOException {
        Path root = Files.createDirectories(mappingsRoot.resolve("demo"));
        Path nested = Files.createDirectories(root.resolve("nested"));
        Path original = Files.writeString(nested.resolve("mapping.json"), "original");
        Path moved = root.resolve("moved");
        Path target = Files.createDirectories(mappingsRoot.resolve("target"));
        Path targetFile = Files.writeString(target.resolve("mapping.json"), "outside");
        AtomicBoolean swapped = new AtomicBoolean();
        SecureStreamObserver observer = new SecureStreamObserver() {
            @Override
            public void beforeFileDelete(Path parent, Path name) throws IOException {
                swapDirectoryForSymlink(parent, name, nested, moved, target, swapped);
            }
        };
        MappingsService service = service(true, 32, 10_000,
                directory -> new TestSecureDirectoryStream(directory, observer));

        try (MockedStatic<Files> files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            files.when(() -> Files.deleteIfExists(original)).thenAnswer(invocation -> {
                swapDirectoryForSymlink(nested, original.getFileName(), nested, moved, target, swapped);
                return original.getFileSystem().provider().deleteIfExists(original);
            });
            service.deleteFile("demo", "nested/mapping.json");
        }

        assertTrue(Files.exists(targetFile), "the symlink target must not be deleted");
        assertFalse(Files.exists(moved.resolve("mapping.json")));
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
    void overEntryDeleteRemovesNoPath() throws IOException {
        Path root = Files.createDirectories(mappingsRoot.resolve("demo"));
        createFiles(root, 4);
        MappingsService service = service(true, 10, 4);

        ApiException exception = assertThrows(ApiException.class, () -> service.deleteFolder("demo"));

        assertApiError(exception, 400, "MAPPINGS_TRAVERSAL_LIMIT", false,
                Map.of("mockId", "demo", "limit", "maxEntries", "maximum", 4));
        for (int index = 0; index < 4; index++) {
            assertTrue(Files.exists(root.resolve("mapping-%05d.json".formatted(index))));
        }
    }

    @Test
    void rejectsDirectoryReplacementBeforeTraversalOrDeletion() throws IOException {
        Path root = Files.createDirectories(mappingsRoot.resolve("demo"));
        Path nested = Files.createDirectories(root.resolve("nested"));
        Path originalFile = Files.writeString(nested.resolve("original.json"), "{}");
        Path target = Files.createDirectories(mappingsRoot.resolve("target"));
        Path targetFile = Files.writeString(target.resolve("target.json"), "{}");
        Path moved = root.resolve("moved");
        AtomicBoolean replaced = new AtomicBoolean();
        SecureStreamObserver observer = new SecureStreamObserver() {
            @Override
            public void beforeDirectoryOpen(Path parent, Path name) throws IOException {
                if (parent.equals(root) && name.equals(nested.getFileName()) && replaced.compareAndSet(false, true)) {
                    parent.getFileSystem().provider().move(nested, moved, StandardCopyOption.ATOMIC_MOVE);
                    parent.getFileSystem().provider().createSymbolicLink(nested, target);
                }
            }
        };
        MappingsService service = service(true, 32, 10_000,
                directory -> new TestSecureDirectoryStream(directory, observer));

        ApiException exception = assertThrows(ApiException.class, () -> service.deleteFolder("demo"));

        assertApiError(exception, 503, "MAPPINGS_STORAGE_ERROR", true, Map.of("mockId", "demo"));
        assertTrue(Files.exists(targetFile));
        assertTrue(Files.exists(moved.resolve(originalFile.getFileName())));
    }

    @Test
    void rejectsDirectoryReplacementAfterDiscoveryBeforeTreeAssembly() throws IOException {
        Path root = Files.createDirectories(mappingsRoot.resolve("demo"));
        Path nested = Files.createDirectories(root.resolve("nested"));
        Files.writeString(nested.resolve("original.json"), "{}");
        Path target = Files.createDirectories(mappingsRoot.resolve("target"));
        Path moved = root.resolve("moved");
        AtomicInteger nestedAttributeReads = new AtomicInteger();
        SecureStreamObserver observer = new SecureStreamObserver() {
            @Override
            public void beforeAttributeRead(Path parent, Path name) throws IOException {
                if (parent.equals(root) && name.equals(nested.getFileName())
                        && nestedAttributeReads.incrementAndGet() == 2) {
                    parent.getFileSystem().provider().move(nested, moved, StandardCopyOption.ATOMIC_MOVE);
                    parent.getFileSystem().provider().createSymbolicLink(nested, target);
                }
            }
        };
        MappingsService service = service(true, 32, 10_000,
                directory -> new TestSecureDirectoryStream(directory, observer));

        ApiException exception = assertThrows(ApiException.class, () -> service.tree("demo"));

        assertApiError(exception, 503, "MAPPINGS_STORAGE_ERROR", true,
                Map.of("mockId", "demo", "path", ""));
        assertTrue(Files.exists(moved.resolve("original.json")));
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
    void reportsDirectoryPhaseStorageFailureDuringDelete() throws IOException {
        Assumptions.assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        Path root = Files.createDirectories(mappingsRoot.resolve("demo/nested"));
        MappingsService service = service(true);
        Set<PosixFilePermission> originalPermissions = Files.getPosixFilePermissions(root.getParent());
        Files.setPosixFilePermissions(root.getParent(), Set.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_EXECUTE));

        ApiException exception;
        try {
            exception = assertThrows(ApiException.class, () -> service.deleteFolder("demo"));
        } finally {
            Files.setPosixFilePermissions(root.getParent(), originalPermissions);
        }

        assertApiError(exception, 503, "MAPPINGS_STORAGE_ERROR", true, true, Map.of("mockId", "demo"));
        assertTrue(Files.exists(root));
    }

    @Test
    void deletesDiscoveredEntriesThroughSecureParentHandles() throws IOException {
        Path root = Files.createDirectories(mappingsRoot.resolve("demo/nested"));
        Path file = Files.writeString(root.resolve("mapping.json"), "{}");
        AtomicBoolean unsafeDeleteObserved = new AtomicBoolean();
        MappingsService service = service(true);

        try (MockedStatic<Files> files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            files.when(() -> Files.delete(any(Path.class))).thenAnswer(invocation -> {
                unsafeDeleteObserved.set(true);
                Path deleted = invocation.getArgument(0);
                deleted.getFileSystem().provider().delete(deleted);
                return null;
            });

            service.deleteFolder("demo");
        }

        assertFalse(Files.exists(mappingsRoot.resolve("demo")));
        assertFalse(unsafeDeleteObserved.get());
    }

    @Test
    void deletingMissingMappingFolderIsNoop() {
        MappingsService service = service(true);

        service.deleteFolder("missing");

        assertFalse(Files.exists(mappingsRoot.resolve("missing")));
    }

    @Test
    void reportsRootAttributeFailureBeforeDeleteDiscovery() throws IOException {
        Path root = Files.createDirectories(mappingsRoot.resolve("demo"));
        MappingsService service = service(true);

        ApiException exception;
        try (MockedStatic<Files> files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            files.when(() -> Files.exists(root, LinkOption.NOFOLLOW_LINKS)).thenReturn(false);
            files.when(() -> Files.readAttributes(root, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS))
                    .thenThrow(new AccessDeniedException(root.toString()));

            exception = assertThrows(ApiException.class, () -> service.deleteFolder("demo"));
        }

        assertApiError(exception, 503, "MAPPINGS_STORAGE_ERROR", true, false, Map.of("mockId", "demo"));
        assertTrue(Files.exists(root));
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

    private String readOpenedFile(Object opened) throws IOException {
        try (MappingsService.OpenedFile file = (MappingsService.OpenedFile) opened) {
            return new String(Channels.newInputStream(file.channel()).readAllBytes());
        }
    }

    private void swapDirectoryForSymlink(Path parent, Path name, Path nested, Path moved, Path target,
                                         AtomicBoolean swapped) throws IOException {
        if (parent.equals(nested) && name.equals(Path.of("mapping.json"))
                && swapped.compareAndSet(false, true)) {
            Files.move(nested, moved, StandardCopyOption.ATOMIC_MOVE);
            createSymbolicLink(nested, target);
        }
    }

    private Path createSymbolicLink(Path link, Path target) throws IOException {
        try {
            return Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException e) {
            Assumptions.assumeTrue(false, "Symbolic links are not supported by this file system");
            return link;
        }
    }

    private DirectoryStream<Path> directoryStreamThatFailsOnEnumeration(AtomicBoolean enumerationObserved) {
        return new DirectoryStream<>() {
            @Override
            public Iterator<Path> iterator() {
                enumerationObserved.set(true);
                throw new AssertionError("An unsupported directory stream must not be enumerated.");
            }

            @Override
            public void close() {
            }
        };
    }

    private MappingsService service(boolean persistent) {
        return service(persistent, 32, 10_000);
    }

    private MappingsService service(boolean persistent, int maxDepth, int maxEntries) {
        return service(persistent, maxDepth, maxEntries, TestSecureDirectoryStream::new);
    }

    private MappingsService service(boolean persistent, int maxDepth, int maxEntries,
                                    TrustedDirectoryFactory trustedDirectoryFactory) {
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

        MappingsService service = new MappingsService() {
            @Override
            DirectoryStream<Path> openTrustedDirectory(Path directory) throws IOException {
                return trustedDirectoryFactory.open(directory);
            }
        };
        service.config = config;
        return service;
    }

    @FunctionalInterface
    private interface TrustedDirectoryFactory {
        DirectoryStream<Path> open(Path directory) throws IOException;
    }

    private interface SecureStreamObserver {
        default void beforeDirectoryOpen(Path parent, Path name) throws IOException {
        }

        default void beforeAttributeRead(Path parent, Path name) throws IOException {
        }

        default void beforeFileDelete(Path parent, Path name) throws IOException {
        }
    }

    private static final class TestSecureDirectoryStream implements SecureDirectoryStream<Path> {

        private static final SecureStreamObserver NOOP_OBSERVER = new SecureStreamObserver() { };

        private final Path directory;
        private final SecureStreamObserver observer;
        private DirectoryStream<Path> iteratorStream;
        private boolean closed;

        private TestSecureDirectoryStream(Path directory) {
            this(directory, NOOP_OBSERVER);
        }

        private TestSecureDirectoryStream(Path directory, SecureStreamObserver observer) {
            this.directory = directory;
            this.observer = observer;
        }

        @Override
        public SecureDirectoryStream<Path> newDirectoryStream(Path path, LinkOption... options) throws IOException {
            ensureOpen();
            observer.beforeDirectoryOpen(directory, path);
            Path child = directory.resolve(path);
            BasicFileAttributes attributes = child.getFileSystem().provider()
                    .readAttributes(child, BasicFileAttributes.class, options);
            if (!attributes.isDirectory()) {
                throw new NotDirectoryException(child.toString());
            }
            return new TestSecureDirectoryStream(child, observer);
        }

        @Override
        public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options,
                                                  FileAttribute<?>... attrs) throws IOException {
            ensureOpen();
            Path child = directory.resolve(path);
            return child.getFileSystem().provider().newByteChannel(child, options, attrs);
        }

        @Override
        public void deleteFile(Path path) throws IOException {
            ensureOpen();
            observer.beforeFileDelete(directory, path);
            Path child = directory.resolve(path);
            child.getFileSystem().provider().delete(child);
        }

        @Override
        public void deleteDirectory(Path path) throws IOException {
            deleteFile(path);
        }

        @Override
        public void move(Path sourcePath, SecureDirectoryStream<Path> targetDirectory, Path targetPath)
                throws IOException {
            ensureOpen();
            if (!(targetDirectory instanceof TestSecureDirectoryStream target)) {
                throw new IOException("Test secure stream cannot move to an unknown provider.");
            }
            Path source = directory.resolve(sourcePath);
            Path destination = target.directory.resolve(targetPath);
            source.getFileSystem().provider().move(source, destination);
        }

        @Override
        public <V extends FileAttributeView> V getFileAttributeView(Path path, Class<V> type,
                                                                    LinkOption... options) {
            ensureOpenUnchecked();
            try {
                observer.beforeAttributeRead(directory, path);
            } catch (IOException e) {
                throw new DirectoryIteratorException(e);
            }
            Path child = directory.resolve(path);
            return child.getFileSystem().provider().getFileAttributeView(child, type, options);
        }

        @Override
        public <V extends FileAttributeView> V getFileAttributeView(Class<V> type) {
            ensureOpenUnchecked();
            return directory.getFileSystem().provider().getFileAttributeView(directory, type);
        }

        @Override
        public Iterator<Path> iterator() {
            ensureOpenUnchecked();
            if (iteratorStream != null) {
                throw new IllegalStateException("Iterator has already been obtained.");
            }
            try {
                iteratorStream = directory.getFileSystem().provider()
                        .newDirectoryStream(directory, ignored -> true);
                return iteratorStream.iterator();
            } catch (IOException e) {
                throw new DirectoryIteratorException(e);
            }
        }

        @Override
        public void close() throws IOException {
            closed = true;
            if (iteratorStream != null) {
                iteratorStream.close();
            }
        }

        private void ensureOpen() throws ClosedDirectoryStreamException {
            if (closed) {
                throw new ClosedDirectoryStreamException();
            }
        }

        private void ensureOpenUnchecked() {
            if (closed) {
                throw new ClosedDirectoryStreamException();
            }
        }
    }
}
