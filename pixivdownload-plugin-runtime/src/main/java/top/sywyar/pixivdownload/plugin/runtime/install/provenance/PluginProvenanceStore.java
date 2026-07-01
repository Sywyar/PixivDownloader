package top.sywyar.pixivdownload.plugin.runtime.install.provenance;

import top.sywyar.pixivdownload.plugin.runtime.install.PluginPackageOrigin;
import top.sywyar.pixivdownload.plugin.runtime.install.PluginPackageSource;
import top.sywyar.pixivdownload.plugin.signature.SignatureMetadata;
import top.sywyar.pixivdownload.plugin.signature.VerificationResult;
import top.sywyar.pixivdownload.plugin.signature.VerificationStatus;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;

/**
 * 已安装插件验签来源 sidecar 的持久化读写入口。
 */
public final class PluginProvenanceStore {

    private static final String SIDECAR_SUFFIX = ".pixiv-plugin-provenance";

    private final Path pluginsDir;

    public PluginProvenanceStore(Path pluginsDir) {
        this.pluginsDir = Objects.requireNonNull(pluginsDir, "pluginsDir");
    }

    public Path sidecarPath(Path artifact) {
        return artifact.resolveSibling(artifact.getFileName().toString() + SIDECAR_SUFFIX);
    }

    public Optional<PluginProvenanceRecord> read(Path artifact) {
        Path sidecar = sidecarPath(artifact);
        if (!Files.isRegularFile(sidecar)) {
            return Optional.empty();
        }
        Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(sidecar, StandardCharsets.UTF_8)) {
            props.load(reader);
            return Optional.of(toRecord(props));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    public void write(Path artifact, PluginPackageOrigin origin, VerificationResult result) throws IOException {
        write(artifact, PluginProvenanceRecord.from(origin, result));
    }

    public void write(Path artifact, PluginProvenanceRecord record) throws IOException {
        Properties props = new Properties();
        props.setProperty("formatVersion", "1");
        props.setProperty("source", record.source().name());
        put(props, "repositoryId", record.repositoryId());
        props.setProperty("officialRepository", Boolean.toString(record.officialRepository()));
        put(props, "expectedSizeBytes", record.expectedSizeBytes() != null
                ? record.expectedSizeBytes().toString() : null);
        put(props, "expectedSha256", record.expectedSha256());
        if (record.signature() != null) {
            props.setProperty("signature.formatVersion", Integer.toString(record.signature().formatVersion()));
            put(props, "signature.algorithm", record.signature().algorithm());
            put(props, "signature.keyId", record.signature().keyId());
            put(props, "signature.value", record.signature().value());
        }
        props.setProperty("status", record.status().name());
        put(props, "keyId", record.keyId());
        put(props, "publisher", record.publisher());
        put(props, "trustLabel", record.trustLabel());
        put(props, "verifiedAt", record.verifiedAt() != null ? record.verifiedAt().toString() : null);
        put(props, "offlineStatus", record.offlineStatus() != null ? record.offlineStatus().name() : null);
        put(props, "offlineVerifiedAt", record.offlineVerifiedAt() != null
                ? record.offlineVerifiedAt().toString() : null);
        put(props, "diagnosticCode", record.diagnosticCode());

        Files.createDirectories(artifact.toAbsolutePath().normalize().getParent());
        Path sidecar = sidecarPath(artifact);
        Path tmp = sidecar.resolveSibling(sidecar.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            props.store(writer, "PixivDownloader plugin provenance");
        }
        move(tmp, sidecar);
    }

    public void delete(Path artifact) throws IOException {
        Files.deleteIfExists(sidecarPath(artifact));
    }

    public void moveWithArtifact(Path sourceArtifact, Path targetArtifact, ArtifactMover mover) throws IOException {
        Path sourceSidecar = sidecarPath(sourceArtifact);
        Path targetSidecar = sidecarPath(targetArtifact);
        boolean sidecarMoved = false;
        if (Files.exists(sourceSidecar)) {
            Files.createDirectories(targetSidecar.toAbsolutePath().normalize().getParent());
            move(sourceSidecar, targetSidecar);
            sidecarMoved = true;
        }
        try {
            mover.move(sourceArtifact, targetArtifact);
        } catch (IOException | RuntimeException e) {
            if (sidecarMoved && Files.exists(targetSidecar)) {
                move(targetSidecar, sourceSidecar);
            }
            throw e;
        }
    }

    public Path pluginsDir() {
        return pluginsDir;
    }

    private static PluginProvenanceRecord toRecord(Properties props) {
        SignatureMetadata signature = null;
        if (props.getProperty("signature.keyId") != null) {
            signature = new SignatureMetadata(
                    Integer.parseInt(props.getProperty("signature.formatVersion", "1")),
                    props.getProperty("signature.algorithm"),
                    props.getProperty("signature.keyId"),
                    props.getProperty("signature.value"));
        }
        return new PluginProvenanceRecord(
                PluginPackageSource.valueOf(props.getProperty("source", PluginPackageSource.LOCAL_UPLOAD.name())),
                text(props, "repositoryId"),
                Boolean.parseBoolean(props.getProperty("officialRepository", "false")),
                longOrNull(props.getProperty("expectedSizeBytes")),
                text(props, "expectedSha256"),
                signature,
                VerificationStatus.valueOf(props.getProperty("status", VerificationStatus.UNSIGNED_ALLOWED.name())),
                text(props, "keyId"),
                text(props, "publisher"),
                text(props, "trustLabel"),
                instantOrNull(props.getProperty("verifiedAt")),
                statusOrNull(props.getProperty("offlineStatus")),
                instantOrNull(props.getProperty("offlineVerifiedAt")),
                text(props, "diagnosticCode"));
    }

    private static void put(Properties props, String key, String value) {
        if (value != null && !value.isBlank()) {
            props.setProperty(key, value);
        }
    }

    private static String text(Properties props, String key) {
        String value = props.getProperty(key);
        return value == null || value.isBlank() ? null : value;
    }

    private static Long longOrNull(String value) {
        return value == null || value.isBlank() ? null : Long.parseLong(value);
    }

    private static Instant instantOrNull(String value) {
        return value == null || value.isBlank() ? null : Instant.parse(value);
    }

    private static VerificationStatus statusOrNull(String value) {
        return value == null || value.isBlank() ? null : VerificationStatus.valueOf(value);
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException | java.nio.file.FileAlreadyExistsException e) {
            Files.move(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @FunctionalInterface
    public interface ArtifactMover {
        void move(Path source, Path target) throws IOException;
    }
}
