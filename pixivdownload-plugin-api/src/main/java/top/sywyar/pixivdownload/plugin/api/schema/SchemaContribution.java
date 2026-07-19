package top.sywyar.pixivdownload.plugin.api.schema;

import java.util.List;

/**
 * 插件声明自己的表、补列规则和路径列。
 * 由宿主以已注册插件身份盖章所有权，再由 schema registry 统一建表 / 补列 / 校验冲突。
 *
 * @param tables           插件拥有的表
 * @param columnMigrations 对本插件自有表的安全补列规则
 * @param pathColumns      含路径前缀编码的列（须纳入符号根 {@code {0}} 折叠与迁移改写）
 */
public record SchemaContribution(
        List<TableSpec> tables,
        List<ColumnMigrationSpec> columnMigrations,
        List<PathColumnSpec> pathColumns
) {
    public SchemaContribution {
        tables = List.copyOf(tables);
        columnMigrations = List.copyOf(columnMigrations);
        pathColumns = List.copyOf(pathColumns);
    }
}
