package top.sywyar.pixivdownload.plugin.runtime.artifact;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.sywyar.pixivdownload.plugin.runtime.lifecycle.PluginRuntimeOperationException;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageFormat;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageInspection;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageIntegrity;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.ZipSafety;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * Converts verified plugin artifacts into the PF4J path that should be loaded.
 *
 * <p>The original artifact remains the trust source. The runtime directory is only a cache produced
 * after provenance/signature/SHA verification has accepted the original bytes.
 */
public final class PluginArtifactMaterializer {

    private static final Logger log = LoggerFactory.getLogger(PluginArtifactMaterializer.class);
    private static final String MARKER = ".pixiv-plugin-runtime-cache";

    private final PluginRuntimeLayout layout;

    public PluginArtifactMaterializer(PluginRuntimeLayout layout) {
        this.layout = Objects.requireNonNull(layout, "layout");
    }

    public MaterializedPluginArtifact materialize(Path artifact, PluginPackageInspection inspection) {
        Objects.requireNonNull(artifact, "artifact");
        Objects.requireNonNull(inspection, "inspection");
        Path original = artifact.toAbsolutePath().normalize();
        if (inspection.format() == PluginPackageFormat.SINGLE_JAR
                && inspection.innerJarEntry() == null
                && !inspection.containsPrivateLibraries()) {
            return new MaterializedPluginArtifact(original, original, false);
        }

        try {
            String sha256 = PluginPackageIntegrity.sha256Hex(original);
            Path target = cacheDirectory(inspection, sha256);
            CacheMarker marker = reusableCacheMarker(target, inspection, sha256);
            if (marker != null) {
                refreshMarkerPathIfNeeded(target, original, inspection, sha256, marker);
                return new MaterializedPluginArtifact(original, target, true);
            }
            if (Files.exists(target)) {
                throw new PluginRuntimeOperationException("runtime cache path exists but is not owned by this artifact: "
                        + target);
            }
            Files.createDirectories(layout.runtimeDirectory());
            Path temporary = Files.createTempDirectory(layout.runtimeDirectory(), ".materialize-");
            boolean moved = false;
            try {
                if (inspection.format() == PluginPackageFormat.EXPLODED_DIRECTORY) {
                    extractExplodedZip(original, temporary);
                } else if (inspection.innerJarEntry() != null) {
                    extractInnerJarAsDirectory(original, inspection.innerJarEntry(), temporary);
                } else {
                    extractJarAsDirectory(original, temporary);
                }
                writeMarker(temporary, original, inspection, sha256);
                moveDirectory(temporary, target);
                moved = true;
                return new MaterializedPluginArtifact(original, target, true);
            } finally {
                if (!moved) {
                    deleteRecursivelyQuietly(temporary);
                }
            }
        } catch (IOException e) {
            throw new PluginRuntimeOperationException("failed to materialize plugin artifact " + artifact, e);
        }
    }

    private Path cacheDirectory(PluginPackageInspection inspection, String sha256) {
        String id = safeSegment(inspection.descriptor().id());
        String version = safeSegment(inspection.descriptor().version());
        return layout.runtimeDirectory().resolve(id + "-" + version + "-" + sha256);
    }

    private static String safeSegment(String value) {
        String text = value == null || value.isBlank() ? "unknown" : value;
        return text.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static CacheMarker reusableCacheMarker(Path target, PluginPackageInspection inspection,
                                                   String sha256) throws IOException {
        Path marker = target.resolve(MARKER);
        if (!Files.isDirectory(target) || !Files.isRegularFile(marker)) {
            return null;
        }
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(marker)) {
            properties.load(in);
        }
        boolean reusable = sha256.equals(properties.getProperty("artifact.sha256"))
                && inspection.descriptor().id().equals(properties.getProperty("plugin.id"))
                && inspection.descriptor().version().equals(properties.getProperty("plugin.version"));
        return reusable ? new CacheMarker(properties.getProperty("artifact.path")) : null;
    }

    private static void refreshMarkerPathIfNeeded(Path target, Path original, PluginPackageInspection inspection,
                                                  String sha256, CacheMarker marker) {
        if (original.toString().equals(marker.artifactPath())) {
            return;
        }
        try {
            writeMarker(target, original, inspection, sha256);
        } catch (IOException | RuntimeException e) {
            log.warn("Failed to refresh plugin materialization cache marker {}: {}",
                    target.resolve(MARKER), e.toString());
        }
    }

    private static void writeMarker(Path directory, Path original, PluginPackageInspection inspection,
                                    String sha256) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("formatVersion", "1");
        properties.setProperty("artifact.path", original.toString());
        properties.setProperty("artifact.sha256", sha256);
        properties.setProperty("plugin.id", inspection.descriptor().id());
        properties.setProperty("plugin.version", inspection.descriptor().version());
        Path marker = directory.resolve(MARKER);
        Path temporary = directory.resolve(MARKER + ".tmp");
        boolean moved = false;
        try (var out = Files.newOutputStream(temporary)) {
            properties.store(out, "PixivDownloader plugin runtime cache");
        }
        try {
            moveFile(temporary, marker);
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporary);
            }
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

    private static void extractInnerJarAsDirectory(Path zip, String jarEntryName, Path target) throws IOException {
        ZipSafety.requireSafeEntryName(jarEntryName);
        try (ZipFile zipFile = new ZipFile(zip.toFile())) {
            ZipEntry jarEntry = zipFile.getEntry(jarEntryName);
            if (jarEntry == null) {
                throw new IOException("inner plugin jar not found: " + jarEntryName);
            }
            try (ZipInputStream jarStream = new ZipInputStream(
                    new BufferedInputStream(zipFile.getInputStream(jarEntry)))) {
                ZipEntry inner;
                while ((inner = jarStream.getNextEntry()) != null) {
                    if (inner.isDirectory()) {
                        continue;
                    }
                    String entryName = inner.getName().replace('\\', '/');
                    ZipSafety.requireSafeEntryName(entryName);
                    copyJarEntry(jarStream, entryName, target);
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
        Path output = root.resolve(entryName).normalize();
        if (!output.startsWith(root.normalize())) {
            throw new PluginRuntimeOperationException("unsafe plugin archive entry: " + entryName);
        }
        return output;
    }

    private static void copyEntry(InputStream in, Path output) throws IOException {
        Path parent = output.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.copy(in, output, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void moveDirectory(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException | java.nio.file.FileAlreadyExistsException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void moveFile(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException | java.nio.file.FileAlreadyExistsException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteRecursivelyQuietly(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.comparingInt(Path::getNameCount).reversed()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    log.warn("Failed to delete plugin materialization cache entry {}: {}", path, e.toString());
                }
            });
        } catch (IOException e) {
            log.warn("Failed to clean plugin materialization cache {}: {}", root, e.toString());
        }
    }

    public record MaterializedPluginArtifact(Path originalArtifactPath, Path pf4jLoadPath, boolean materialized) {
    }

    private record CacheMarker(String artifactPath) {
    }
}
