package top.sywyar.pixivdownload.core.db.schema;

import top.sywyar.pixivdownload.plugin.api.schema.ColumnSpec;
import top.sywyar.pixivdownload.plugin.api.schema.IndexOrigin;
import top.sywyar.pixivdownload.plugin.api.schema.IndexSpec;

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

    /** {@code <name> INTEGER PRIMARY KEY AUTOINCREMENT} 形态的单列自增主键。 */
    public static ColumnSpec autoIncrementPrimaryKey(String name) {
        return new ColumnSpec(name, "INTEGER", false, null, 1, true);
    }

    public static IndexSpec explicitIndex(String name, boolean unique, String... columns) {
        return new IndexSpec(name, IndexOrigin.CREATE_INDEX, unique, List.of(columns));
    }

    public static IndexSpec uniqueConstraint(String... columns) {
        return new IndexSpec(null, IndexOrigin.UNIQUE_CONSTRAINT, true, List.of(columns));
    }
}
