package top.sywyar.pixivdownload.plugin.api;

import java.util.List;

/**
 * 索引声明。字段形态与 {@code ManagedDatabaseSchema.IndexSpec} 对齐。
 *
 * @param name 显式索引名；{@link IndexOrigin#UNIQUE_CONSTRAINT} 来源时为 {@code null}
 */
public record IndexSpec(
        String name,
        IndexOrigin origin,
        boolean unique,
        List<String> columns
) {
    public IndexSpec {
        columns = List.copyOf(columns);
    }
}
