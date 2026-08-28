package com.github.letsrokk;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@ApplicationScoped
public class MappingsService {

    private static final Pattern VALID_MOCK_ID = Pattern.compile("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$");
    static final String MOCK_ID_VALIDATION_MESSAGE = "Mock id must contain 1-63 lowercase letters, numbers, or hyphens, and must start and end with a letter or number.";

    @Inject
    MockFleetConfig config;

    MappingsView view() {
        RoutingView routing = routingView();
        if (!enabled()) {
            return new MappingsView(false, List.of(), null, routing);
        }

        Path root = mappingsRoot();
        if (!Files.isDirectory(root)) {
            return new MappingsView(true, List.of(), null, routing);
        }

        try (Stream<Path> children = Files.list(root)) {
            List<Path> mockDirectories = children
                    .filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .toList();
            List<String> mockIds = new ArrayList<>();
            for (Path directory : mockDirectories) {
                String mockId = directory.getFileName().toString();
                if (VALID_MOCK_ID.matcher(mockId).matches() && containsMappingFile(directory)) {
                    mockIds.add(mockId);
                }
            }
            mockIds.sort(String::compareTo);
            return new MappingsView(true, mockIds, null, routing);
        } catch (IOException e) {
            return new MappingsView(true, List.of(), "Unable to list mappings root: " + ioErrorMessage(e), routing);
        }
    }

    private boolean containsMappingFile(Path directory) throws IOException {
        try (Stream<Path> paths = Files.walk(directory)) {
            return paths.anyMatch(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS));
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    FileNode tree(String mockId) {
        ensureEnabled();
        validateMockId(mockId);
        Path mockRoot = mappingsRoot().resolve(mockId).normalize();
        ensureInside(mappingsRoot(), mockRoot);
        if (!Files.isDirectory(mockRoot)) {
            throw error(Response.Status.NOT_FOUND, "MAPPING_FOLDER_NOT_FOUND", "Mappings folder not found.",
                    false, false, Map.of("mockId", mockId));
        }
        return node(mockRoot, mockRoot);
    }

    Path file(String mockId, String relativePath) {
        Path file = resolveFile(mockId, relativePath);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw error(Response.Status.NOT_FOUND, "MAPPING_FILE_NOT_FOUND", "Mapping file not found.",
                    false, false, mappingDetails(mockId, relativePath));
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
                throw error(Response.Status.NOT_FOUND, "MAPPING_FILE_NOT_FOUND", "Mapping file not found.",
                        false, false, mappingDetails(mockId, relativePath));
            }
        } catch (IOException e) {
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            throw error(Response.Status.SERVICE_UNAVAILABLE, "MAPPINGS_STORAGE_ERROR",
                    "Unable to delete mapping file: " + ioErrorMessage(e), true, false,
                    mappingDetails(mockId, relativePath));
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
            throw error(Response.Status.NOT_FOUND, "MAPPING_FOLDER_NOT_FOUND", "Mappings folder not found.",
                    false, false, Map.of("mockId", mockId));
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
            throw error(Response.Status.SERVICE_UNAVAILABLE, "MAPPINGS_STORAGE_ERROR",
                    "Unable to delete mappings folder: " + ioErrorMessage(e), true, true,
                    Map.of("mockId", mockId));
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
            throw error(Response.Status.SERVICE_UNAVAILABLE, "MAPPINGS_STORAGE_ERROR",
                    "Unable to list mappings folder: " + ioErrorMessage(e), true, false,
                    Map.of("mockId", mockRoot.getFileName().toString(), "path", relativePath));
        }
    }

    private Path resolveFile(String mockId, String relativePath) {
        ensureEnabled();
        validateMockId(mockId);
        if (relativePath == null || relativePath.isBlank()) {
            throw error(Response.Status.BAD_REQUEST, "INVALID_MAPPING_PATH", "Mapping file path is required.",
                    false, false, Map.of("mockId", mockId));
        }
        Path requestedPath = Path.of(relativePath);
        if (requestedPath.isAbsolute()) {
            throw error(Response.Status.BAD_REQUEST, "INVALID_MAPPING_PATH", "Mapping file path must be relative.",
                    false, false, mappingDetails(mockId, relativePath));
        }

        Path mockRoot = mappingsRoot().resolve(mockId).normalize();
        ensureInside(mappingsRoot(), mockRoot);
        Path file = mockRoot.resolve(requestedPath).normalize();
        ensureInside(mockRoot, file, mockId, relativePath);
        return file;
    }

    private void ensureEnabled() {
        if (!enabled()) {
            throw error(Response.Status.SERVICE_UNAVAILABLE, "MAPPINGS_STORAGE_DISABLED",
                    "Persistent mappings storage is disabled.", false, false, Map.of());
        }
    }

    private boolean enabled() {
        return config.storage().persistent();
    }

    private Path mappingsRoot() {
        return Path.of(config.storage().mappingsPath()).toAbsolutePath().normalize();
    }

    private RoutingView routingView() {
        MockFleetConfig.RoutingConfig routing = config.proxy().routing();
        return new RoutingView(routing.mode().name(), routing.host());
    }

    private void validateMockId(String mockId) {
        if (mockId == null || !VALID_MOCK_ID.matcher(mockId).matches()) {
            Map<String, Object> details = mockId == null ? Map.of() : Map.of("mockId", mockId);
            throw error(Response.Status.BAD_REQUEST, "INVALID_MOCK_ID", MOCK_ID_VALIDATION_MESSAGE,
                    false, false, details);
        }
    }

    private void ensureInside(Path root, Path path) {
        ensureInside(root, path, null, null);
    }

    private void ensureInside(Path root, Path path, String mockId, String relativePath) {
        if (!path.normalize().startsWith(root.normalize())) {
            throw error(Response.Status.BAD_REQUEST, "INVALID_MAPPING_PATH",
                    "Mapping file path escapes mappings root.", false, false,
                    mappingDetails(mockId, relativePath));
        }
    }

    private Map<String, Object> mappingDetails(String mockId, String relativePath) {
        if (mockId == null) {
            return Map.of();
        }
        if (relativePath == null) {
            return Map.of("mockId", mockId);
        }
        return Map.of("mockId", mockId, "path", relativePath);
    }

    private ApiException error(Response.Status status, String code, String message, boolean retryable,
                               boolean stateMayHaveChanged, Map<String, Object> details) {
        return new ApiException(status, new ApiError(code, message, retryable, stateMayHaveChanged, details));
    }

    private String ioErrorMessage(IOException e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return e.getClass().getSimpleName();
        }
        return e.getClass().getSimpleName() + ": " + message;
    }

    public record MappingsView(boolean enabled, List<String> mockIds, String error, RoutingView routing) {
        public MappingsView(boolean enabled, List<String> mockIds) {
            this(enabled, mockIds, null, null);
        }

        public MappingsView(boolean enabled, List<String> mockIds, String error) {
            this(enabled, mockIds, error, null);
        }
    }

    public record FileNode(String name, String path, boolean directory, List<FileNode> children) {
    }

    public record RoutingView(String mode, String host) {
    }
}
