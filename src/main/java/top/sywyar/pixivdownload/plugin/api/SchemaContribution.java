package top.sywyar.pixivdownload.plugin.api;

import java.util.List;

/**
 * 插件声明自己的表、索引和补列规则。
 * 由 schema registry 合并后统一建表 / 补列 / 校验冲突。
 *
 * @param ownerPluginId    声明方插件 id
 * @param tables           插件拥有的表
 * @param indexes          建在他表（含核心表）上的附加索引；自有表内联索引写在 {@link TableSpec#indexes()}
 * @param columnMigrations 对既有表的安全补列规则
 * @param pathColumns      含路径前缀编码的列（须纳入符号根 {@code {0}} 折叠与迁移改写）
 */
public record SchemaContribution(
        String ownerPluginId,
        List<TableSpec> tables,
        List<IndexSpec> indexes,
        List<ColumnMigrationSpec> columnMigrations,
        List<PathColumnSpec> pathColumns
) {
    public SchemaContribution {
        tables = List.copyOf(tables);
        indexes = List.copyOf(indexes);
        columnMigrations = List.copyOf(columnMigrations);
        pathColumns = List.copyOf(pathColumns);
    }
}
