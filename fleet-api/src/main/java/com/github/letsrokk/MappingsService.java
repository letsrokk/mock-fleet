package com.github.letsrokk;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
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
        List<TraversalEntry> manifest;
        try {
            manifest = discover(mockRoot);
        } catch (TraversalBudget.LimitExceeded e) {
            throw traversalLimit(mockId, e);
        } catch (TraversalStorageException e) {
            throw error(Response.Status.SERVICE_UNAVAILABLE, "MAPPINGS_STORAGE_ERROR",
                    "Unable to list mappings folder: " + ioErrorMessage(e.ioException()), true, false,
                    Map.of("mockId", mockId, "path", e.relativePath()));
        }
        return assembleTree(manifest);
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

        List<TraversalEntry> manifest;
        try {
            manifest = discover(mockRoot);
        } catch (TraversalBudget.LimitExceeded e) {
            throw traversalLimit(mockId, e);
        } catch (TraversalStorageException e) {
            throw error(Response.Status.SERVICE_UNAVAILABLE, "MAPPINGS_STORAGE_ERROR",
                    "Unable to discover mappings folder: " + ioErrorMessage(e.ioException()), true, false,
                    Map.of("mockId", mockId));
        }

        List<TraversalEntry> deleteOrder = manifest.stream()
                .sorted(Comparator.comparingInt(TraversalEntry::relativeDepth).reversed())
                .toList();
        try {
            for (TraversalEntry entry : deleteOrder) {
                deleteMappingPath(entry);
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

    private void deleteMappingPath(TraversalEntry entry) throws IOException {
        if (!entry.directory()) {
            Files.deleteIfExists(entry.path());
            return;
        }
        try {
            Files.deleteIfExists(entry.path());
        } catch (IOException e) {
            // S3-style mounts can reject rmdir for virtual directories after all objects are removed.
            if (Files.isDirectory(entry.path(), LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            throw e;
        }
    }

    private List<TraversalEntry> discover(Path mockRoot) {
        TraversalBudget budget = new TraversalBudget(config.mappings().maxDepth(), config.mappings().maxEntries());
        List<TraversalEntry> manifest = new ArrayList<>();
        Deque<TraversalEntry> directories = new ArrayDeque<>();
        TraversalEntry root = new TraversalEntry(mockRoot, "", 0, true);
        budget.visit(root.relativeDepth());
        manifest.add(root);
        directories.add(root);

        while (!directories.isEmpty()) {
            TraversalEntry directory = directories.removeFirst();
            try (DirectoryStream<Path> children = Files.newDirectoryStream(directory.path())) {
                for (Path child : children) {
                    BasicFileAttributes attributes = Files.readAttributes(child, BasicFileAttributes.class,
                            LinkOption.NOFOLLOW_LINKS);
                    if (!attributes.isDirectory() && !attributes.isRegularFile()) {
                        continue;
                    }

                    int relativeDepth = directory.relativeDepth() + 1;
                    budget.visit(relativeDepth);
                    TraversalEntry entry = new TraversalEntry(child,
                            mockRoot.relativize(child).toString().replace('\\', '/'),
                            relativeDepth, attributes.isDirectory());
                    manifest.add(entry);
                    if (entry.directory()) {
                        directories.addLast(entry);
                    }
                }
            } catch (DirectoryIteratorException e) {
                throw new TraversalStorageException(directory.relativePath(), e.getCause());
            } catch (IOException e) {
                throw new TraversalStorageException(directory.relativePath(), e);
            }
        }
        return List.copyOf(manifest);
    }

    private FileNode assembleTree(List<TraversalEntry> manifest) {
        Map<Path, List<TraversalEntry>> childrenByParent = new HashMap<>();
        for (int index = 1; index < manifest.size(); index++) {
            TraversalEntry entry = manifest.get(index);
            childrenByParent.computeIfAbsent(entry.path().getParent(), ignored -> new ArrayList<>()).add(entry);
        }

        Comparator<TraversalEntry> childOrder = Comparator
                .comparing((TraversalEntry entry) -> !entry.directory())
                .thenComparing(entry -> entry.path().getFileName().toString());
        Map<Path, FileNode> nodes = new HashMap<>();
        List<TraversalEntry> deepestFirst = manifest.stream()
                .sorted(Comparator.comparingInt(TraversalEntry::relativeDepth).reversed())
                .toList();
        for (TraversalEntry entry : deepestFirst) {
            List<FileNode> children = childrenByParent.getOrDefault(entry.path(), List.of()).stream()
                    .sorted(childOrder)
                    .map(child -> nodes.get(child.path()))
                    .toList();
            nodes.put(entry.path(), new FileNode(entry.path().getFileName().toString(), entry.relativePath(),
                    entry.directory(), children));
        }
        return nodes.get(manifest.getFirst().path());
    }

    private ApiException traversalLimit(String mockId, TraversalBudget.LimitExceeded e) {
        return error(Response.Status.BAD_REQUEST, "MAPPINGS_TRAVERSAL_LIMIT",
                "Mappings traversal exceeds configured " + e.limit() + " of " + e.maximum() + ".",
                false, false,
                Map.of("mockId", mockId, "limit", e.limit(), "maximum", e.maximum()));
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

    private record TraversalEntry(Path path, String relativePath, int relativeDepth, boolean directory) {
    }

    private static final class TraversalStorageException extends RuntimeException {

        private final String relativePath;

        TraversalStorageException(String relativePath, IOException cause) {
            super(cause);
            this.relativePath = relativePath;
        }

        String relativePath() {
            return relativePath;
        }

        IOException ioException() {
            return (IOException) getCause();
        }
    }
}
