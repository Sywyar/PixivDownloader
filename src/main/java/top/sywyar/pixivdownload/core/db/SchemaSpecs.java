package top.sywyar.pixivdownload.core.db;

import top.sywyar.pixivdownload.plugin.api.ColumnSpec;
import top.sywyar.pixivdownload.plugin.api.IndexOrigin;
import top.sywyar.pixivdownload.plugin.api.IndexSpec;

import java.util.List;

/**
 * 各领域 {@code XxxSchemaContribution} 共用的规格构造助手。
 * 仅是 plugin.api 规格 record 的构造别名，不含任何归一化或校验逻辑。
 */
public final class SchemaSpecs {

    private SchemaSpecs() {}

    public static ColumnSpec column(String name,
                                    String type,
                                    boolean notNull,
                                    String defaultValue,
                                    int primaryKeyPosition) {
        return new ColumnSpec(name, type, notNull, defaultValue, primaryKeyPosition);
    }

    public static IndexSpec explicitIndex(String name, boolean unique, String... columns) {
        return new IndexSpec(name, IndexOrigin.CREATE_INDEX, unique, List.of(columns));
    }

    public static IndexSpec uniqueConstraint(String... columns) {
        return new IndexSpec(null, IndexOrigin.UNIQUE_CONSTRAINT, true, List.of(columns));
    }
}
