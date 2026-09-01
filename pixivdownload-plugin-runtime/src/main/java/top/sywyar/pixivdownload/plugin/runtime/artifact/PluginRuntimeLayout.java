package top.sywyar.pixivdownload.plugin.runtime.artifact;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Runtime-managed subdirectories under the external plugin installation root.
 */
public final class PluginRuntimeLayout {

    public static final String RUNTIME_DIR = "runtime";
    public static final String PROVENANCE_DIR = "provenance";

    private final Path pluginsRoot;

    public PluginRuntimeLayout(Path pluginsRoot) {
        this.pluginsRoot = resolveExistingPluginsRoot(pluginsRoot);
    }

    /** 已存在目录解析为稳定真实路径，从而安全支持 portable 根目录本身是 symlink / junction。 */
    public static Path resolveExistingPluginsRoot(Path pluginsRoot) {
        Path normalized = Objects.requireNonNull(pluginsRoot, "pluginsRoot").toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized)) {
            return normalized;
        }
        try {
            return normalized.toRealPath();
        } catch (IOException | SecurityException failure) {
            return normalized;
        }
    }

    public Path pluginsRoot() {
        return pluginsRoot;
    }

    public Path runtimeDirectory() {
        return pluginsRoot.resolve(RUNTIME_DIR);
    }

    public Path provenanceDirectory() {
        return pluginsRoot.resolve(PROVENANCE_DIR);
    }

    public boolean isInstalledRootArtifact(Path artifact) {
        if (artifact == null || artifact.getParent() == null) {
            return false;
        }
        return artifact.getParent().toAbsolutePath().normalize()
                .equals(pluginsRoot.toAbsolutePath().normalize());
    }

    public static boolean isRuntimeManagedRootName(String name) {
        return RUNTIME_DIR.equals(name) || PROVENANCE_DIR.equals(name);
    }
}
