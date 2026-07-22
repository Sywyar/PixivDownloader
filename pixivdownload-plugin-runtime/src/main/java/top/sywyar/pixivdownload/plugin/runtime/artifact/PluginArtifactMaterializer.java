package top.sywyar.pixivdownload.plugin.runtime.artifact;

import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageFormat;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageInspection;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageIntegrity;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.ZipSafety;
import top.sywyar.pixivdownload.plugin.runtime.lifecycle.PluginRuntimeOperationException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 从已冻结并完成验签的私有 snapshot 生成 PF4J 加载路径。
 *
 * <p>每个 snapshot 自带唯一 runtime workspace；本类不读取原始安装路径、不复用旧 marker cache，
 * 也不把任何确定性共享目录作为加载来源。
 */
public final class PluginArtifactMaterializer {

    public PluginArtifactMaterializer(PluginRuntimeLayout layout) {
        Objects.requireNonNull(layout, "layout");
    }

    public MaterializedPluginArtifact materialize(PluginArtifactSnapshot snapshot,
                                                   PluginPackageInspection inspection,
                                                   String verifiedSha256) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(inspection, "inspection");
        String expectedSha256 = Objects.requireNonNull(verifiedSha256, "verifiedSha256")
                .toLowerCase(Locale.ROOT);
        if (!expectedSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("verifiedSha256 is not a SHA-256 digest");
        }
        Path original = snapshot.originalArtifact().toAbsolutePath().normalize();
        Path frozen = snapshot.snapshotArtifact().toAbsolutePath().normalize();
        if (inspection.innerJarEntry() != null) {
            throw new PluginRuntimeOperationException(
                    "installed plugin package must be canonical and cannot contain an inner plugin jar: "
                            + original);
        }

        try {
            requireVerifiedSnapshotHash(frozen, expectedSha256, "after verification");
            Path loadPath;
            boolean materialized;
            if (inspection.format() == PluginPackageFormat.EXPLODED_DIRECTORY) {
                loadPath = snapshot.createLoadDirectory();
                extractExplodedZip(frozen, loadPath);
                materialized = true;
            } else if (inspection.containsPrivateLibraries()) {
                loadPath = snapshot.createLoadDirectory();
                extractJarAsDirectory(frozen, loadPath);
                materialized = true;
            } else {
                loadPath = frozen;
                materialized = false;
            }
            requireVerifiedSnapshotHash(frozen, expectedSha256, "during materialization");
            return new MaterializedPluginArtifact(original, loadPath, materialized);
        } catch (IOException e) {
            throw new PluginRuntimeOperationException("failed to materialize plugin artifact " + original, e);
        }
    }

    private static void requireVerifiedSnapshotHash(Path snapshot, String expectedSha256, String phase)
            throws IOException {
        if (!expectedSha256.equals(PluginPackageIntegrity.sha256Hex(snapshot))) {
            throw new IOException("plugin artifact snapshot changed " + phase);
        }
    }

    private static void extractExplodedZip(Path zip, Path target) throws IOException {
        try (ZipFile zipFile = new ZipFile(zip.toFile())) {
            var entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String entryName = entry.getName().replace('\\', '/');
                ZipSafety.requireSafeEntryName(entryName);
                if (entry.isDirectory()) {
                    Files.createDirectories(safeResolve(target, entryName));
                    continue;
                }
                try (InputStream in = zipFile.getInputStream(entry)) {
                    copyEntry(in, safeResolve(target, entryName));
                }
            }
        }
    }

    private static void extractJarAsDirectory(Path jar, Path target) throws IOException {
        try (ZipFile zipFile = new ZipFile(jar.toFile())) {
            var entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String entryName = entry.getName().replace('\\', '/');
                ZipSafety.requireSafeEntryName(entryName);
                try (InputStream in = zipFile.getInputStream(entry)) {
                    copyJarEntry(in, entryName, target);
                }
            }
        }
    }

    private static void copyJarEntry(InputStream in, String entryName, Path target) throws IOException {
        Path output;
        if ("plugin.properties".equals(entryName)) {
            output = target.resolve("plugin.properties");
        } else if (isLibEntry(entryName)) {
            output = safeResolve(target, entryName);
        } else {
            output = safeResolve(target.resolve("classes"), entryName);
        }
        copyEntry(in, output);
    }

    private static boolean isLibEntry(String entryName) {
        if (!entryName.startsWith("lib/")) {
            return false;
        }
        return entryName.toLowerCase(Locale.ROOT).endsWith(".jar")
                && entryName.indexOf('/', "lib/".length()) < 0;
    }

    private static Path safeResolve(Path root, String entryName) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path output = normalizedRoot.resolve(entryName).normalize();
        if (!output.startsWith(normalizedRoot)) {
            throw new PluginRuntimeOperationException("unsafe plugin archive entry: " + entryName);
        }
        return output;
    }

    private static void copyEntry(InputStream in, Path output) throws IOException {
        Path parent = output.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.copy(in, output);
    }

    public record MaterializedPluginArtifact(Path originalArtifactPath, Path pf4jLoadPath, boolean materialized) {
    }
}
