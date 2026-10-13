package top.sywyar.pixivdownload.plugin.api.storage.relational;

import java.util.List;
import top.sywyar.pixivdownload.plugin.api.database.migration.MigrationSchema;

/** 插件声明派生索引与关联清理的业务意图；提供者拥有物理对象和语法。 */
public final class StorageFeatures {
    private StorageFeatures() {}

    /** 整数单列主键正文的字面子串搜索；activeValue 以外的记录不可见。 */
    public record TextIndex(String name, String table, String key, String content, String activeColumn, long activeValue) {
        /**
         * 校验正文索引使用的逻辑标识符。
         * @param activeColumn 可见状态列
         * @param activeValue 可见记录的状态值
         * @param content 权威正文列
         * @param key 已声明的身份列
         * @param name 符合标识符规则的逻辑名称
         * @param table 当前作用域中已声明的表或引用别名
         */
        public TextIndex {
            for (String id : List.of(name, table, key, content, activeColumn)) MigrationSchema.identifier(id);
        }
    }
    /** 索引可以物化，也可以由提供者从权威正文派生查询；后者不创建伪索引。 */
    public enum TextIndexMode {
        /** 使用由提供者维护的物化索引。 */
        MATERIALIZED,
        /** 直接从权威正文执行具有相同语义的查询。 */
        SOURCE_QUERY
    }

    /** 指定列被更新为给定值时，在同一数据库语句内删除匹配主键的关联行。 */
    public record Cleanup(String name, String table, String key, String changedColumn, long value,
                          List<Dependent> dependents) {
        /**
         * 固定清理规则并拒绝空集合或重复的关联对象。
         * @param changedColumn 触发清理的状态列
         * @param dependents 按主记录身份清理的关联表和列
         * @param key 已声明的身份列
         * @param name 符合标识符规则的逻辑名称
         * @param table 当前作用域中已声明的表或引用别名
         * @param value 触发关联清理的状态值
         */
        public Cleanup {
            for (String id : List.of(name, table, key, changedColumn)) MigrationSchema.identifier(id);
            dependents = List.copyOf(dependents);
            if (dependents.isEmpty() || dependents.stream().distinct().count() != dependents.size())
                throw new IllegalArgumentException("STORAGE_CLEANUP_INVALID");
        }
    }
    /** 按主记录身份匹配待删除关联行的表和列。 */
    public record Dependent(String table, String key) {
        /**
         * 校验关联表和身份列。
         * @param key 已声明的身份列
         * @param table 当前作用域中已声明的表或引用别名
         */
        public Dependent { MigrationSchema.identifier(table); MigrationSchema.identifier(key); }
    }
}
