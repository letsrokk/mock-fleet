package com.github.letsrokk;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
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
import java.util.Set;
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
        BasicFileAttributes rootAttributes;
        try {
            rootAttributes = Files.readAttributes(mockRoot, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException e) {
            throw error(Response.Status.NOT_FOUND, "MAPPING_FOLDER_NOT_FOUND", "Mappings folder not found.",
                    false, false, Map.of("mockId", mockId));
        } catch (IOException e) {
            throw error(Response.Status.SERVICE_UNAVAILABLE, "MAPPINGS_STORAGE_ERROR",
                    "Unable to inspect mappings folder: " + ioErrorMessage(e), true, false,
                    Map.of("mockId", mockId, "path", ""));
        }
        if (!rootAttributes.isDirectory()) {
            if (rootAttributes.isSymbolicLink()) {
                throw invalidMappingRoot(mockId, true);
            }
            throw error(Response.Status.NOT_FOUND, "MAPPING_FOLDER_NOT_FOUND", "Mappings folder not found.",
                    false, false, Map.of("mockId", mockId));
        }

        TraversalEntry root = entry(mockRoot, "", 0, rootAttributes);
        try {
            List<TraversalEntry> manifest;
            try (SecureTraversalSession session = openSecureRoot(root)) {
                manifest = discover(session, root);
                validateManifest(session, manifest);
            }
            return assembleTree(manifest);
        } catch (TraversalBudget.LimitExceeded e) {
            throw traversalLimit(mockId, e);
        } catch (TraversalStorageException e) {
            throw error(Response.Status.SERVICE_UNAVAILABLE, "MAPPINGS_STORAGE_ERROR",
                    "Unable to list mappings folder: " + ioErrorMessage(e.ioException()), true, false,
                    Map.of("mockId", mockId, "path", e.relativePath()));
        } catch (IOException e) {
            throw error(Response.Status.SERVICE_UNAVAILABLE, "MAPPINGS_STORAGE_ERROR",
                    "Mappings folder changed during discovery: " + ioErrorMessage(e), true, false,
                    Map.of("mockId", mockId, "path", ""));
        }
    }

    OpenedFile file(String mockId, String relativePath) {
        ResolvedFile file = resolveFile(mockId, relativePath);
        OpenedFile opened = null;
        try (SecureFileParent parent = openSecureFileParent(file)) {
            BasicFileAttributes attributes = secureAttributes(parent.stream(), parent.name());
            if (!attributes.isRegularFile()) {
                throw new MappingFileMissingException();
            }
            EntryIdentity identity = EntryIdentity.from(attributes);
            Set<OpenOption> options = Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
            SeekableByteChannel channel = parent.stream().newByteChannel(parent.name(), options);
            try {
                validateIdentity(identity, secureAttributes(parent.stream(), parent.name()));
            } catch (IOException | RuntimeException e) {
                channel.close();
                throw e;
            }
            opened = new OpenedFile(file.path().getFileName().toString(), channel);
        } catch (MappingFileMissingException | NoSuchFileException e) {
            closeOpenedFile(opened);
            throw mappingFileNotFound(mockId, relativePath);
        } catch (DirectoryIteratorException e) {
            closeOpenedFile(opened);
            throw mappingFileStorageError(mockId, relativePath, e.getCause());
        } catch (IOException e) {
            closeOpenedFile(opened);
            throw mappingFileStorageError(mockId, relativePath, e);
        }
        return opened;
    }

    void deleteFile(String mockId, String relativePath) {
        ResolvedFile file = resolveFile(mockId, relativePath);
        try (SecureFileParent parent = openSecureFileParent(file)) {
            BasicFileAttributes attributes = secureAttributes(parent.stream(), parent.name());
            if (!attributes.isRegularFile()) {
                throw new MappingFileMissingException();
            }
            parent.stream().deleteFile(parent.name());
        } catch (MappingFileMissingException e) {
            throw mappingFileNotFound(mockId, relativePath);
        } catch (NoSuchFileException e) {
            return;
        } catch (DirectoryIteratorException e) {
            throw mappingFileStorageError(mockId, relativePath, e.getCause());
        } catch (IOException e) {
            throw mappingFileStorageError(mockId, relativePath, e);
        }
    }

    void deleteFolder(String mockId) {
        ensureEnabled();
        validateMockId(mockId);
        Path root = mappingsRoot();
        Path mockRoot = root.resolve(mockId).normalize();
        ensureInside(root, mockRoot);
        BasicFileAttributes rootAttributes;
        try {
            rootAttributes = Files.readAttributes(mockRoot, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException e) {
            return;
        } catch (IOException e) {
            throw error(Response.Status.SERVICE_UNAVAILABLE, "MAPPINGS_STORAGE_ERROR",
                    "Unable to inspect mappings folder: " + ioErrorMessage(e), true, false,
                    Map.of("mockId", mockId));
        }
        if (!rootAttributes.isDirectory()) {
            if (rootAttributes.isSymbolicLink()) {
                throw invalidMappingRoot(mockId, false);
            }
            throw error(Response.Status.NOT_FOUND, "MAPPING_FOLDER_NOT_FOUND", "Mappings folder not found.",
                    false, false, Map.of("mockId", mockId));
        }

        TraversalEntry rootEntry = entry(mockRoot, "", 0, rootAttributes);
        boolean deletionStarted = false;
        try {
            try (SecureTraversalSession session = openSecureRoot(rootEntry)) {
                List<TraversalEntry> manifest = discover(session, rootEntry);
                validateManifest(session, manifest);

                List<TraversalEntry> deleteOrder = manifest.stream()
                        .sorted(Comparator.comparingInt(TraversalEntry::relativeDepth).reversed())
                        .toList();
                Map<Path, TraversalEntry> entriesByPath = entriesByPath(manifest);
                deletionStarted = true;
                for (TraversalEntry entry : deleteOrder) {
                    deleteMappingPath(session, entry, entriesByPath);
                }
            }
        } catch (TraversalBudget.LimitExceeded e) {
            throw traversalLimit(mockId, e);
        } catch (TraversalStorageException e) {
            throw error(Response.Status.SERVICE_UNAVAILABLE, "MAPPINGS_STORAGE_ERROR",
                    "Unable to discover mappings folder: " + ioErrorMessage(e.ioException()), true, false,
                    Map.of("mockId", mockId));
        } catch (IOException e) {
            String message = deletionStarted
                    ? "Unable to delete mappings folder: "
                    : "Mappings folder changed during discovery: ";
            throw error(Response.Status.SERVICE_UNAVAILABLE, "MAPPINGS_STORAGE_ERROR",
                    message + ioErrorMessage(e), true, deletionStarted,
                    Map.of("mockId", mockId));
        }
    }

    private void deleteMappingPath(SecureTraversalSession session, TraversalEntry entry,
                                   Map<Path, TraversalEntry> entriesByPath) throws IOException {
        withSecureEntry(session, entry, entriesByPath, (parent, name) -> {
            if (entry.directory()) {
                parent.deleteDirectory(name);
            } else {
                parent.deleteFile(name);
            }
        });
    }

    private List<TraversalEntry> discover(SecureTraversalSession session, TraversalEntry root) {
        TraversalBudget budget = new TraversalBudget(config.mappings().maxDepth(), config.mappings().maxEntries());
        List<TraversalEntry> manifest = new ArrayList<>();
        budget.visit(0);
        manifest.add(root);
        discoverSecure(root.path(), root, session.root(), budget, manifest);
        return List.copyOf(manifest);
    }

    private SecureTraversalSession openSecureRoot(TraversalEntry root) throws IOException {
        // Kubernetes exposes both ordinary PVCs and the supported S3 CSI volume through the Linux VFS. The
        // default Unix NIO provider therefore supplies openat-backed secure handles; custom providers that do
        // not supply the same no-follow guarantee must fail before the mock directory is enumerated.
        DirectoryStream<Path> openedParent = openTrustedDirectory(root.path().getParent());
        if (!(openedParent instanceof SecureDirectoryStream<Path> secureParent)) {
            IOException unsupported = new IOException(
                    "Mappings storage provider does not support secure relative directory handles.");
            try {
                openedParent.close();
            } catch (IOException closeError) {
                unsupported.addSuppressed(closeError);
            }
            throw unsupported;
        }

        SecureDirectoryStream<Path> secureRoot = null;
        try {
            Path rootName = root.path().getFileName();
            validateIdentity(root.identity(), secureAttributes(secureParent, rootName));
            secureRoot = secureParent.newDirectoryStream(rootName, LinkOption.NOFOLLOW_LINKS);
            validateIdentity(root.identity(), secureAttributes(secureRoot));
            return new SecureTraversalSession(root, secureParent, secureRoot);
        } catch (IOException | RuntimeException e) {
            if (secureRoot != null) {
                try {
                    secureRoot.close();
                } catch (IOException closeError) {
                    e.addSuppressed(closeError);
                }
            }
            try {
                secureParent.close();
            } catch (IOException closeError) {
                e.addSuppressed(closeError);
            }
            throw e;
        }
    }

    private void discoverSecure(Path mockRoot, TraversalEntry root, SecureDirectoryStream<Path> rootStream,
                                TraversalBudget budget, List<TraversalEntry> manifest) {
        Deque<SecureTraversalFrame> directories = new ArrayDeque<>();
        try {
            directories.addLast(new SecureTraversalFrame(root, rootStream, rootStream.iterator(), false));
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
                    if (frame.closeOnComplete()) {
                        close(frame.directory(), frame.stream());
                    }
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
                    Iterator<Path> childIterator;
                    try {
                        childStream = frame.stream().newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS);
                        validateIdentity(entry.identity(), secureAttributes(childStream));
                        childIterator = childStream.iterator();
                    } catch (IOException e) {
                        if (childStream != null) {
                            try {
                                childStream.close();
                            } catch (IOException closeError) {
                                e.addSuppressed(closeError);
                            }
                        }
                        throw new TraversalStorageException(entry.relativePath(), e);
                    } catch (RuntimeException e) {
                        if (childStream != null) {
                            try {
                                childStream.close();
                            } catch (IOException closeError) {
                                e.addSuppressed(closeError);
                            }
                        }
                        throw e;
                    }
                    directories.addLast(new SecureTraversalFrame(entry, childStream, childIterator, true));
                }
            }
        } catch (IOException e) {
            throw new TraversalStorageException(root.relativePath(), e);
        } finally {
            closeRemaining(directories);
        }
    }

    private BasicFileAttributes secureAttributes(SecureDirectoryStream<Path> stream) throws IOException {
        BasicFileAttributeView view = stream.getFileAttributeView(BasicFileAttributeView.class);
        if (view == null) {
            throw new IOException("Basic file attributes are unavailable for an open mappings directory.");
        }
        return view.readAttributes();
    }

    DirectoryStream<Path> openTrustedDirectory(Path directory) throws IOException {
        return Files.newDirectoryStream(directory);
    }

    private BasicFileAttributes secureAttributes(SecureDirectoryStream<Path> stream, Path name) throws IOException {
        BasicFileAttributeView view = stream.getFileAttributeView(name, BasicFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        if (view == null) {
            throw new IOException("Basic file attributes are unavailable for a mappings entry.");
        }
        return view.readAttributes();
    }

    private void validateManifest(SecureTraversalSession session, List<TraversalEntry> manifest) throws IOException {
        Map<Path, TraversalEntry> entriesByPath = entriesByPath(manifest);
        for (TraversalEntry entry : manifest) {
            withSecureEntry(session, entry, entriesByPath, (parent, name) -> { });
        }
    }

    private Map<Path, TraversalEntry> entriesByPath(List<TraversalEntry> manifest) {
        Map<Path, TraversalEntry> entriesByPath = new HashMap<>();
        for (TraversalEntry entry : manifest) {
            entriesByPath.put(entry.path(), entry);
        }
        return entriesByPath;
    }

    private void withSecureEntry(SecureTraversalSession session, TraversalEntry entry,
                                 Map<Path, TraversalEntry> entriesByPath,
                                 SecureEntryOperation operation) throws IOException {
        if (entry.relativeDepth() == 0) {
            Path rootName = entry.path().getFileName();
            validateIdentity(entry.identity(), secureAttributes(session.parent(), rootName));
            validateIdentity(entry.identity(), secureAttributes(session.root()));
            operation.apply(session.parent(), rootName);
            return;
        }

        Path relative = session.rootEntry().path().relativize(entry.path());
        SecureDirectoryStream<Path> current = session.root();
        Deque<SecureDirectoryStream<Path>> opened = new ArrayDeque<>();
        IOException failure = null;
        try {
            Path currentPath = session.rootEntry().path();
            for (int index = 0; index < relative.getNameCount() - 1; index++) {
                Path name = relative.getName(index);
                currentPath = currentPath.resolve(name).normalize();
                TraversalEntry ancestor = entriesByPath.get(currentPath);
                if (ancestor == null || !ancestor.directory()) {
                    throw new IOException("Mappings manifest has an invalid directory ancestry.");
                }
                SecureDirectoryStream<Path> child = current.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS);
                opened.addLast(child);
                validateIdentity(ancestor.identity(), secureAttributes(child));
                current = child;
            }

            Path name = relative.getFileName();
            validateIdentity(entry.identity(), secureAttributes(current, name));
            operation.apply(current, name);
        } catch (IOException e) {
            failure = e;
            throw e;
        } finally {
            IOException closeFailure = closeOpened(opened);
            if (closeFailure != null) {
                if (failure != null) {
                    failure.addSuppressed(closeFailure);
                } else {
                    throw closeFailure;
                }
            }
        }
    }

    private IOException closeOpened(Deque<SecureDirectoryStream<Path>> opened) {
        IOException failure = null;
        while (!opened.isEmpty()) {
            try {
                opened.removeLast().close();
            } catch (IOException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        return failure;
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
            SecureTraversalFrame frame = directories.removeLast();
            if (!frame.closeOnComplete()) {
                continue;
            }
            try {
                frame.stream().close();
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

    private ResolvedFile resolveFile(String mockId, String relativePath) {
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
        if (file.equals(mockRoot)) {
            throw error(Response.Status.BAD_REQUEST, "INVALID_MAPPING_PATH", "Mapping file path is required.",
                    false, false, mappingDetails(mockId, relativePath));
        }
        return new ResolvedFile(mockRoot, file, mockRoot.relativize(file));
    }

    private SecureFileParent openSecureFileParent(ResolvedFile file) throws IOException {
        BasicFileAttributes rootAttributes;
        try {
            rootAttributes = Files.readAttributes(file.mockRoot(), BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException e) {
            throw new MappingFileMissingException();
        }
        if (!rootAttributes.isDirectory()) {
            throw new MappingFileMissingException();
        }

        TraversalEntry root = entry(file.mockRoot(), "", 0, rootAttributes);
        SecureTraversalSession session = openSecureRoot(root);
        Deque<SecureDirectoryStream<Path>> opened = new ArrayDeque<>();
        SecureDirectoryStream<Path> current = session.root();
        try {
            for (int index = 0; index < file.relative().getNameCount() - 1; index++) {
                Path name = file.relative().getName(index);
                BasicFileAttributes attributes = secureAttributes(current, name);
                if (!attributes.isDirectory()) {
                    throw new MappingFileMissingException();
                }
                EntryIdentity identity = EntryIdentity.from(attributes);
                SecureDirectoryStream<Path> child = current.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS);
                opened.addLast(child);
                validateIdentity(identity, secureAttributes(child));
                current = child;
            }
            return new SecureFileParent(session, opened, current, file.relative().getFileName());
        } catch (IOException | RuntimeException e) {
            IOException closeFailure = closeOpened(opened);
            try {
                session.close();
            } catch (IOException sessionCloseFailure) {
                if (closeFailure == null) {
                    closeFailure = sessionCloseFailure;
                } else {
                    closeFailure.addSuppressed(sessionCloseFailure);
                }
            }
            if (closeFailure != null) {
                e.addSuppressed(closeFailure);
            }
            throw e;
        }
    }

    private ApiException mappingFileNotFound(String mockId, String relativePath) {
        return error(Response.Status.NOT_FOUND, "MAPPING_FILE_NOT_FOUND", "Mapping file not found.",
                false, false, mappingDetails(mockId, relativePath));
    }

    private ApiException mappingFileStorageError(String mockId, String relativePath, IOException error) {
        return error(Response.Status.SERVICE_UNAVAILABLE, "MAPPINGS_STORAGE_ERROR",
                "Unable to access mapping file: " + ioErrorMessage(error), true, false,
                mappingDetails(mockId, relativePath));
    }

    private void closeOpenedFile(OpenedFile opened) {
        if (opened == null) {
            return;
        }
        try {
            opened.close();
        } catch (IOException ignored) {
            // Preserve the storage error that prevented the file handle from being returned.
        }
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

    public record OpenedFile(String fileName, SeekableByteChannel channel) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            channel.close();
        }
    }

    public record RoutingView(String mode, String host) {
    }

    private record TraversalEntry(Path path, String relativePath, int relativeDepth, boolean directory,
                                  EntryIdentity identity) {
    }

    private record ResolvedFile(Path mockRoot, Path path, Path relative) {
    }

    private static final class SecureFileParent implements AutoCloseable {

        private final SecureTraversalSession session;
        private final Deque<SecureDirectoryStream<Path>> opened;
        private final SecureDirectoryStream<Path> stream;
        private final Path name;

        private SecureFileParent(SecureTraversalSession session, Deque<SecureDirectoryStream<Path>> opened,
                                 SecureDirectoryStream<Path> stream, Path name) {
            this.session = session;
            this.opened = opened;
            this.stream = stream;
            this.name = name;
        }

        SecureDirectoryStream<Path> stream() {
            return stream;
        }

        Path name() {
            return name;
        }

        @Override
        public void close() throws IOException {
            IOException failure = null;
            while (!opened.isEmpty()) {
                try {
                    opened.removeLast().close();
                } catch (IOException e) {
                    if (failure == null) {
                        failure = e;
                    } else {
                        failure.addSuppressed(e);
                    }
                }
            }
            try {
                session.close();
            } catch (IOException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    private record SecureTraversalFrame(TraversalEntry directory, SecureDirectoryStream<Path> stream,
                                        Iterator<Path> children, boolean closeOnComplete) {
    }

    private record SecureTraversalSession(TraversalEntry rootEntry, SecureDirectoryStream<Path> parent,
                                          SecureDirectoryStream<Path> root) implements AutoCloseable {

        @Override
        public void close() throws IOException {
            IOException failure = null;
            try {
                root.close();
            } catch (IOException e) {
                failure = e;
            }
            try {
                parent.close();
            } catch (IOException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    @FunctionalInterface
    private interface SecureEntryOperation {
        void apply(SecureDirectoryStream<Path> parent, Path name) throws IOException;
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

    private static final class MappingFileMissingException extends IOException {
    }
}
