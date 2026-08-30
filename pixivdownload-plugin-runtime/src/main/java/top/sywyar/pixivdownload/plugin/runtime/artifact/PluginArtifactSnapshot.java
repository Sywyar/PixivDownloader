package top.sywyar.pixivdownload.plugin.runtime.artifact;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.sywyar.pixivdownload.plugin.runtime.lifecycle.PluginRuntimeOperationException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.DirectoryStream;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.UserPrincipal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

/**
 * 生产插件加载使用的私有 artifact 快照。原始安装路径只在这里以 NOFOLLOW channel 读取一次；后续结构校验、
 * provenance 复验与 runtime 物化都只消费同一快照，避免验签后再次按公开路径打开不同字节。
 */
public final class PluginArtifactSnapshot implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PluginArtifactSnapshot.class);
    private static final int COPY_BUFFER_BYTES = 64 * 1024;
    private static final int MAX_WORKSPACE_ENTRIES = 25_000;
    private static final int MAX_WORKER_DIRECTORIES = 16;
    private static final String WORKSPACE_PREFIX = ".artifact-snapshot-";
    private static final String OWNER_MARKER = ".pixiv-plugin-runtime-workspace";

    private final Path originalArtifact;
    private final Path workspace;
    private final Path snapshotArtifact;
    private final UserPrincipal owner;
    private Path loadDirectory;
    private LoadTreeManifest loadManifest;
    private int workerDirectoryCount;
    private boolean closed;

    private PluginArtifactSnapshot(Path originalArtifact, Path workspace, Path snapshotArtifact,
                                   UserPrincipal owner) {
        this.originalArtifact = originalArtifact;
        this.workspace = workspace;
        this.snapshotArtifact = snapshotArtifact;
        this.owner = owner;
    }

    public static PluginArtifactSnapshot create(PluginRuntimeLayout layout, Path artifact, long maximumBytes) {
        Objects.requireNonNull(layout, "layout");
        Path original = Objects.requireNonNull(artifact, "artifact").toAbsolutePath().normalize();
        if (maximumBytes <= 0L) {
            throw new IllegalArgumentException("maximumBytes must be positive");
        }
        Path workspace = null;
        Path snapshot = null;
        try {
            requirePlainRegularFile(original, "plugin artifact");
            UserPrincipal owner = PluginRuntimeFileSecurity.secureLoadingRoots(layout);
            PluginRuntimeFileSecurity.secureWritableFile(original, owner);
            Path runtimeRoot = requireRuntimeRoot(layout, owner);
            workspace = PluginRuntimeFileSecurity.createPrivateDirectory(
                    runtimeRoot, WORKSPACE_PREFIX + UUID.randomUUID(), owner);
            requirePlainDirectory(workspace, "plugin artifact snapshot workspace");
            writeOwnerMarker(workspace, owner);
            snapshot = workspace.resolve(original.getFileName().toString());
            copyBoundedNoFollow(original, snapshot, maximumBytes, owner);
            requirePlainRegularFile(snapshot, "plugin artifact snapshot");
            return new PluginArtifactSnapshot(original, workspace, snapshot, owner);
        } catch (IOException | RuntimeException e) {
            deleteWorkspaceQuietly(workspace);
            throw new PluginRuntimeOperationException("failed to freeze plugin artifact " + original, e);
        }
    }

    /**
     * 在 bootstrap 已取得插件目录 lease、完成事务恢复且尚无活动 generation 时清理崩溃遗留 workspace。
     * marker 只授权清理，绝不授权复用或加载其中内容。
     */
    public static void cleanupAbandonedWorkspaces(PluginRuntimeLayout layout) {
        Objects.requireNonNull(layout, "layout");
        Path pluginsRoot = layout.pluginsRoot().toAbsolutePath().normalize();
        Path runtimeRoot = layout.runtimeDirectory().toAbsolutePath().normalize();
        try {
            requirePlainDirectory(pluginsRoot, "plugins root");
            if (!Objects.equals(runtimeRoot.getParent(), pluginsRoot)) {
                throw new IOException("plugin runtime directory is not a direct child of the plugins root");
            }
            BasicFileAttributes runtimeAttributes = attributesIfPresent(runtimeRoot);
            if (runtimeAttributes == null) {
                return;
            }
            requirePlainDirectory(runtimeRoot, "plugin runtime directory");
            int inspected = 0;
            try (DirectoryStream<Path> children = Files.newDirectoryStream(runtimeRoot)) {
                for (Path child : children) {
                    if (++inspected > MAX_WORKSPACE_ENTRIES) {
                        throw new IOException("plugin runtime directory exceeds the supported entry count");
                    }
                    Path normalized = child.toAbsolutePath().normalize();
                    if (!Objects.equals(normalized.getParent(), runtimeRoot)
                            || !normalized.getFileName().toString().startsWith(WORKSPACE_PREFIX)) {
                        continue;
                    }
                    BasicFileAttributes attributes = attributesIfPresent(normalized);
                    if (attributes == null) {
                        continue;
                    }
                    if (attributes.isSymbolicLink() || attributes.isOther() || !attributes.isDirectory()) {
                        log.warn("Retaining unsafe abandoned plugin artifact workspace candidate {}", normalized);
                        continue;
                    }
                    if (hasOwnedMarker(normalized)) {
                        deleteWorkspaceQuietly(normalized);
                    } else {
                        log.warn("Retaining unowned plugin artifact workspace candidate {}", normalized);
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            throw new PluginRuntimeOperationException(
                    "failed to clean abandoned plugin artifact workspaces under " + runtimeRoot, e);
        }
    }

    public Path originalArtifact() {
        return originalArtifact;
    }

    public Path snapshotArtifact() {
        requireOpen();
        return snapshotArtifact;
    }

    /** 为同一 generation 的每次隔离 worker 启动创建不复用的私有工作目录。 */
    public Path createWorkerDirectory() throws IOException {
        requireOpen();
        requirePlainDirectory(workspace, "plugin artifact snapshot workspace");
        if (workerDirectoryCount >= MAX_WORKER_DIRECTORIES) {
            throw new IOException("plugin generation exceeded the isolated worker restart limit");
        }
        workerDirectoryCount++;
        return PluginRuntimeFileSecurity.createPrivateDirectory(
                workspace, "worker-" + UUID.randomUUID(), owner);
    }

    Path createLoadDirectory() throws IOException {
        requireOpen();
        requirePlainDirectory(workspace, "plugin artifact snapshot workspace");
        if (loadDirectory != null) {
            throw new IOException("plugin artifact load directory already exists: " + loadDirectory);
        }
        Path candidate = workspace.resolve("load");
        if (attributesIfPresent(candidate) != null) {
            throw new IOException("plugin artifact load directory already exists: " + candidate);
        }
        loadDirectory = PluginRuntimeFileSecurity.createPrivateDirectory(workspace, "load", owner);
        return loadDirectory;
    }

    void createMaterializedDirectory(Path directory) throws IOException {
        requireOpen();
        Path normalized = requireMaterializedPath(directory);
        Path current = loadDirectory;
        for (Path component : loadDirectory.relativize(normalized)) {
            Path child = current.resolve(component.toString());
            BasicFileAttributes attributes = attributesIfPresent(child);
            if (attributes == null) {
                PluginRuntimeFileSecurity.createPrivateDirectory(current, component.toString(), owner);
            } else {
                requirePlainDirectory(child, "plugin artifact materialization directory");
                PluginRuntimeFileSecurity.secureWritableDirectory(child, owner);
            }
            current = child;
        }
    }

    void copyMaterializedEntry(InputStream input, Path output) throws IOException {
        requireOpen();
        Objects.requireNonNull(input, "input");
        Path normalized = requireMaterializedPath(output);
        createMaterializedDirectory(normalized.getParent());
        Set<OpenOption> options = Set.of(
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        try (FileChannel channel = FileChannel.open(normalized, options);
             var target = Channels.newOutputStream(channel)) {
            input.transferTo(target);
            channel.force(true);
        }
        requirePlainRegularFile(normalized, "plugin artifact materialized file");
        PluginRuntimeFileSecurity.secureWritableFile(normalized, owner);
    }

    void sealLoadPath(Path loadPath) throws IOException {
        requireOpen();
        if (loadManifest != null) {
            throw new IOException("plugin artifact load path is already sealed");
        }
        Path normalized = Objects.requireNonNull(loadPath, "loadPath").toAbsolutePath().normalize();
        if (!normalized.startsWith(workspace)) {
            throw new IOException("plugin artifact load path escaped its workspace: " + normalized);
        }
        LoadTreeManifest manifest = LoadTreeManifest.capture(normalized, owner, false);
        for (LoadTreeEntry entry : manifest.entries().stream()
                .sorted(Comparator.comparingInt((LoadTreeEntry entry) -> entry.path().getNameCount()).reversed())
                .toList()) {
            if (entry.directory()) {
                PluginRuntimeFileSecurity.secureReadOnlyDirectory(entry.path(), owner);
            } else {
                PluginRuntimeFileSecurity.secureReadOnlyFile(entry.path(), owner);
            }
        }
        manifest.verify(owner);
        loadManifest = manifest;
    }

    /** PF4J 打开路径前，证明本代已封存的完整加载树没有变化。 */
    public void verifyLoadPath(Path loadPath) {
        requireOpen();
        Path normalized = Objects.requireNonNull(loadPath, "loadPath").toAbsolutePath().normalize();
        try {
            if (loadManifest == null || !loadManifest.root().equals(normalized)) {
                throw new IOException("plugin artifact load path was not sealed: " + normalized);
            }
            loadManifest.verify(owner);
        } catch (IOException e) {
            throw new PluginRuntimeOperationException(
                    "plugin artifact load tree changed before PF4J admission: " + normalized, e);
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = deleteWorkspaceQuietly(workspace);
    }

    private Path requireMaterializedPath(Path path) throws IOException {
        if (loadDirectory == null) {
            throw new IOException("plugin artifact load directory is not initialized");
        }
        Path normalized = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        if (!normalized.startsWith(loadDirectory)) {
            throw new IOException("plugin artifact materialization escaped its load directory: " + normalized);
        }
        return normalized;
    }

    private static Path requireRuntimeRoot(PluginRuntimeLayout layout, UserPrincipal owner) throws IOException {
        Path pluginsRoot = layout.pluginsRoot().toAbsolutePath().normalize();
        requirePlainDirectory(pluginsRoot, "plugins root");
        Path runtimeRoot = layout.runtimeDirectory().toAbsolutePath().normalize();
        if (!Objects.equals(runtimeRoot.getParent(), pluginsRoot)) {
            throw new IOException("plugin runtime directory is not a direct child of the plugins root");
        }
        BasicFileAttributes attributes = attributesIfPresent(runtimeRoot);
        if (attributes == null) {
            PluginRuntimeFileSecurity.createPrivateDirectory(pluginsRoot, runtimeRoot.getFileName().toString(), owner);
        }
        requirePlainDirectory(runtimeRoot, "plugin runtime directory");
        PluginRuntimeFileSecurity.secureWritableDirectory(runtimeRoot, owner);
        return runtimeRoot;
    }

    private static void writeOwnerMarker(Path workspace, UserPrincipal owner) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("formatVersion", "1");
        properties.setProperty("workspace.name", workspace.getFileName().toString());
        Path marker = workspace.resolve(OWNER_MARKER);
        try (var out = Files.newOutputStream(marker,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            properties.store(out, "PixivDownloader private plugin runtime workspace");
        }
        PluginRuntimeFileSecurity.secureWritableFile(marker, owner);
    }

    private static boolean hasOwnedMarker(Path workspace) throws IOException {
        Path marker = workspace.resolve(OWNER_MARKER);
        BasicFileAttributes attributes = attributesIfPresent(marker);
        if (attributes == null || attributes.isSymbolicLink() || attributes.isOther()
                || !attributes.isRegularFile() || attributes.size() > 64L * 1024L) {
            return false;
        }
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(marker, LinkOption.NOFOLLOW_LINKS)) {
            properties.load(in);
        }
        return "1".equals(properties.getProperty("formatVersion"))
                && workspace.getFileName().toString().equals(properties.getProperty("workspace.name"));
    }

    private static void copyBoundedNoFollow(Path source, Path target, long maximumBytes,
                                            UserPrincipal owner) throws IOException {
        BasicFileAttributes sourceAttributes = attributesIfPresent(source);
        if (sourceAttributes == null || sourceAttributes.isSymbolicLink() || sourceAttributes.isOther()
                || !sourceAttributes.isRegularFile()) {
            throw new IOException("plugin artifact source is not a plain regular file: " + source);
        }
        if (sourceAttributes.size() > maximumBytes) {
            throw new IOException("plugin artifact exceeds the snapshot size limit: " + source);
        }
        Set<OpenOption> readOptions = Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        Set<OpenOption> writeOptions = Set.of(
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        long copied = 0L;
        ByteBuffer buffer = ByteBuffer.allocate(COPY_BUFFER_BYTES);
        try (FileChannel input = FileChannel.open(source, readOptions);
             FileChannel output = FileChannel.open(target, writeOptions)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                buffer.flip();
                if (read > maximumBytes - copied) {
                    throw new IOException("plugin artifact grew beyond the snapshot size limit: " + source);
                }
                while (buffer.hasRemaining()) {
                    output.write(buffer);
                }
                copied += read;
                buffer.clear();
            }
            output.force(true);
        }
        PluginRuntimeFileSecurity.secureWritableFile(target, owner);
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("plugin artifact snapshot is closed");
        }
    }

    private static void requirePlainDirectory(Path path, String role) throws IOException {
        BasicFileAttributes attributes = attributesIfPresent(path);
        if (attributes == null || attributes.isSymbolicLink() || attributes.isOther()
                || !attributes.isDirectory()) {
            throw new IOException(role + " must be a plain directory: " + path);
        }
    }

    private static void requirePlainRegularFile(Path path, String role) throws IOException {
        BasicFileAttributes attributes = attributesIfPresent(path);
        if (attributes == null || attributes.isSymbolicLink() || attributes.isOther()
                || !attributes.isRegularFile()) {
            throw new IOException(role + " must be a plain regular file: " + path);
        }
    }

    private static BasicFileAttributes attributesIfPresent(Path path) throws IOException {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException e) {
            return null;
        }
    }

    private static boolean deleteWorkspaceQuietly(Path root) {
        if (root == null) {
            return true;
        }
        try {
            BasicFileAttributes rootAttributes = attributesIfPresent(root);
            if (rootAttributes == null) {
                return true;
            }
            if (rootAttributes.isSymbolicLink() || rootAttributes.isOther() || !rootAttributes.isDirectory()) {
                throw new IOException("plugin artifact workspace is not a plain directory: " + root);
            }
            PluginRuntimeFileSecurity.makeTreeWritable(root, PluginRuntimeFileSecurity.owner(root));
            List<WorkspaceEntry> entries = new ArrayList<>();
            try (var walk = Files.walk(root)) {
                var iterator = walk.iterator();
                while (iterator.hasNext()) {
                    if (entries.size() >= MAX_WORKSPACE_ENTRIES) {
                        throw new IOException("plugin artifact workspace exceeds the supported entry count");
                    }
                    Path entry = iterator.next().toAbsolutePath().normalize();
                    if (!entry.startsWith(root)) {
                        throw new IOException("plugin artifact workspace traversal escaped its root: " + entry);
                    }
                    BasicFileAttributes attributes = Files.readAttributes(
                            entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (attributes.isSymbolicLink() || attributes.isOther()
                            || !attributes.isDirectory() && !attributes.isRegularFile()) {
                        throw new IOException("plugin artifact workspace contains an unsafe entry: " + entry);
                    }
                    entries.add(new WorkspaceEntry(entry, attributes.fileKey(), attributes.creationTime(),
                            attributes.isDirectory()));
                }
            }
            entries.sort(Comparator.comparingInt((WorkspaceEntry entry) -> entry.path().getNameCount()).reversed());
            for (WorkspaceEntry entry : entries) {
                BasicFileAttributes current = attributesIfPresent(entry.path());
                if (current == null) {
                    continue;
                }
                if (current.isSymbolicLink() || current.isOther()
                        || entry.directory() != current.isDirectory()
                        || !entry.directory() && !current.isRegularFile()
                        || !Objects.equals(entry.fileKey(), current.fileKey())
                        || !entry.creationTime().equals(current.creationTime())) {
                    throw new IOException("plugin artifact workspace entry changed after validation: "
                            + entry.path());
                }
                Files.delete(entry.path());
            }
            return true;
        } catch (IOException | RuntimeException e) {
            log.warn("Failed to clean plugin artifact workspace {}: {}", root, e.toString());
            return false;
        }
    }

    private record WorkspaceEntry(Path path, Object fileKey, FileTime creationTime, boolean directory) {
    }

    private record LoadTreeManifest(Path root, List<LoadTreeEntry> entries) {

        private static LoadTreeManifest capture(Path root, UserPrincipal owner,
                                                boolean requireReadOnly) throws IOException {
            Path normalizedRoot = root.toAbsolutePath().normalize();
            BasicFileAttributes rootAttributes = Files.readAttributes(
                    normalizedRoot, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (rootAttributes.isSymbolicLink() || rootAttributes.isOther()
                    || !rootAttributes.isDirectory() && !rootAttributes.isRegularFile()) {
                throw new IOException("plugin artifact load path is not plain: " + normalizedRoot);
            }
            List<Path> paths;
            if (rootAttributes.isDirectory()) {
                try (var walk = Files.walk(normalizedRoot)) {
                    paths = walk.sorted().toList();
                }
            } else {
                paths = List.of(normalizedRoot);
            }
            if (paths.size() > MAX_WORKSPACE_ENTRIES) {
                throw new IOException("plugin artifact load tree exceeds the supported entry count");
            }
            List<LoadTreeEntry> entries = new ArrayList<>(paths.size());
            for (Path path : paths) {
                Path normalized = path.toAbsolutePath().normalize();
                if (!normalized.equals(normalizedRoot) && !normalized.startsWith(normalizedRoot)) {
                    throw new IOException("plugin artifact load tree escaped its root: " + normalized);
                }
                BasicFileAttributes attributes = Files.readAttributes(
                        normalized, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isSymbolicLink() || attributes.isOther()
                        || !attributes.isDirectory() && !attributes.isRegularFile()) {
                    throw new IOException("plugin artifact load tree contains an unsafe entry: " + normalized);
                }
                String relativePath = normalized.equals(normalizedRoot)
                        ? "." : normalizedRoot.relativize(normalized).toString().replace('\\', '/');
                boolean directory = attributes.isDirectory();
                entries.add(new LoadTreeEntry(normalized, relativePath, directory,
                        directory ? 0L : attributes.size(),
                        directory ? "" : sha256NoFollow(normalized, attributes),
                        attributes.fileKey(), attributes.creationTime()));
                if (requireReadOnly) {
                    PluginRuntimeFileSecurity.verifyReadOnly(normalized, directory, owner);
                }
            }
            return new LoadTreeManifest(normalizedRoot, List.copyOf(entries));
        }

        private void verify(UserPrincipal owner) throws IOException {
            LoadTreeManifest current = capture(root, owner, true);
            if (!entries.equals(current.entries)) {
                throw new IOException("plugin artifact load tree manifest changed: " + root);
            }
        }
    }

    private record LoadTreeEntry(Path path,
                                 String relativePath,
                                 boolean directory,
                                 long size,
                                 String sha256,
                                 Object fileKey,
                                 FileTime creationTime) {
    }

    private static String sha256NoFollow(Path file, BasicFileAttributes expected) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
        ByteBuffer buffer = ByteBuffer.allocate(8192);
        Set<OpenOption> options = Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        try (FileChannel channel = FileChannel.open(file, options)) {
            while (channel.read(buffer) >= 0) {
                buffer.flip();
                digest.update(buffer);
                buffer.clear();
            }
        }
        BasicFileAttributes current = Files.readAttributes(
                file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!current.isRegularFile() || current.isSymbolicLink() || current.isOther()
                || expected.size() != current.size()
                || !Objects.equals(expected.fileKey(), current.fileKey())
                || !expected.creationTime().equals(current.creationTime())) {
            throw new IOException("plugin artifact load file changed while hashing: " + file);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
