package top.sywyar.pixivdownload.plugin.runtime.install.recovery;

import top.sywyar.pixivdownload.plugin.runtime.install.transaction.PluginTransactionRecoveryReport.FailureKind;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/** 清理恢复扫描面之外的非权威隐藏事务工作区。 */
public final class PluginRecoveryWorkspaceCleaner {

    private final int maximumWorkspaces;
    private final int maximumEntries;

    public PluginRecoveryWorkspaceCleaner(int maximumWorkspaces, int maximumEntries) {
        if (maximumWorkspaces <= 0 || maximumEntries <= 0) {
            throw new IllegalArgumentException("workspace cleanup budgets must be positive");
        }
        this.maximumWorkspaces = maximumWorkspaces;
        this.maximumEntries = maximumEntries;
    }

    public void cleanup(Path pluginsRoot, String directoryName)
            throws IOException, PluginRecoveryValidationException {
        Path normalizedPluginsRoot = Objects.requireNonNull(
                pluginsRoot, "pluginsRoot").toAbsolutePath().normalize();
        Path workspaceRoot = normalizedPluginsRoot.resolve(
                Objects.requireNonNull(directoryName, "directoryName")).toAbsolutePath().normalize();
        if (!Objects.equals(workspaceRoot.getParent(), normalizedPluginsRoot)) {
            throw unsafePath("hidden transaction workspace escapes the plugins root: " + workspaceRoot);
        }
        BasicFileAttributes rootAttributes = readAttributesIfPresent(workspaceRoot).orElse(null);
        if (rootAttributes == null) {
            return;
        }
        if (rootAttributes.isSymbolicLink() || rootAttributes.isOther() || !rootAttributes.isDirectory()) {
            throw unsafePath("hidden transaction workspace must be a plain directory: " + workspaceRoot);
        }
        List<Path> workspaces = collectWorkspaces(workspaceRoot);
        int remainingEntries = maximumEntries;
        for (Path workspace : workspaces) {
            List<Path> deletionOrder = new ArrayList<>();
            try (Stream<Path> walk = Files.walk(workspace)) {
                var iterator = walk.iterator();
                while (iterator.hasNext()) {
                    if (remainingEntries-- <= 0) {
                        throw invalidManifest("hidden transaction workspaces exceed the cumulative entry budget");
                    }
                    Path entry = iterator.next().toAbsolutePath().normalize();
                    BasicFileAttributes attributes = Files.readAttributes(
                            entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (attributes.isSymbolicLink() || attributes.isOther()) {
                        throw unsafePath("hidden transaction workspace contains a link or special entry: " + entry);
                    }
                    deletionOrder.add(entry);
                }
            }
            deletionOrder.sort(Comparator.comparingInt(Path::getNameCount).reversed());
            for (Path entry : deletionOrder) {
                Files.deleteIfExists(entry);
            }
        }
        Files.deleteIfExists(workspaceRoot);
    }

    private List<Path> collectWorkspaces(Path workspaceRoot)
            throws IOException, PluginRecoveryValidationException {
        List<Path> workspaces = new ArrayList<>();
        try (Stream<Path> entries = Files.list(workspaceRoot)) {
            var iterator = entries.iterator();
            while (iterator.hasNext()) {
                if (workspaces.size() >= maximumWorkspaces) {
                    throw invalidManifest("hidden transaction workspace exceeds the supported count");
                }
                Path workspace = iterator.next().toAbsolutePath().normalize();
                BasicFileAttributes attributes = readAttributesIfPresent(workspace).orElse(null);
                if (attributes == null || attributes.isSymbolicLink() || attributes.isOther()
                        || !attributes.isDirectory() || !Objects.equals(workspace.getParent(), workspaceRoot)) {
                    throw unsafePath("hidden transaction workspace contains an unsafe entry: " + workspace);
                }
                workspaces.add(workspace);
            }
        }
        return workspaces;
    }

    private static Optional<BasicFileAttributes> readAttributesIfPresent(Path path) throws IOException {
        try {
            return Optional.of(Files.readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS));
        } catch (java.nio.file.NoSuchFileException e) {
            return Optional.empty();
        }
    }

    private static PluginRecoveryValidationException invalidManifest(String message) {
        return new PluginRecoveryValidationException(FailureKind.INVALID_MANIFEST, message);
    }

    private static PluginRecoveryValidationException unsafePath(String message) {
        return new PluginRecoveryValidationException(FailureKind.UNSAFE_PATH, message);
    }
}
