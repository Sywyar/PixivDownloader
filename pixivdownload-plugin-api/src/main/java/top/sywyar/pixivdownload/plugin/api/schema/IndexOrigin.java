package top.sywyar.pixivdownload.plugin.api.schema;

/**
 * 索引来源：显式 {@code CREATE INDEX}，或表定义内的 {@code UNIQUE} 约束。
 */
public enum IndexOrigin {
    CREATE_INDEX,
    UNIQUE_CONSTRAINT
}
