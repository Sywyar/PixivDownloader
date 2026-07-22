package top.sywyar.pixivdownload.plugin.runtime.install.provenance;

import top.sywyar.pixivdownload.plugin.runtime.artifact.PluginRuntimeLayout;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageOrigin;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageSource;
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
        byte[] bytes;
        try (SeekableByteChannel channel = Files.newByteChannel(sidecar, options);
             InputStream input = Channels.newInputStream(channel)) {
            bytes = input.readNBytes((int) effectiveMaximum + 1);
        }
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
        props.setProperty("formatVersion", "1");
        props.setProperty("source", record.source().name());
        put(props, "repositoryId", record.repositoryId());
        props.setProperty("officialRepository", Boolean.toString(record.officialRepository()));
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
        put(props, "verifiedAt", record.verifiedAt() != null ? record.verifiedAt().toString() : null);
        put(props, "offlineStatus", record.offlineStatus() != null ? record.offlineStatus().name() : null);
        put(props, "offlineVerifiedAt", record.offlineVerifiedAt() != null
                ? record.offlineVerifiedAt().toString() : null);
        put(props, "diagnosticCode", record.diagnosticCode());

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
        Path sourceSidecar = existingSidecarPath(sourceArtifact);
        Path targetSidecar = sidecarPath(targetArtifact);
        boolean sidecarMoved = false;
        if (sourceSidecar != null && Files.exists(sourceSidecar)) {
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
                throw new IOException("plugin provenance exceeds the recovery size limit: " + normalized);
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
                "formatVersion", "source", "repositoryId", "officialRepository",
                "expectedSizeBytes", "expectedSha256", "artifactSizeBytes", "artifactSha256",
                "signature.formatVersion", "signature.algorithm", "signature.keyId", "signature.value",
                "status", "keyId", "publisher", "trustLabel", "verifiedAt",
                "offlineStatus", "offlineVerifiedAt", "diagnosticCode");
        for (String key : props.stringPropertyNames()) {
            if (!allowedKeys.contains(key)) {
                throw new IllegalArgumentException("unknown provenance property: " + key);
            }
        }
        if (!"1".equals(requiredText(props, "formatVersion"))) {
            throw new IllegalArgumentException("unsupported provenance formatVersion");
        }
        PluginPackageSource source = PluginPackageSource.valueOf(requiredText(props, "source"));
        String officialValue = requiredText(props, "officialRepository");
        if (!"true".equals(officialValue) && !"false".equals(officialValue)) {
            throw new IllegalArgumentException("officialRepository must be true or false");
        }
        boolean officialRepository = Boolean.parseBoolean(officialValue);
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

        String repositoryId = text(props, "repositoryId");
        if (source == PluginPackageSource.MARKET_CATALOG) {
            if (repositoryId == null || expectedSize == null || expectedSha256 == null || signature == null) {
                throw new IllegalArgumentException("catalog provenance is missing its signed source binding");
            }
            if (expectedSize != artifactSize || !expectedSha256.equalsIgnoreCase(artifactSha256)) {
                throw new IllegalArgumentException("catalog provenance observed artifact binding changed");
            }
        } else if (officialRepository || repositoryId != null || expectedSize != null
                || expectedSha256 != null || signature != null) {
            throw new IllegalArgumentException("local provenance must not claim catalog source bindings");
        }

        VerificationStatus offlineStatus = strictStatusOrNull(props.getProperty("offlineStatus"));
        Instant offlineVerifiedAt = strictInstantOrNull(props.getProperty("offlineVerifiedAt"));
        if ((offlineStatus == null) != (offlineVerifiedAt == null)) {
            throw new IllegalArgumentException("offline verification status and timestamp must be recorded together");
        }
        return new PluginProvenanceRecord(
                source,
                repositoryId,
                officialRepository,
                expectedSize,
                expectedSha256 != null ? expectedSha256.toLowerCase(java.util.Locale.ROOT) : null,
                artifactSize,
                artifactSha256,
                signature,
                VerificationStatus.valueOf(requiredText(props, "status")),
                text(props, "keyId"),
                text(props, "publisher"),
                text(props, "trustLabel"),
                strictInstantOrNull(props.getProperty("verifiedAt")),
                offlineStatus,
                offlineVerifiedAt,
                text(props, "diagnosticCode"));
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
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException | java.nio.file.FileAlreadyExistsException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
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
            if (byteCount < 0L || byteCount > MAX_RECOVERY_SIDECAR_BYTES) {
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
}
