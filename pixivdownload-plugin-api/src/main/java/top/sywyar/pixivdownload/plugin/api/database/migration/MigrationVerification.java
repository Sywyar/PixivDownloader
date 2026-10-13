package top.sywyar.pixivdownload.plugin.api.database.migration;

import java.util.Map;

/** 同一中性格式的完整回读结果；序列是禁止复用的历史高水位，不是下一次生成值。 */
public record MigrationVerification(Map<String, TableDigest> tables, Map<String, Long> sequences,
                                    Map<String, Long> referenceWarnings) {
    /**
     * 复制并校验全部验证值。
     * @param tables 表名到行数和摘要
     * @param sequences 生成键历史高水位
     * @param referenceWarnings 引用缺失的正数计数
     */
    public MigrationVerification {
        tables = Map.copyOf(tables);
        sequences = Map.copyOf(sequences);
        referenceWarnings = Map.copyOf(referenceWarnings);
        if (!tables.keySet().containsAll(sequences.keySet()) || sequences.values().stream().anyMatch(value -> value < 0)
                || referenceWarnings.values().stream().anyMatch(value -> value <= 0))
            throw new IllegalArgumentException("MIGRATION_VERIFICATION_INVALID");
    }
    /** SHA-256 对有类型标签和长度的值逐行计算，空表仍有摘要。 */
    public record TableDigest(long rows, String sha256) {
        /**
         * 拒绝负行数和非 SHA-256 摘要。
         * @param rows 完整行数
         * @param sha256 小写十六进制摘要
         */
        public TableDigest {
            if (rows < 0 || sha256 == null || !sha256.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("MIGRATION_DIGEST_INVALID");
        }
    }
}
