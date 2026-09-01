package top.sywyar.pixivdownload.plugin.runtime.install.provenance;

import top.sywyar.pixivdownload.plugin.runtime.artifact.PluginRuntimeLayout;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageOrigin;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageSource;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginExecutionMode;
import top.sywyar.pixivdownload.plugin.runtime.install.trust.PluginTrustDecision;
import top.sywyar.pixivdownload.plugin.signature.SignatureMetadata;
import top.sywyar.pixivdownload.plugin.signature.VerificationResult;
import top.sywyar.pixivdownload.plugin.signature.VerificationStatus;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.StringReader;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CharacterCodingException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;

/**
 * 已安装插件验签来源 sidecar 的持久化读写入口。
 */
public final class PluginProvenanceStore {

    private static final String SIDECAR_SUFFIX = ".pixiv-plugin-provenance";

    /** 恢复期 sidecar 独立上限；正常记录仅数百字节，先限长再加载或计算摘要。 */
    private static final long MAX_RECOVERY_SIDECAR_BYTES = 1L * 1024L * 1024L;

    private final PluginRuntimeLayout layout;

    public PluginProvenanceStore(Path pluginsDir) {
        this(new PluginRuntimeLayout(pluginsDir));
    }

    public PluginProvenanceStore(PluginRuntimeLayout layout) {
        this.layout = Objects.requireNonNull(layout, "layout");
    }

    public Path sidecarPath(Path artifact) {
        if (layout.isInstalledRootArtifact(artifact)) {
            return layout.provenanceDirectory().resolve(artifact.getFileName().toString() + SIDECAR_SUFFIX);
        }
        return artifact.resolveSibling(artifact.getFileName().toString() + SIDECAR_SUFFIX);
    }

    /** 当前与兼容期 legacy sidecar 的全部受管路径；供事务恢复在文件操作前完成路径边界校验。 */
    public List<Path> managedSidecarPaths(Path artifact) {
        Path current = sidecarPath(artifact);
        Path legacy = legacySidecarPath(artifact);
        return current.equals(legacy) ? List.of(current) : List.of(current, legacy);
    }

    /**
     * 恢复路径使用的严格 sidecar 查询：只把明确的不存在视为空；访问失败、符号链接、特殊文件或 current/legacy
     * 双副本均拒绝，避免恢复逻辑把不确定状态误当作「没有 provenance」。
     */
    public Optional<Path> existingManagedSidecarPathStrict(Path artifact) throws IOException {
        List<Path> existing = existingManagedSidecars(artifact);
        if (existing.size() > 1) {
            throw new IOException("plugin provenance has both current and legacy copies: " + artifact);
        }
        return existing.stream().findFirst();
    }

    /** 严格读取恢复所需的 provenance；缺失或损坏均抛错，不执行 legacy 迁移或清理副作用。 */
    public PluginProvenanceRecord readRequiredForRecovery(Path artifact) throws IOException {
        Path sidecar = existingManagedSidecarPathStrict(artifact)
                .orElseThrow(() -> new IOException("plugin provenance is missing: " + artifact));
        try {
            return readStrictRecord(sidecar);
        } catch (RuntimeException e) {
            throw new IOException("plugin provenance is invalid: " + sidecar, e);
        }
    }

    /** 恢复累计预算使用实际有界读取字节数，不能只信读取前 stat 的可竞态尺寸。 */
    public Optional<MeasuredSidecar> measureManagedSidecarStrict(Path artifact) throws IOException {
        Optional<Path> sidecar = existingManagedSidecarPathStrict(artifact);
        if (sidecar.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new MeasuredSidecar(sidecar.get(), readSidecarBytesStrictly(sidecar.get()).length));
    }

    /**
     * 只移动 sidecar，用于修复进程恰好在「sidecar 已移动、artifact 尚未移动」时留下的拆分状态。
     * 目标已有任一受管副本时拒绝覆盖。
     */
    public void moveSidecarOnly(Path sourceArtifact, Path targetArtifact) throws IOException {
        Optional<Path> source = existingManagedSidecarPathStrict(sourceArtifact);
        Optional<Path> existingTarget = existingManagedSidecarPathStrict(targetArtifact);
        if (source.isEmpty()) {
            if (existingTarget.isPresent()) {
                return;
            }
            return;
        }
        if (existingTarget.isPresent()) {
            if (!Files.isSameFile(source.get(), existingTarget.get())) {
                throw new IOException("target plugin provenance already exists: " + targetArtifact);
            }
            deleteManagedFileIfPresent(source.get());
            return;
        }
        Path target = sidecarPath(targetArtifact);
        requireSafeManagedParent(target, true);
        move(source.get(), target);
    }

    public Optional<PluginProvenanceRecord> read(Path artifact) {
        return readMeasured(artifact).map(MeasuredProvenance::record);
    }

    /** 正常读取与累计预算共用同一次有界字节读取，避免先测量再重复解析。 */
    public Optional<MeasuredProvenance> readMeasured(Path artifact) {
        try {
            Optional<Path> selected = existingReadableSidecarPath(artifact);
            if (selected.isEmpty()) {
                return Optional.empty();
            }
            byte[] bytes = readSidecarBytesStrictly(selected.get());
            PluginProvenanceRecord record = readStrictRecord(bytes);
            Path legacy = legacySidecarPath(artifact).toAbsolutePath().normalize();
            Path current = sidecarPath(artifact).toAbsolutePath().normalize();
            if (selected.get().equals(legacy) && !legacy.equals(current)) {
                migrateLegacy(artifact, legacy);
            } else {
                deleteLegacyIfSuperseded(artifact, selected.get());
            }
            return Optional.of(new MeasuredProvenance(record, bytes.length));
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("plugin provenance is invalid for " + artifact, e);
        }
    }

    /**
     * 启动复验使用的兼容计量读取：在剩余累计预算内读取，并保留正常启动收敛等价
     * current/legacy 双副本的语义。结构错误只拒绝当前 artifact；所有实际读取失败都携带已消费字节数。
     */
    public Optional<MeasuredProvenance> readMeasuredCompatible(Path artifact, long maximumBytes)
            throws IOException {
        if (maximumBytes < 0L) {
            throw new IllegalArgumentException("maximumBytes must not be negative");
        }
        List<Path> existing = existingManagedSidecars(artifact);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        Path current = sidecarPath(artifact).toAbsolutePath().normalize();
        Path legacy = legacySidecarPath(artifact).toAbsolutePath().normalize();
        if (existing.size() == 1) {
            Path selected = existing.get(0);
            MeasuredProvenance measured = readMeasuredRecord(selected, maximumBytes);
            if (selected.equals(legacy) && !legacy.equals(current)) {
                migrateLegacy(artifact, legacy);
            } else {
                deleteLegacyIfSuperseded(artifact, selected);
            }
            return Optional.of(measured);
        }

        MeasuredProvenance currentMeasured = readMeasuredRecord(current, maximumBytes);
        long remainingBytes = maximumBytes - currentMeasured.byteCount();
        MeasuredProvenance legacyMeasured;
        try {
            legacyMeasured = readMeasuredRecord(legacy, remainingBytes);
        } catch (IOException failure) {
            throw includePriorReadBytes(failure, currentMeasured.byteCount());
        }
        long totalBytes = Math.addExact(currentMeasured.byteCount(), legacyMeasured.byteCount());
        if (!currentMeasured.record().equals(legacyMeasured.record())) {
            throw new InvalidProvenanceException(
                    "plugin provenance current and legacy copies differ: " + artifact,
                    totalBytes, null);
        }
        deleteLegacyIfSuperseded(artifact, current);
        return Optional.of(new MeasuredProvenance(currentMeasured.record(), totalBytes));
    }

    /** 只读投影使用的严格计量读取：不迁移 legacy、不删除副本，双副本直接拒绝。 */
    public Optional<MeasuredProvenance> readMeasuredStrict(Path artifact) throws IOException {
        return readMeasuredStrict(artifact, MAX_RECOVERY_SIDECAR_BYTES);
    }

    /** 在调用方剩余累计预算内完成同一次严格读取与解析；超额时不会继续读完整 sidecar。 */
    public Optional<MeasuredProvenance> readMeasuredStrict(Path artifact, long maximumBytes)
            throws IOException {
        if (maximumBytes < 0L) {
            throw new IllegalArgumentException("maximumBytes must not be negative");
        }
        Optional<Path> selected = existingManagedSidecarPathStrict(artifact);
        if (selected.isEmpty()) {
            return Optional.empty();
        }
        byte[] bytes = readSidecarBytesStrictly(selected.get(), maximumBytes);
        try {
            return Optional.of(new MeasuredProvenance(readStrictRecord(bytes), bytes.length));
        } catch (IOException | RuntimeException e) {
            throw new InvalidProvenanceException(
                    "plugin provenance is invalid: " + selected.get(), bytes.length, e);
        }
    }

    private static MeasuredProvenance readMeasuredRecord(Path sidecar, long maximumBytes)
            throws IOException {
        byte[] bytes = readSidecarBytesStrictly(sidecar, maximumBytes);
        try {
            return new MeasuredProvenance(readStrictRecord(bytes), bytes.length);
        } catch (IOException | RuntimeException e) {
            throw new InvalidProvenanceException(
                    "plugin provenance is invalid: " + sidecar, bytes.length, e);
        }
    }

    private static IOException includePriorReadBytes(IOException failure, long priorBytes) {
        long totalBytes;
        try {
            totalBytes = Math.addExact(priorBytes, measuredFailureBytes(failure));
        } catch (ArithmeticException overflow) {
            totalBytes = Long.MAX_VALUE;
        }
        if (failure instanceof ReadBudgetExceededException) {
            return new ReadBudgetExceededException(failure.getMessage(), totalBytes);
        }
        if (failure instanceof InvalidProvenanceException) {
            return new InvalidProvenanceException(failure.getMessage(), totalBytes, failure);
        }
        if (failure instanceof ProvenanceReadException) {
            return new ProvenanceReadException(failure.getMessage(), totalBytes, failure);
        }
        return new ProvenanceReadException(failure.getMessage(), totalBytes, failure);
    }

    private static long measuredFailureBytes(IOException failure) {
        if (failure instanceof ReadBudgetExceededException measured) {
            return measured.byteCount();
        }
        if (failure instanceof InvalidProvenanceException measured) {
            return measured.byteCount();
        }
        if (failure instanceof ProvenanceReadException measured) {
            return measured.byteCount();
        }
        return 0L;
    }

    private PluginProvenanceRecord readStrictRecord(Path sidecar) throws IOException {
        return readStrictRecord(readSidecarBytesStrictly(sidecar));
    }

    private static PluginProvenanceRecord readStrictRecord(byte[] bytes) throws IOException {
        return toRecordStrict(readPropertiesStrictly(bytes));
    }

    private static Properties readPropertiesStrictly(Path sidecar) throws IOException {
        return readPropertiesStrictly(readSidecarBytesStrictly(sidecar));
    }

    private static Properties readPropertiesStrictly(byte[] bytes) throws IOException {
        String content;
        try {
            content = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new IOException("plugin provenance is not valid UTF-8", e);
        }
        Properties properties = new RejectingProperties();
        try (Reader reader = new StringReader(content)) {
            properties.load(reader);
        }
        return properties;
    }

    private static byte[] readSidecarBytesStrictly(Path sidecar) throws IOException {
        return readSidecarBytesStrictly(sidecar, MAX_RECOVERY_SIDECAR_BYTES);
    }

    private static byte[] readSidecarBytesStrictly(Path sidecar, long maximumBytes) throws IOException {
        long effectiveMaximum = Math.min(MAX_RECOVERY_SIDECAR_BYTES, maximumBytes);
        if (effectiveMaximum < 0L || effectiveMaximum > Integer.MAX_VALUE - 1L) {
            throw new IllegalArgumentException("invalid plugin provenance read limit: " + maximumBytes);
        }
        java.util.Set<OpenOption> options = java.util.Set.of(
                StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        int readLimit = (int) effectiveMaximum + 1;
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(readLimit, 8 * 1024));
        long bytesRead = 0L;
        try (SeekableByteChannel channel = Files.newByteChannel(sidecar, options);
             InputStream input = Channels.newInputStream(channel)) {
            byte[] buffer = new byte[Math.min(readLimit, 8 * 1024)];
            while (bytesRead < readLimit) {
                int requested = (int) Math.min(buffer.length, readLimit - bytesRead);
                int read = input.read(buffer, 0, requested);
                if (read < 0) {
                    break;
                }
                output.write(buffer, 0, read);
                bytesRead += read;
            }
        } catch (IOException e) {
            throw new ProvenanceReadException(
                    "failed while reading plugin provenance: " + sidecar, bytesRead, e);
        }
        byte[] bytes = output.toByteArray();
        if (bytes.length > effectiveMaximum && effectiveMaximum < MAX_RECOVERY_SIDECAR_BYTES) {
            throw new ReadBudgetExceededException(
                    "plugin provenance exceeds the remaining read budget", bytes.length);
        }
        if (bytes.length > MAX_RECOVERY_SIDECAR_BYTES) {
            throw new ReadBudgetExceededException(
                    "plugin provenance grew beyond the supported size while reading", bytes.length);
        }
        return bytes;
    }

    public void write(Path artifact, PluginPackageOrigin origin, VerificationResult result) throws IOException {
        write(artifact, PluginProvenanceRecord.from(origin, result));
    }

    public void write(Path artifact, PluginProvenanceRecord record) throws IOException {
        Properties props = new Properties();
        props.setProperty("formatVersion", "3");
        props.setProperty("source", record.source().name());
        put(props, "repositoryId", record.repositoryId());
        props.setProperty("officialRepository", Boolean.toString(record.officialRepository()));
        props.setProperty("developmentOnly", Boolean.toString(record.developmentOnly()));
        put(props, "expectedSizeBytes", record.expectedSizeBytes() != null
                ? record.expectedSizeBytes().toString() : null);
        put(props, "expectedSha256", record.expectedSha256());
        props.setProperty("artifactSizeBytes", Long.toString(record.artifactSizeBytes()));
        props.setProperty("artifactSha256", record.artifactSha256());
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
        put(props, "publisherKeyFingerprint", record.publisherKeyFingerprint());
        put(props, "verifiedAt", record.verifiedAt() != null ? record.verifiedAt().toString() : null);
        put(props, "offlineStatus", record.offlineStatus() != null ? record.offlineStatus().name() : null);
        put(props, "offlineVerifiedAt", record.offlineVerifiedAt() != null
                ? record.offlineVerifiedAt().toString() : null);
        put(props, "diagnosticCode", record.diagnosticCode());
        PluginTrustDecision trust = record.trustDecision();
        if (trust != null) {
            put(props, "trust.pluginId", trust.pluginId());
            put(props, "trust.publisherKeyFingerprint", trust.publisherKeyFingerprint());
            put(props, "trust.repositoryId", trust.repositoryId());
            props.setProperty("trust.repositoryOfficial", Boolean.toString(trust.repositoryOfficial()));
            put(props, "trust.artifactSha256", trust.artifactSha256());
            props.setProperty("trust.executionMode", trust.executionMode().name());
            put(props, "trust.declaredPermissionDigest", trust.declaredPermissionDigest());
            props.setProperty("trust.permissionsDeclared", Boolean.toString(trust.permissionsDeclared()));
            if (!trust.declaredPermissions().isEmpty()) {
                props.setProperty("trust.declaredPermissions", String.join(",", trust.declaredPermissions()));
            }
            props.setProperty("trust.approvedAt", trust.approvedAt().toString());
            props.setProperty("trust.approvedAppSdkMajor", Integer.toString(trust.approvedAppSdkMajor()));
            props.setProperty("trust.approvalType", trust.approvalType().name());
        }
        put(props, "trustRevokedAt", record.trustRevokedAt() != null
                ? record.trustRevokedAt().toString() : null);

        Path sidecar = sidecarPath(artifact);
        normalizeLegacyBeforeWrite(artifact);
        requireSafeManagedParent(sidecar, true);
        writePropertiesAtomically(sidecar, props, "PixivDownloader plugin provenance");
        Path legacy = legacySidecarPath(artifact);
        if (!legacy.equals(sidecar)) {
            deleteManagedFileIfPresent(legacy);
        }
    }

    public void delete(Path artifact) throws IOException {
        deleteManagedFileIfPresent(sidecarPath(artifact));
        Path legacy = legacySidecarPath(artifact);
        if (!legacy.equals(sidecarPath(artifact))) {
            deleteManagedFileIfPresent(legacy);
        }
    }

    public void moveWithArtifact(Path sourceArtifact, Path targetArtifact, ArtifactMover mover) throws IOException {
        Path sourceSidecar = existingManagedSidecarPathStrict(sourceArtifact).orElse(null);
        Path existingTargetSidecar = existingManagedSidecarPathStrict(targetArtifact).orElse(null);
        Path targetSidecar = sidecarPath(targetArtifact);
        boolean sidecarMoved = false;
        if (sourceSidecar != null && existingTargetSidecar != null) {
            if (!Files.isSameFile(sourceSidecar, existingTargetSidecar)) {
                throw new IOException("target plugin provenance already exists: " + targetArtifact);
            }
            deleteManagedFileIfPresent(sourceSidecar);
            sourceSidecar = sidecarPath(sourceArtifact);
            targetSidecar = existingTargetSidecar;
            sidecarMoved = true;
        } else if (sourceSidecar != null) {
            requireSafeManagedParent(targetSidecar, true);
            move(sourceSidecar, targetSidecar);
            sidecarMoved = true;
        } else if (existingTargetSidecar != null) {
            sourceSidecar = sidecarPath(sourceArtifact);
            targetSidecar = existingTargetSidecar;
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
        return layout.pluginsRoot();
    }

    public Path provenanceDir() {
        return layout.provenanceDirectory();
    }

    private Path legacySidecarPath(Path artifact) {
        return artifact.resolveSibling(artifact.getFileName().toString() + SIDECAR_SUFFIX);
    }

    private Path existingSidecarPath(Path artifact) {
        Path current = sidecarPath(artifact);
        if (Files.exists(current)) {
            return current;
        }
        Path legacy = legacySidecarPath(artifact);
        if (!legacy.equals(current) && Files.exists(legacy)) {
            return legacy;
        }
        return current;
    }

    private void migrateLegacy(Path artifact, Path legacy) {
        try {
            Path current = sidecarPath(artifact).toAbsolutePath().normalize();
            requireSafeManagedParent(current, true);
            if (attributesIfPresent(current) == null) {
                move(legacy, current);
            }
        } catch (IOException ignored) {
            // Compatibility read succeeds even if best-effort atomic migration cannot persist yet.
        }
    }

    private void deleteLegacyIfSuperseded(Path artifact, Path current) {
        Path legacy = legacySidecarPath(artifact).toAbsolutePath().normalize();
        current = current.toAbsolutePath().normalize();
        if (legacy.equals(current)) {
            return;
        }
        try {
            deleteManagedFileIfPresent(legacy);
        } catch (IOException ignored) {
            // Best-effort cleanup; the central provenance record remains authoritative.
        }
    }

    /** 正常启动读允许收敛一次已完成写入但未删 legacy 的等价双副本；恢复路径仍保持零副作用严格拒绝。 */
    private Optional<Path> existingReadableSidecarPath(Path artifact) throws IOException {
        List<Path> existing = existingManagedSidecars(artifact);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        if (existing.size() == 1) {
            return Optional.of(existing.get(0));
        }
        Path current = sidecarPath(artifact).toAbsolutePath().normalize();
        Path legacy = legacySidecarPath(artifact).toAbsolutePath().normalize();
        PluginProvenanceRecord currentRecord;
        PluginProvenanceRecord legacyRecord;
        try {
            currentRecord = readStrictRecord(current);
            legacyRecord = readStrictRecord(legacy);
        } catch (RuntimeException e) {
            throw new IOException("duplicate plugin provenance copies are not both valid", e);
        }
        if (!currentRecord.equals(legacyRecord)) {
            throw new IOException("plugin provenance current and legacy copies differ: " + artifact);
        }
        deleteManagedFileIfPresent(legacy);
        return Optional.of(current);
    }

    private List<Path> existingManagedSidecars(Path artifact) throws IOException {
        List<Path> existing = new ArrayList<>(2);
        for (Path candidate : managedSidecarPaths(artifact)) {
            Path normalized = candidate.toAbsolutePath().normalize();
            if (!requireSafeManagedParent(normalized, false)) {
                continue;
            }
            BasicFileAttributes attributes = attributesIfPresent(normalized);
            if (attributes == null) {
                continue;
            }
            if (attributes.isSymbolicLink() || attributes.isOther() || !attributes.isRegularFile()) {
                throw new IOException("plugin provenance is not a regular file: " + normalized);
            }
            if (attributes.size() > MAX_RECOVERY_SIDECAR_BYTES) {
                throw new ReadBudgetExceededException(
                        "plugin provenance exceeds the recovery size limit: " + normalized,
                        0L);
            }
            existing.add(normalized);
        }
        return List.copyOf(existing);
    }

    private void normalizeLegacyBeforeWrite(Path artifact) throws IOException {
        List<Path> existing = existingManagedSidecars(artifact);
        if (existing.isEmpty()) {
            return;
        }
        Path current = sidecarPath(artifact).toAbsolutePath().normalize();
        Path legacy = legacySidecarPath(artifact).toAbsolutePath().normalize();
        if (existing.size() == 1) {
            if (existing.get(0).equals(legacy) && !legacy.equals(current)) {
                requireSafeManagedParent(current, true);
                if (attributesIfPresent(current) != null) {
                    throw new IOException("plugin provenance current copy appeared during legacy migration");
                }
                move(legacy, current);
            }
            return;
        }
        PluginProvenanceRecord currentRecord;
        PluginProvenanceRecord legacyRecord;
        try {
            currentRecord = readStrictRecord(current);
            legacyRecord = readStrictRecord(legacy);
        } catch (RuntimeException e) {
            throw new IOException("duplicate plugin provenance copies are not both valid", e);
        }
        if (!currentRecord.equals(legacyRecord)) {
            throw new IOException("plugin provenance current and legacy copies differ: " + artifact);
        }
        deleteManagedFileIfPresent(legacy);
    }

    /**
     * 校验受管文件从文件系统根到父目录的每个现存组件；创建时逐层 CREATE directory，绝不让
     * createDirectories/open 隐式跟随中间 symlink 或 junction。
     */
    private boolean requireSafeManagedParent(Path managedPath, boolean createMissing) throws IOException {
        Path pluginsRoot = layout.pluginsRoot().toAbsolutePath().normalize();
        Path normalized = managedPath.toAbsolutePath().normalize();
        if (!normalized.startsWith(pluginsRoot) || normalized.equals(pluginsRoot)
                || normalized.getParent() == null) {
            throw new IOException("plugin provenance path escapes its managed root: " + normalized);
        }
        Path parent = normalized.getParent();
        Path current = parent.getRoot();
        if (current == null) {
            throw new IOException("plugin provenance path must be absolute: " + normalized);
        }
        requirePlainDirectory(current);
        for (Path component : parent) {
            current = current.resolve(component.toString());
            BasicFileAttributes attributes = attributesIfPresent(current);
            if (attributes == null) {
                if (!createMissing) {
                    return false;
                }
                Files.createDirectory(current);
                attributes = attributesIfPresent(current);
            }
            if (attributes == null || attributes.isSymbolicLink() || attributes.isOther()
                    || !attributes.isDirectory()) {
                throw new IOException("plugin provenance parent is not a plain directory: " + current);
            }
        }
        return true;
    }

    private static void requirePlainDirectory(Path directory) throws IOException {
        BasicFileAttributes attributes = attributesIfPresent(directory);
        if (attributes == null || attributes.isSymbolicLink() || attributes.isOther()
                || !attributes.isDirectory()) {
            throw new IOException("plugin provenance filesystem root is not a plain directory: " + directory);
        }
    }

    private void deleteManagedFileIfPresent(Path path) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        if (!requireSafeManagedParent(normalized, false)) {
            return;
        }
        BasicFileAttributes attributes = attributesIfPresent(normalized);
        if (attributes == null) {
            return;
        }
        if (attributes.isSymbolicLink() || attributes.isOther() || !attributes.isRegularFile()) {
            throw new IOException("plugin provenance delete target is unsafe: " + normalized);
        }
        Files.delete(normalized);
    }

    private static PluginProvenanceRecord toRecordStrict(Properties props) {
        java.util.Set<String> allowedKeys = java.util.Set.of(
                "formatVersion", "source", "repositoryId", "officialRepository", "developmentOnly",
                "expectedSizeBytes", "expectedSha256", "artifactSizeBytes", "artifactSha256",
                "signature.formatVersion", "signature.algorithm", "signature.keyId", "signature.value",
                "status", "keyId", "publisher", "trustLabel", "publisherKeyFingerprint", "verifiedAt",
                "offlineStatus", "offlineVerifiedAt", "diagnosticCode", "trust.pluginId",
                "trust.publisherKeyFingerprint", "trust.repositoryId", "trust.repositoryOfficial",
                "trust.artifactSha256", "trust.executionMode", "trust.declaredPermissionDigest",
                "trust.permissionsDeclared", "trust.declaredPermissions",
                "trust.approvedAt", "trust.approvedAppSdkMajor", "trust.approvalType", "trustRevokedAt");
        for (String key : props.stringPropertyNames()) {
            if (!allowedKeys.contains(key)) {
                throw new IllegalArgumentException("unknown provenance property: " + key);
            }
        }
        String formatVersion = requiredText(props, "formatVersion");
        if (!"1".equals(formatVersion) && !"2".equals(formatVersion) && !"3".equals(formatVersion)) {
            throw new IllegalArgumentException("unsupported provenance formatVersion");
        }
        if (!"3".equals(formatVersion) && props.stringPropertyNames().stream().anyMatch(key ->
                "publisherKeyFingerprint".equals(key)
                        || key.startsWith("trust.")
                        || "trustRevokedAt".equals(key))) {
            throw new IllegalArgumentException("legacy provenance must not contain v3 trust properties");
        }
        PluginPackageSource source = PluginPackageSource.valueOf(requiredText(props, "source"));
        String officialValue = requiredText(props, "officialRepository");
        if (!"true".equals(officialValue) && !"false".equals(officialValue)) {
            throw new IllegalArgumentException("officialRepository must be true or false");
        }
        boolean officialRepository = Boolean.parseBoolean(officialValue);
        boolean developmentOnly;
        if (!"1".equals(formatVersion)) {
            String developmentValue = requiredText(props, "developmentOnly");
            if (!"true".equals(developmentValue) && !"false".equals(developmentValue)) {
                throw new IllegalArgumentException("developmentOnly must be true or false");
            }
            developmentOnly = Boolean.parseBoolean(developmentValue);
        } else {
            developmentOnly = false;
        }
        Long expectedSize = strictLongOrNull(props.getProperty("expectedSizeBytes"));
        if (expectedSize != null && expectedSize <= 0L) {
            throw new IllegalArgumentException("expectedSizeBytes must be positive");
        }
        String expectedSha256 = text(props, "expectedSha256");
        if (expectedSha256 != null && !expectedSha256.matches("[0-9A-Fa-f]{64}")) {
            throw new IllegalArgumentException("expectedSha256 is not a SHA-256 digest");
        }
        long artifactSize = Long.parseLong(requiredText(props, "artifactSizeBytes"));
        if (artifactSize <= 0L) {
            throw new IllegalArgumentException("artifactSizeBytes must be positive");
        }
        String artifactSha256 = requiredText(props, "artifactSha256").toLowerCase(java.util.Locale.ROOT);
        if (!artifactSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("artifactSha256 is not a SHA-256 digest");
        }

        boolean hasAnySignatureField = props.stringPropertyNames().stream()
                .anyMatch(name -> name.startsWith("signature."));
        SignatureMetadata signature = null;
        if (hasAnySignatureField) {
            signature = new SignatureMetadata(
                    Integer.parseInt(requiredText(props, "signature.formatVersion")),
                    requiredText(props, "signature.algorithm"),
                    requiredText(props, "signature.keyId"),
                    requiredText(props, "signature.value"));
        }
        if ("1".equals(formatVersion)
                && source == PluginPackageSource.LOCAL_UPLOAD
                && signature == null) {
            throw new IllegalArgumentException("legacy unsigned provenance is not trusted");
        }

        String repositoryId = text(props, "repositoryId");
        if (source == PluginPackageSource.MARKET_CATALOG) {
            if (repositoryId == null || expectedSize == null || expectedSha256 == null
                    || !"3".equals(formatVersion) && signature == null) {
                throw new IllegalArgumentException("catalog provenance is missing its source binding");
            }
            if (expectedSize != artifactSize || !expectedSha256.equalsIgnoreCase(artifactSha256)) {
                throw new IllegalArgumentException("catalog provenance observed artifact binding changed");
            }
        } else if (officialRepository || repositoryId != null || expectedSize != null
                || expectedSha256 != null) {
            throw new IllegalArgumentException("local provenance must not claim catalog source bindings");
        }

        VerificationStatus offlineStatus = strictStatusOrNull(props.getProperty("offlineStatus"));
        Instant offlineVerifiedAt = strictInstantOrNull(props.getProperty("offlineVerifiedAt"));
        if ((offlineStatus == null) != (offlineVerifiedAt == null)) {
            throw new IllegalArgumentException("offline verification status and timestamp must be recorded together");
        }
        boolean hasTrustProperties = props.stringPropertyNames().stream()
                .anyMatch(name -> name.startsWith("trust."));
        PluginTrustDecision trustDecision = null;
        if (hasTrustProperties) {
            String trustOfficial = requiredText(props, "trust.repositoryOfficial");
            if (!"true".equals(trustOfficial) && !"false".equals(trustOfficial)) {
                throw new IllegalArgumentException("trust.repositoryOfficial must be true or false");
            }
            String permissionsDeclared = props.getProperty("trust.permissionsDeclared");
            if (permissionsDeclared != null
                    && !"true".equals(permissionsDeclared) && !"false".equals(permissionsDeclared)) {
                throw new IllegalArgumentException("trust.permissionsDeclared must be true or false");
            }
            if (!Boolean.parseBoolean(permissionsDeclared)
                    && props.containsKey("trust.declaredPermissions")) {
                throw new IllegalArgumentException(
                        "trust.declaredPermissions requires an explicit permission declaration");
            }
            List<String> declaredPermissions = permissionsDeclared == null
                    || !Boolean.parseBoolean(permissionsDeclared)
                    ? List.of()
                    : splitCommaSeparated(props.getProperty("trust.declaredPermissions"));
            trustDecision = new PluginTrustDecision(
                    requiredText(props, "trust.pluginId"),
                    text(props, "trust.publisherKeyFingerprint"),
                    text(props, "trust.repositoryId"),
                    Boolean.parseBoolean(trustOfficial),
                    requiredText(props, "trust.artifactSha256"),
                    PluginExecutionMode.valueOf(requiredText(props, "trust.executionMode")),
                    requiredText(props, "trust.declaredPermissionDigest"),
                    Instant.parse(requiredText(props, "trust.approvedAt")),
                    Integer.parseInt(requiredText(props, "trust.approvedAppSdkMajor")),
                    PluginTrustDecision.ApprovalType.valueOf(requiredText(props, "trust.approvalType")),
                    Boolean.parseBoolean(permissionsDeclared), declaredPermissions);
        }
        return new PluginProvenanceRecord(
                source,
                repositoryId,
                officialRepository,
                developmentOnly,
                expectedSize,
                expectedSha256 != null ? expectedSha256.toLowerCase(java.util.Locale.ROOT) : null,
                artifactSize,
                artifactSha256,
                signature,
                VerificationStatus.valueOf(requiredText(props, "status")),
                text(props, "keyId"),
                text(props, "publisher"),
                text(props, "trustLabel"),
                text(props, "publisherKeyFingerprint"),
                strictInstantOrNull(props.getProperty("verifiedAt")),
                offlineStatus,
                offlineVerifiedAt,
                text(props, "diagnosticCode"),
                trustDecision,
                strictInstantOrNull(props.getProperty("trustRevokedAt")));
    }

    private static String requiredText(Properties props, String key) {
        String value = props.getProperty(key);
        if (value == null || value.isBlank() || !value.equals(value.trim())) {
            throw new IllegalArgumentException("missing or malformed provenance property: " + key);
        }
        return value;
    }

    private static Long strictLongOrNull(String value) {
        return value == null || value.isBlank() ? null : Long.valueOf(value);
    }

    private static Instant strictInstantOrNull(String value) {
        return value == null || value.isBlank() ? null : Instant.parse(value);
    }

    private static VerificationStatus strictStatusOrNull(String value) {
        return value == null || value.isBlank() ? null : VerificationStatus.valueOf(value);
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

    private static List<String> splitCommaSeparated(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(value.split(",", -1)).map(String::trim).toList();
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

    private void writePropertiesAtomically(Path target, Properties properties, String comment)
            throws IOException {
        byte[] serialized = serializeProperties(properties, comment);
        target = target.toAbsolutePath().normalize();
        requireSafeManagedParent(target, true);
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        BasicFileAttributes temporaryAttributes = attributesIfPresent(temporary);
        if (temporaryAttributes != null) {
            if (temporaryAttributes.isSymbolicLink() || temporaryAttributes.isOther()
                    || !temporaryAttributes.isRegularFile()) {
                throw new IOException("plugin provenance temporary path is unsafe: " + temporary);
            }
            Files.delete(temporary);
        }
        BasicFileAttributes targetAttributes = attributesIfPresent(target);
        if (targetAttributes != null && (targetAttributes.isSymbolicLink() || targetAttributes.isOther()
                || !targetAttributes.isRegularFile())) {
            throw new IOException("plugin provenance target path is unsafe: " + target);
        }
        java.util.Set<OpenOption> options = java.util.Set.of(
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        try (SeekableByteChannel channel = Files.newByteChannel(temporary, options)) {
            ByteBuffer buffer = ByteBuffer.wrap(serialized);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            if (channel instanceof FileChannel fileChannel) {
                fileChannel.force(true);
            }
        }
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.deleteIfExists(temporary);
            throw new IOException("filesystem does not support atomic provenance persistence", e);
        }
    }

    private static byte[] serializeProperties(Properties properties, String comment) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        OutputStream bounded = new OutputStream() {
            private long count;

            @Override
            public void write(int value) throws IOException {
                requireCapacity(1);
                bytes.write(value);
                count++;
            }

            @Override
            public void write(byte[] value, int offset, int length) throws IOException {
                Objects.checkFromIndexSize(offset, length, value.length);
                requireCapacity(length);
                bytes.write(value, offset, length);
                count += length;
            }

            private void requireCapacity(int increment) throws IOException {
                if (increment < 0 || count > MAX_RECOVERY_SIDECAR_BYTES - increment) {
                    throw new IOException("generated plugin provenance exceeds the supported size");
                }
            }
        };
        try (Writer writer = new OutputStreamWriter(bounded, StandardCharsets.UTF_8)) {
            properties.store(writer, comment);
        }
        return bytes.toByteArray();
    }

    private static BasicFileAttributes attributesIfPresent(Path path) throws IOException {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException e) {
            return null;
        }
    }

    private static final class RejectingProperties extends Properties {
        @Override
        public synchronized Object put(Object key, Object value) {
            if (containsKey(key)) {
                throw new IllegalArgumentException("duplicate provenance property: " + key);
            }
            return super.put(key, value);
        }
    }

    private void move(Path source, Path target) throws IOException {
        source = source.toAbsolutePath().normalize();
        target = target.toAbsolutePath().normalize();
        if (!requireSafeManagedParent(source, false)) {
            throw new IOException("plugin provenance source parent is missing: " + source);
        }
        requireSafeManagedParent(target, true);
        BasicFileAttributes sourceAttributes = attributesIfPresent(source);
        if (sourceAttributes == null || sourceAttributes.isSymbolicLink() || sourceAttributes.isOther()
                || !sourceAttributes.isRegularFile()) {
            throw new IOException("plugin provenance source is not a plain regular file: " + source);
        }
        if (attributesIfPresent(target) != null) {
            throw new java.nio.file.FileAlreadyExistsException(target.toString());
        }
        boolean linked = false;
        try {
            // Windows 的 ATOMIC_MOVE 会隐式 REPLACE_EXISTING，无法实现事务所需的 no-clobber。
            // 同卷 hardlink 以 CREATE_NEW 语义发布目标名，再删除源名；崩溃留下的双名字由恢复器按同一文件身份收敛。
            Files.createLink(target, source);
            linked = true;
            if (!Files.isSameFile(source, target)) {
                throw new IOException("plugin provenance hardlink did not preserve file identity");
            }
            Files.delete(source);
        } catch (IOException | RuntimeException e) {
            if (linked) {
                try {
                    if (attributesIfPresent(source) != null && attributesIfPresent(target) != null
                            && Files.isSameFile(source, target)) {
                        Files.delete(target);
                    }
                } catch (IOException cleanupFailure) {
                    e.addSuppressed(cleanupFailure);
                }
            }
            throw e;
        }
    }

    @FunctionalInterface
    public interface ArtifactMover {
        void move(Path source, Path target) throws IOException;
    }

    public record MeasuredSidecar(Path path, long byteCount) {

        public MeasuredSidecar {
            path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
            if (byteCount < 0L || byteCount > MAX_RECOVERY_SIDECAR_BYTES) {
                throw new IllegalArgumentException("sidecar byte count is outside the supported range");
            }
        }
    }

    public record MeasuredProvenance(PluginProvenanceRecord record, long byteCount) {

        public MeasuredProvenance {
            record = Objects.requireNonNull(record, "record");
            if (byteCount < 0L || byteCount > MAX_RECOVERY_SIDECAR_BYTES * 2L) {
                throw new IllegalArgumentException("sidecar byte count is outside the supported range");
            }
        }
    }

    public static final class ReadBudgetExceededException extends IOException {
        private final long byteCount;

        public ReadBudgetExceededException(String message, long byteCount) {
            super(message);
            this.byteCount = byteCount;
        }

        public long byteCount() {
            return byteCount;
        }
    }

    public static final class InvalidProvenanceException extends IOException {
        private final long byteCount;

        public InvalidProvenanceException(String message, long byteCount, Throwable cause) {
            super(message, cause);
            this.byteCount = byteCount;
        }

        public long byteCount() {
            return byteCount;
        }
    }

    public static final class ProvenanceReadException extends IOException {
        private final long byteCount;

        public ProvenanceReadException(String message, long byteCount, Throwable cause) {
            super(message, cause);
            this.byteCount = byteCount;
        }

        public long byteCount() {
            return byteCount;
        }
    }
}
