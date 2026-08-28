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
import java.nio.file.SecureDirectoryStream;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
        if (Files.isSymbolicLink(mockRoot)) {
            throw invalidMappingRoot(mockId, true);
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
                    "Unable to list mappings folder: " + ioErrorMessage(e.ioException()), true, false,
                    Map.of("mockId", mockId, "path", e.relativePath()));
        }
        try {
            validateManifest(manifest);
        } catch (IOException e) {
            throw error(Response.Status.SERVICE_UNAVAILABLE, "MAPPINGS_STORAGE_ERROR",
                    "Mappings folder changed during discovery: " + ioErrorMessage(e), true, false,
                    Map.of("mockId", mockId, "path", ""));
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
        if (Files.isSymbolicLink(mockRoot)) {
            throw invalidMappingRoot(mockId, false);
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

        try {
            validateManifest(manifest);
        } catch (IOException e) {
            throw error(Response.Status.SERVICE_UNAVAILABLE, "MAPPINGS_STORAGE_ERROR",
                    "Mappings folder changed during discovery: " + ioErrorMessage(e), true, false,
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
            throw error(Response.Status.SERVICE_UNAVAILABLE, "MAPPINGS_STORAGE_ERROR",
                    "Unable to delete mappings folder: " + ioErrorMessage(e), true, true,
                    Map.of("mockId", mockId));
        }
    }

    private void deleteMappingPath(TraversalEntry entry) throws IOException {
        validateIdentity(entry);
        Files.delete(entry.path());
    }

    private List<TraversalEntry> discover(Path mockRoot) {
        TraversalBudget budget = new TraversalBudget(config.mappings().maxDepth(), config.mappings().maxEntries());
        List<TraversalEntry> manifest = new ArrayList<>();
        BasicFileAttributes rootAttributes;
        try {
            rootAttributes = Files.readAttributes(mockRoot, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException e) {
            throw new TraversalStorageException("", e);
        }
        if (!rootAttributes.isDirectory()) {
            throw new TraversalStorageException("",
                    new IOException("Mappings root is not a directory or changed during traversal."));
        }

        TraversalEntry root = entry(mockRoot, "", 0, rootAttributes);
        budget.visit(0);
        manifest.add(root);
        DirectoryStream<Path> rootStream;
        try {
            rootStream = Files.newDirectoryStream(mockRoot);
        } catch (IOException e) {
            throw new TraversalStorageException("", e);
        }

        if (rootStream instanceof SecureDirectoryStream<Path> secureRoot) {
            discoverSecure(mockRoot, root, secureRoot, budget, manifest);
        } else {
            // S3 CSI providers may expose only the mandatory DirectoryStream API. The fallback verifies the
            // no-follow identity before and after every directory open instead of assuming path stability.
            discoverChecked(mockRoot, root, rootStream, budget, manifest);
        }
        return List.copyOf(manifest);
    }

    private void discoverSecure(Path mockRoot, TraversalEntry root, SecureDirectoryStream<Path> rootStream,
                                TraversalBudget budget, List<TraversalEntry> manifest) {
        Deque<SecureTraversalFrame> directories = new ArrayDeque<>();
        try {
            directories.addLast(new SecureTraversalFrame(root, rootStream, rootStream.iterator()));
            validateIdentity(root.identity(), secureAttributes(rootStream));
            while (!directories.isEmpty()) {
                SecureTraversalFrame frame = directories.getLast();
                boolean hasNext;
                try {
                    hasNext = frame.children().hasNext();
                } catch (DirectoryIteratorException e) {
                    throw new TraversalStorageException(frame.directory().relativePath(), e.getCause());
                }
                if (!hasNext) {
                    directories.removeLast();
                    close(frame.directory(), frame.stream());
                    continue;
                }

                Path listedChild;
                try {
                    listedChild = frame.children().next();
                } catch (DirectoryIteratorException e) {
                    throw new TraversalStorageException(frame.directory().relativePath(), e.getCause());
                }
                Path name = listedChild.getFileName();
                int relativeDepth = frame.directory().relativeDepth() + 1;
                budget.visit(relativeDepth);
                BasicFileAttributes attributes;
                try {
                    attributes = secureAttributes(frame.stream(), name);
                } catch (IOException e) {
                    throw new TraversalStorageException(frame.directory().relativePath(), e);
                }
                if (!attributes.isDirectory() && !attributes.isRegularFile()) {
                    continue;
                }

                Path child = frame.directory().path().resolve(name).normalize();
                ensureDiscoveredInside(mockRoot, child);
                TraversalEntry entry = entry(child, relativePath(mockRoot, child), relativeDepth, attributes);
                manifest.add(entry);
                if (entry.directory()) {
                    SecureDirectoryStream<Path> childStream = null;
                    try {
                        childStream = frame.stream().newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS);
                        validateIdentity(entry.identity(), secureAttributes(childStream));
                    } catch (IOException e) {
                        if (childStream != null) {
                            try {
                                childStream.close();
                            } catch (IOException closeError) {
                                e.addSuppressed(closeError);
                            }
                        }
                        throw new TraversalStorageException(entry.relativePath(), e);
                    }
                    directories.addLast(new SecureTraversalFrame(entry, childStream, childStream.iterator()));
                }
            }
        } catch (IOException e) {
            throw new TraversalStorageException(root.relativePath(), e);
        } finally {
            closeRemaining(directories);
        }
    }

    private void discoverChecked(Path mockRoot, TraversalEntry root, DirectoryStream<Path> rootStream,
                                 TraversalBudget budget, List<TraversalEntry> manifest) {
        Deque<TraversalEntry> directories = new ArrayDeque<>();
        directories.add(root);
        DirectoryStream<Path> children = rootStream;
        try {
            validateIdentity(root);
        } catch (IOException e) {
            try {
                rootStream.close();
            } catch (IOException closeError) {
                e.addSuppressed(closeError);
            }
            throw new TraversalStorageException(root.relativePath(), e);
        }
        while (!directories.isEmpty()) {
            TraversalEntry directory = directories.removeFirst();
            try (DirectoryStream<Path> opened = children == null ? openChecked(directory) : children) {
                children = null;
                for (Path listedChild : opened) {
                    int relativeDepth = directory.relativeDepth() + 1;
                    budget.visit(relativeDepth);
                    Path child = directory.path().resolve(listedChild.getFileName()).normalize();
                    ensureDiscoveredInside(mockRoot, child);
                    BasicFileAttributes attributes = Files.readAttributes(child, BasicFileAttributes.class,
                            LinkOption.NOFOLLOW_LINKS);
                    if (!attributes.isDirectory() && !attributes.isRegularFile()) {
                        continue;
                    }

                    TraversalEntry entry = entry(child, relativePath(mockRoot, child), relativeDepth, attributes);
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
    }

    private DirectoryStream<Path> openChecked(TraversalEntry directory) throws IOException {
        validateIdentity(directory);
        DirectoryStream<Path> stream = Files.newDirectoryStream(directory.path());
        try {
            validateIdentity(directory);
            return stream;
        } catch (IOException e) {
            stream.close();
            throw e;
        }
    }

    private BasicFileAttributes secureAttributes(SecureDirectoryStream<Path> stream) throws IOException {
        BasicFileAttributeView view = stream.getFileAttributeView(BasicFileAttributeView.class);
        if (view == null) {
            throw new IOException("Basic file attributes are unavailable for an open mappings directory.");
        }
        return view.readAttributes();
    }

    private BasicFileAttributes secureAttributes(SecureDirectoryStream<Path> stream, Path name) throws IOException {
        BasicFileAttributeView view = stream.getFileAttributeView(name, BasicFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        if (view == null) {
            throw new IOException("Basic file attributes are unavailable for a mappings entry.");
        }
        return view.readAttributes();
    }

    private void validateManifest(List<TraversalEntry> manifest) throws IOException {
        for (TraversalEntry entry : manifest) {
            validateIdentity(entry);
        }
    }

    private void validateIdentity(TraversalEntry entry) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(entry.path(), BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        validateIdentity(entry.identity(), attributes);
    }

    private void validateIdentity(EntryIdentity expected, BasicFileAttributes actual) throws IOException {
        if (!expected.matches(actual)) {
            throw new IOException("Mappings entry identity or type changed during traversal.");
        }
    }

    private TraversalEntry entry(Path path, String relativePath, int relativeDepth,
                                 BasicFileAttributes attributes) {
        return new TraversalEntry(path, relativePath, relativeDepth, attributes.isDirectory(),
                EntryIdentity.from(attributes));
    }

    private String relativePath(Path mockRoot, Path path) {
        return mockRoot.relativize(path).toString().replace('\\', '/');
    }

    private void ensureDiscoveredInside(Path mockRoot, Path child) {
        if (!child.startsWith(mockRoot)) {
            throw new TraversalStorageException("",
                    new IOException("Mappings directory returned an entry outside the mappings root."));
        }
    }

    private void close(TraversalEntry directory, DirectoryStream<Path> stream) {
        try {
            stream.close();
        } catch (IOException e) {
            throw new TraversalStorageException(directory.relativePath(), e);
        }
    }

    private void closeRemaining(Deque<SecureTraversalFrame> directories) {
        while (!directories.isEmpty()) {
            try {
                directories.removeLast().stream().close();
            } catch (IOException ignored) {
                // Preserve the traversal failure that caused cleanup.
            }
        }
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

    private ApiException invalidMappingRoot(String mockId, boolean includePath) {
        Map<String, Object> details = includePath
                ? Map.of("mockId", mockId, "path", "")
                : Map.of("mockId", mockId);
        return error(Response.Status.SERVICE_UNAVAILABLE, "MAPPINGS_STORAGE_ERROR",
                "Mappings root must be a real directory, not a symbolic link.", true, false, details);
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

    private record TraversalEntry(Path path, String relativePath, int relativeDepth, boolean directory,
                                  EntryIdentity identity) {
    }

    private record SecureTraversalFrame(TraversalEntry directory, SecureDirectoryStream<Path> stream,
                                        Iterator<Path> children) {
    }

    private record EntryIdentity(boolean directory, boolean regularFile, Object fileKey, long size,
                                 FileTime modifiedTime, FileTime creationTime) {

        static EntryIdentity from(BasicFileAttributes attributes) {
            return new EntryIdentity(attributes.isDirectory(), attributes.isRegularFile(), attributes.fileKey(),
                    attributes.size(), attributes.lastModifiedTime(), attributes.creationTime());
        }

        boolean matches(BasicFileAttributes attributes) {
            if (directory != attributes.isDirectory() || regularFile != attributes.isRegularFile()) {
                return false;
            }
            if (fileKey != null || attributes.fileKey() != null) {
                return Objects.equals(fileKey, attributes.fileKey());
            }
            // Some object-backed providers do not expose file keys; retain a fail-closed Basic-attribute
            // fingerprint so that the same traversal remains usable on those mounts.
            return size == attributes.size()
                    && Objects.equals(modifiedTime, attributes.lastModifiedTime())
                    && Objects.equals(creationTime, attributes.creationTime());
        }
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
