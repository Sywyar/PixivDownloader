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
        List<PathColumnSpec> pathColumns,
        SharedAccess sharedAccess
) {
    /**
     * 创建不向其它 owner 授予访问的结构声明。
     * @param columnMigrations 本 owner 表的安全补列声明
     * @param pathColumns 受宿主路径前缀管理的列
     * @param tables 当前 owner 拥有的表声明
     */
    public SchemaContribution(List<TableSpec> tables, List<ColumnMigrationSpec> columnMigrations,
                              List<PathColumnSpec> pathColumns) {
        this(tables, columnMigrations, pathColumns, null);
    }

    /** 宿主公开的逻辑存储域；仅可信核心声明可授予访问，不构成其它插件私有表的授权。 */
    public record SharedAccess(String storageId, boolean readOnly) {
        /**
         * 校验公开存储域的稳定身份。
         * @param readOnly 是否只授予读取权限
         * @param storageId 公开存储域的稳定身份
         */
        public SharedAccess {
            if (storageId == null || !storageId.matches("[a-z][a-z0-9-]*"))
                throw new IllegalArgumentException("STORAGE_ID_INVALID");
        }
    }
    /**
     * 创建 {@code SchemaContribution} 实例。
     *
     * @param tables 表集合
     * @param columnMigrations {@code columnMigrations} 对应的值
     * @param pathColumns 路径列集合
     * @param sharedAccess 核心授予的公开存储域；未公开时为 null
     */
    public SchemaContribution {
        tables = List.copyOf(tables);
        columnMigrations = List.copyOf(columnMigrations);
        pathColumns = List.copyOf(pathColumns);
    }
}
