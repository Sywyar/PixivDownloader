package top.sywyar.pixivdownload.plugin.runtime.artifact;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.sywyar.pixivdownload.plugin.runtime.lifecycle.PluginRuntimeOperationException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;

/**
 * 生产插件加载使用的私有 artifact 快照。原始安装路径只在这里以 NOFOLLOW channel 读取一次；后续结构校验、
 * provenance 复验与 runtime 物化都只消费同一快照，避免验签后再次按公开路径打开不同字节。
 */
public final class PluginArtifactSnapshot implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PluginArtifactSnapshot.class);
    private static final int COPY_BUFFER_BYTES = 64 * 1024;
    private static final int MAX_WORKSPACE_ENTRIES = 25_000;
    private static final String WORKSPACE_PREFIX = ".artifact-snapshot-";
    private static final String OWNER_MARKER = ".pixiv-plugin-runtime-workspace";

    private final Path originalArtifact;
    private final Path workspace;
    private final Path snapshotArtifact;
    private boolean closed;

    private PluginArtifactSnapshot(Path originalArtifact, Path workspace, Path snapshotArtifact) {
        this.originalArtifact = originalArtifact;
        this.workspace = workspace;
        this.snapshotArtifact = snapshotArtifact;
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
            Path runtimeRoot = requireRuntimeRoot(layout);
            workspace = Files.createTempDirectory(runtimeRoot, WORKSPACE_PREFIX)
                    .toAbsolutePath().normalize();
            requirePlainDirectory(workspace, "plugin artifact snapshot workspace");
            writeOwnerMarker(workspace);
            snapshot = workspace.resolve(original.getFileName().toString());
            copyBoundedNoFollow(original, snapshot, maximumBytes);
            requirePlainRegularFile(snapshot, "plugin artifact snapshot");
            return new PluginArtifactSnapshot(original, workspace, snapshot);
        } catch (IOException | RuntimeException e) {
            deleteWorkspaceQuietly(workspace);
            throw new PluginRuntimeOperationException("failed to freeze plugin artifact " + original, e);
        }
    }

    public Path originalArtifact() {
        return originalArtifact;
    }

    public Path snapshotArtifact() {
        requireOpen();
        return snapshotArtifact;
    }

    Path createLoadDirectory() throws IOException {
        requireOpen();
        requirePlainDirectory(workspace, "plugin artifact snapshot workspace");
        Path loadDirectory = workspace.resolve("load");
        if (attributesIfPresent(loadDirectory) != null) {
            throw new IOException("plugin artifact load directory already exists: " + loadDirectory);
        }
        Files.createDirectory(loadDirectory);
        requirePlainDirectory(loadDirectory, "plugin artifact load directory");
        return loadDirectory;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        deleteWorkspaceQuietly(workspace);
    }

    private static Path requireRuntimeRoot(PluginRuntimeLayout layout) throws IOException {
        Path pluginsRoot = layout.pluginsRoot().toAbsolutePath().normalize();
        requirePlainDirectory(pluginsRoot, "plugins root");
        Path runtimeRoot = layout.runtimeDirectory().toAbsolutePath().normalize();
        if (!Objects.equals(runtimeRoot.getParent(), pluginsRoot)) {
            throw new IOException("plugin runtime directory is not a direct child of the plugins root");
        }
        BasicFileAttributes attributes = attributesIfPresent(runtimeRoot);
        if (attributes == null) {
            Files.createDirectory(runtimeRoot);
        }
        requirePlainDirectory(runtimeRoot, "plugin runtime directory");
        return runtimeRoot;
    }

    private static void writeOwnerMarker(Path workspace) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("formatVersion", "1");
        properties.setProperty("workspace.name", workspace.getFileName().toString());
        Path marker = workspace.resolve(OWNER_MARKER);
        try (var out = Files.newOutputStream(marker,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            properties.store(out, "PixivDownloader private plugin runtime workspace");
        }
    }

    private static void copyBoundedNoFollow(Path source, Path target, long maximumBytes) throws IOException {
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

    private static void deleteWorkspaceQuietly(Path root) {
        if (root == null) {
            return;
        }
        try {
            BasicFileAttributes rootAttributes = attributesIfPresent(root);
            if (rootAttributes == null) {
                return;
            }
            if (rootAttributes.isSymbolicLink() || rootAttributes.isOther() || !rootAttributes.isDirectory()) {
                throw new IOException("plugin artifact workspace is not a plain directory: " + root);
            }
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
        } catch (IOException | RuntimeException e) {
            log.warn("Failed to clean plugin artifact workspace {}: {}", root, e.toString());
        }
    }

    private record WorkspaceEntry(Path path, Object fileKey, FileTime creationTime, boolean directory) {
    }
}
