package top.sywyar.pixivdownload.plugin.api.database.migration;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** 格式版本与 SDK 版本独立；无法理解的版本必须在目标写入前拒绝，不尝试猜测兼容。 */
public record MigrationSnapshot(int formatVersion, MigrationSchema schema, MigrationVerification verification,
                                 Path backup, String sourceRevision, List<String> rebuildObjects) {
    /**
     * 当前中性交换格式版本。
     */
    public static final int FORMAT_VERSION = 1;
    /**
     * 固定完整校验结果及源身份。
     * @param formatVersion 交换格式版本
     * @param schema 规范化结构
     * @param verification 按存储文本规则处理工作副本后的回读结果；不改变原始备份
     * @param backup 耐久备份绝对路径
     * @param sourceRevision 无秘密的源变化凭据
     * @param rebuildObjects 需要重建的可再生对象
     */
    public MigrationSnapshot {
        requireVersion(formatVersion);
        Objects.requireNonNull(schema);
        Objects.requireNonNull(verification);
        if (!verification.tables().keySet().equals(schema.tables().stream().map(top.sywyar.pixivdownload.plugin.api.schema.TableSpec::name)
                .collect(java.util.stream.Collectors.toSet()))) throw new IllegalArgumentException("MIGRATION_VERIFICATION_INVALID");
        backup = Objects.requireNonNull(backup).toAbsolutePath().normalize();
        if (sourceRevision == null || sourceRevision.isBlank())
            throw new IllegalArgumentException("MIGRATION_REVISION_INVALID");
        rebuildObjects = List.copyOf(rebuildObjects);
    }
    /**
     * 拒绝不支持的交换格式。
     * @param version 待核对版本
     */
    public static void requireVersion(int version) {
        if (version != FORMAT_VERSION) throw new IllegalArgumentException("MIGRATION_FORMAT_UNSUPPORTED");
    }
}
