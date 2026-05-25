package com.github.letsrokk;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@ApplicationScoped
public class MappingsService {

    private static final Pattern VALID_MOCK_ID = Pattern.compile("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$");
    static final String MOCK_ID_VALIDATION_MESSAGE = "Mock id must contain 1-63 lowercase letters, numbers, or hyphens, and must start and end with a letter or number.";

    @Inject
    MockFleetConfig config;

    MappingsView view() {
        if (!enabled()) {
            return new MappingsView(false, List.of());
        }

        Path root = mappingsRoot();
        if (!Files.isDirectory(root)) {
            return new MappingsView(true, List.of());
        }

        try (Stream<Path> children = Files.list(root)) {
            List<String> mockIds = children
                    .filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .map(path -> path.getFileName().toString())
                    .filter(mockId -> VALID_MOCK_ID.matcher(mockId).matches())
                    .sorted()
                    .toList();
            return new MappingsView(true, mockIds, null);
        } catch (IOException e) {
            return new MappingsView(true, List.of(), "Unable to list mappings root: " + ioErrorMessage(e));
        }
    }

    FileNode tree(String mockId) {
        ensureEnabled();
        validateMockId(mockId);
        Path mockRoot = mappingsRoot().resolve(mockId).normalize();
        ensureInside(mappingsRoot(), mockRoot);
        if (!Files.isDirectory(mockRoot)) {
            throw error("Mappings folder not found.", Response.Status.NOT_FOUND);
        }
        return node(mockRoot, mockRoot);
    }

    Path file(String mockId, String relativePath) {
        Path file = resolveFile(mockId, relativePath);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw error("Mapping file not found.", Response.Status.NOT_FOUND);
        }
        return file;
    }

    void deleteFile(String mockId, String relativePath) {
        Path file = resolveFile(mockId, relativePath);
        try {
            if (!Files.deleteIfExists(file)) {
                if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                    return;
                }
                throw error("Mapping file not found.", Response.Status.NOT_FOUND);
            }
        } catch (IOException e) {
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            throw error("Unable to delete mapping file: " + ioErrorMessage(e), Response.Status.SERVICE_UNAVAILABLE);
        }
    }

    void deleteFolder(String mockId) {
        ensureEnabled();
        validateMockId(mockId);
        Path root = mappingsRoot();
        Path mockRoot = root.resolve(mockId).normalize();
        ensureInside(root, mockRoot);
        if (!Files.exists(mockRoot, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (!Files.isDirectory(mockRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw error("Mappings folder not found.", Response.Status.NOT_FOUND);
        }

        try (Stream<Path> paths = Files.walk(mockRoot)) {
            List<Path> deleteOrder = paths
                    .filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                            || Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .sorted(Comparator.reverseOrder())
                    .toList();
            for (Path path : deleteOrder) {
                deleteMappingPath(path);
            }
        } catch (IOException e) {
            if (!Files.exists(mockRoot, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            throw error("Unable to delete mappings folder: " + ioErrorMessage(e), Response.Status.SERVICE_UNAVAILABLE);
        }
    }

    private void deleteMappingPath(Path path) throws IOException {
        if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            Files.deleteIfExists(path);
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            // S3-style mounts can reject rmdir for virtual directories after all objects are removed.
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            throw e;
        }
    }

    private FileNode node(Path mockRoot, Path path) {
        String name = path.equals(mockRoot) ? mockRoot.getFileName().toString() : path.getFileName().toString();
        String relativePath = path.equals(mockRoot) ? "" : mockRoot.relativize(path).toString().replace('\\', '/');
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            return new FileNode(name, relativePath, false, List.of());
        }

        try (Stream<Path> children = Files.list(path)) {
            List<FileNode> childNodes = children
                    .filter(child -> Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)
                            || Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS))
                    .sorted(Comparator
                            .comparing((Path child) -> !Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS))
                            .thenComparing(child -> child.getFileName().toString()))
                    .map(child -> node(mockRoot, child))
                    .toList();
            return new FileNode(name, relativePath, true, childNodes);
        } catch (IOException e) {
            throw error("Unable to list mappings folder: " + ioErrorMessage(e), Response.Status.SERVICE_UNAVAILABLE);
        }
    }

    private Path resolveFile(String mockId, String relativePath) {
        ensureEnabled();
        validateMockId(mockId);
        if (relativePath == null || relativePath.isBlank()) {
            throw error("Mapping file path is required.", Response.Status.BAD_REQUEST);
        }
        Path requestedPath = Path.of(relativePath);
        if (requestedPath.isAbsolute()) {
            throw error("Mapping file path must be relative.", Response.Status.BAD_REQUEST);
        }

        Path mockRoot = mappingsRoot().resolve(mockId).normalize();
        ensureInside(mappingsRoot(), mockRoot);
        Path file = mockRoot.resolve(requestedPath).normalize();
        ensureInside(mockRoot, file);
        return file;
    }

    private void ensureEnabled() {
        if (!enabled()) {
            throw error("Persistent mappings storage is disabled.", Response.Status.SERVICE_UNAVAILABLE);
        }
    }

    private boolean enabled() {
        return config.storage().persistent();
    }

    private Path mappingsRoot() {
        return Path.of(config.storage().mappingsPath()).toAbsolutePath().normalize();
    }

    private void validateMockId(String mockId) {
        if (mockId == null || !VALID_MOCK_ID.matcher(mockId).matches()) {
            throw error(MOCK_ID_VALIDATION_MESSAGE, Response.Status.BAD_REQUEST);
        }
    }

    private void ensureInside(Path root, Path path) {
        if (!path.normalize().startsWith(root.normalize())) {
            throw error("Mapping file path escapes mappings root.", Response.Status.BAD_REQUEST);
        }
    }

    private WebApplicationException error(String message, Response.Status status) {
        return new WebApplicationException(Response.status(status)
                .type("text/plain")
                .entity(message)
                .build());
    }

    private String ioErrorMessage(IOException e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return e.getClass().getSimpleName();
        }
        return e.getClass().getSimpleName() + ": " + message;
    }

    public record MappingsView(boolean enabled, List<String> mockIds, String error) {
        public MappingsView(boolean enabled, List<String> mockIds) {
            this(enabled, mockIds, null);
        }
    }

    public record FileNode(String name, String path, boolean directory, List<FileNode> children) {
    }
}
