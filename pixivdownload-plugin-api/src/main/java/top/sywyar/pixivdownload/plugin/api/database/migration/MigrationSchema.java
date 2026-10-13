package top.sywyar.pixivdownload.plugin.api.database.migration;

import top.sywyar.pixivdownload.plugin.api.schema.ColumnSpec;
import top.sywyar.pixivdownload.plugin.api.schema.TableSpec;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 已合并的中性结构与数据不变量，不携带引擎 DDL。支持 INTEGER、REAL、TEXT、BLOB，时间值不换单位。
 * seed 只补入规范化副本中缺失的主键；引用警告只核对、不删除孤儿或添加外键。
 */
public record MigrationSchema(int formatVersion, List<TableSpec> tables, List<Seed> seeds,
                              List<Reference> references) {
    /**
     * 复制并校验声明，拒绝无法交换的类型和结构。
     * @param formatVersion 交换格式版本
     * @param tables 完整声明表
     * @param seeds 仅补齐缺失主键的初始数据
     * @param references 仅核对的引用
     */
    public MigrationSchema {
        MigrationSnapshot.requireVersion(formatVersion);
        tables = tables.stream().sorted(Comparator.comparing(TableSpec::name)).toList();
        seeds = List.copyOf(seeds);
        references = List.copyOf(references);
        Set<String> names = tables.stream().map(TableSpec::name).collect(Collectors.toSet());
        if (names.size() != tables.size()) throw invalid();
        for (var table : tables) {
            identifier(table.name());
            if (table.columns().isEmpty() || primaryKey(table).isEmpty()) throw invalid();
            Set<String> columns = table.columns().stream().map(ColumnSpec::name).collect(Collectors.toSet());
            if (columns.size() != table.columns().size()) throw invalid();
            for (var column : table.columns()) {
                identifier(column.name());
                if (!Set.of("INTEGER", "REAL", "TEXT", "BLOB").contains(column.type())) throw invalid();
                if (column.primaryKeyPosition() < 0 || column.defaultValue() != null && !column.defaultValue().matches(
                        "(?is)NULL|[-+]?[0-9]+(?:\\.[0-9]+)?|'(?:[^']|'')*'")) throw invalid();
                if (column.autoIncrement() && (!column.type().equals("INTEGER")
                        || column.primaryKeyPosition() != 1 || primaryKey(table).size() != 1)) throw invalid();
            }
            var keys = table.columns().stream().filter(c -> c.primaryKeyPosition() > 0)
                    .map(ColumnSpec::primaryKeyPosition).sorted().toList();
            for (int i = 0; i < keys.size(); i++) if (keys.get(i) != i + 1) throw invalid();
            for (var index : table.indexes()) {
                if (index.name() != null) identifier(index.name());
                if (index.origin() == top.sywyar.pixivdownload.plugin.api.schema.IndexOrigin.CREATE_INDEX && index.name() == null)
                    throw invalid();
                if (index.columns().isEmpty() || !columns.containsAll(index.columns())) throw invalid();
            }
        }
        for (var seed : seeds) {
            var table = find(tables, seed.table());
            if (!seed.values().keySet().containsAll(primaryKey(table))
                    || !table.columns().stream().map(ColumnSpec::name).collect(Collectors.toSet())
                    .containsAll(seed.values().keySet())) throw invalid();
        }
        if (references.stream().map(Reference::key).distinct().count() != references.size()) throw invalid();
        for (var reference : references) {
            requireColumn(find(tables, reference.table()), reference.column());
            requireColumn(find(tables, reference.targetTable()), reference.targetColumn());
        }
    }

    /**
     * 查找声明表。
     * @param name 规范表名
     * @return 唯一表声明
     */
    public TableSpec table(String name) { return find(tables, name); }
    /**
     * 按复合主键次序返回列名。
     * @param table 声明表
     * @return 有序主键列
     */
    public static List<String> primaryKey(TableSpec table) {
        return table.columns().stream().filter(c -> c.primaryKeyPosition() > 0)
                .sorted(Comparator.comparingInt(ColumnSpec::primaryKeyPosition)).map(ColumnSpec::name).toList();
    }
    /** 标识符不接受 SQL 片段，引用只负责方言转义，不承担授权。 */
    /**
     * 校验中性标识符，拒绝 SQL 片段。
     * @param value 标识符
     * @return 校验后的原值
     */
    public static String identifier(String value) {
        if (value == null || !value.matches("[a-z_][a-z0-9_]*")) throw invalid();
        return value;
    }
    private static TableSpec find(List<TableSpec> tables, String name) {
        return tables.stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow(MigrationSchema::invalid);
    }
    private static void requireColumn(TableSpec table, String column) {
        if (table.columns().stream().noneMatch(c -> c.name().equals(column))) throw invalid();
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("MIGRATION_SCHEMA_INVALID"); }

    /** 以主键判断是否缺失；不会覆盖已有同主键数据。 */
    public record Seed(String table, Map<String, Object> values) {
        /**
         * 复制初始数据，不覆盖同主键源行。
         * @param table 声明表名
         * @param values 声明列名到中性值
         */
        public Seed { values = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(values)); }
    }
    /** 精确值引用，或字符串开头 {整数} 的引用（0 为符号根、无需目标行）。 */
    public record Reference(String table, String column, String targetTable, String targetColumn, Encoding encoding) {
        /**
         * 固定引用编码。
         * @param table 引用表
         * @param column 引用列
         * @param targetTable 目标表
         * @param targetColumn 目标列
         * @param encoding 引用编码
         */
        public Reference { Objects.requireNonNull(encoding); }
        /**
         * 生成跨端一致的警告标识。
         * @return 不包含数据值的引用键
         */
        public String key() { return table + ":" + column + ":" + targetTable
                + (encoding == Encoding.VALUE ? ":" + targetColumn : ""); }
    }
    /** 引用值的中性编码。 */
    public enum Encoding {
        /** 完整列值等于目标键。 */
        VALUE,
        /** 字符串以花括号整数开头，零表示符号根。 */
        BRACED_PREFIX
    }
}
