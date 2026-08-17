package top.sywyar.pixivdownload.plugin.api.schema;

/**
 * 索引来源：显式 {@code CREATE INDEX}，或表定义内的 {@code UNIQUE} 约束。
 */
public enum IndexOrigin {
    /**
     * 表示 {@code CREATE_INDEX} 状态。
     */
    CREATE_INDEX,
    /**
     * 表示 {@code UNIQUE_CONSTRAINT}。
     */
    UNIQUE_CONSTRAINT
}
