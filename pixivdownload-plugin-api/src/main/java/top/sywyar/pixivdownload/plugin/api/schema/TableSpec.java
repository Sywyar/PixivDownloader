package top.sywyar.pixivdownload.plugin.api.schema;

import java.util.List;
import top.sywyar.pixivdownload.plugin.api.storage.relational.Expression;

/**
 * 插件自有表的纯数据声明。宿主负责 owner 与冲突检查，数据库提供者负责物理结构；
 * 本契约不引用宿主 schema 实现模型。
 *
 * @param name 名称
 * @param columns 列集合
 * @param indexes 索引列表
 * @param check 当前表的中性行约束；FALSE 拒绝写入，TRUE 或 NULL 接受
 * @param previousChecks owner 明确允许升级的旧行约束；仅用于匹配迁移源，不放宽当前约束
 * @param previousPrimaryKey 允许扩展的旧联合主键；新主键必须以它为前缀，新增键列须非空且有默认值
 */
public record TableSpec(
        String name,
        List<ColumnSpec> columns,
        List<IndexSpec> indexes,
        Expression check,
        List<Expression> previousChecks,
        List<String> previousPrimaryKey
) {
    /**
     * 创建 {@code TableSpec} 实例。
     *
     * @param name 名称
     * @param columns 列集合
     * @param indexes 索引列表
     * @param check 中性行约束，不允许子查询、聚合、传入行或其它表引用
     * @param previousChecks 可迁入的旧中性行约束，缺省为空
     * @param previousPrimaryKey 可扩展的旧联合主键，缺省为空
     */
    public TableSpec {
        columns = List.copyOf(columns);
        indexes = List.copyOf(indexes);
        previousChecks = previousChecks == null ? List.of() : List.copyOf(previousChecks);
        previousPrimaryKey = previousPrimaryKey == null ? List.of() : List.copyOf(previousPrimaryKey);
        if (!previousPrimaryKey.isEmpty()) {
            var keys = columns.stream().filter(c -> c.primaryKeyPosition() > 0)
                    .sorted(java.util.Comparator.comparingInt(ColumnSpec::primaryKeyPosition)).toList();
            if (previousPrimaryKey.size() < 2 || keys.size() <= previousPrimaryKey.size()
                    || !keys.subList(0, previousPrimaryKey.size()).stream().map(ColumnSpec::name).toList().equals(previousPrimaryKey)
                    || keys.subList(previousPrimaryKey.size(), keys.size()).stream()
                    .anyMatch(c -> !c.notNull() || c.defaultValue() == null || c.autoIncrement()))
                throw new IllegalArgumentException("STORAGE_PRIMARY_KEY_MIGRATION_INVALID");
        }
        if (check != null && !"BOOLEAN".equals(checkType(name, columns, check))) throw invalidCheck();
        if (!previousChecks.isEmpty() && check == null) throw invalidCheck();
        for (var previous : previousChecks)
            if (!"BOOLEAN".equals(checkType(name, columns, previous))) throw invalidCheck();
    }

    /** 创建携带已知旧约束的声明。
     * @param name 表名
     * @param columns 列集合
     * @param indexes 索引集合
     * @param check 当前中性约束
     * @param previousChecks 可迁入的旧中性约束
     */
    public TableSpec(String name, List<ColumnSpec> columns, List<IndexSpec> indexes,
                     Expression check, List<Expression> previousChecks) {
        this(name, columns, indexes, check, previousChecks, List.of());
    }

    /** 在保留数据的调用方事务内扩展已知联合主键；提供者必须拒绝未知结构与受依赖约束阻止的变更。
     * @param columns 已知旧主键列，按键顺序排列
     * @return 携带升级声明的新表定义
     */
    public TableSpec withPreviousPrimaryKey(List<String> columns) {
        return new TableSpec(name, this.columns, indexes, check, previousChecks, columns);
    }

    /**
     * 创建当前中性约束声明。
     * @param name 表名
     * @param columns 列集合
     * @param indexes 索引集合
     * @param check 当前中性约束，没有约束时为 null
     */
    public TableSpec(String name, List<ColumnSpec> columns, List<IndexSpec> indexes, Expression check) {
        this(name, columns, indexes, check, List.of(), List.of());
    }

    /**
     * 返回携带中性约束的新声明。
     * @param expression 当前表的中性行约束
     * @return 新声明
     */
    public TableSpec withCheck(Expression expression) {
        return new TableSpec(name, columns, indexes, java.util.Objects.requireNonNull(expression), previousChecks, previousPrimaryKey);
    }

    /**
     * 声明可升级的旧 CHECK；提供者须核验源结构和全部数据，在调用方事务内保留数据完成升级。
     * 未匹配的约束、未声明的无约束表及不合规数据必须拒绝，不自动清洗或丢弃记录。
     * @param expressions 已知旧中性约束
     * @return 新声明
     */
    public TableSpec withPreviousChecks(List<Expression> expressions) {
        return new TableSpec(name, columns, indexes, check, java.util.Objects.requireNonNull(expressions), previousPrimaryKey);
    }

    private static String checkType(String table, List<ColumnSpec> columns, Expression expression) {
        if (expression instanceof Expression.Column column) {
            if (!column.table().equals(table)) throw invalidCheck();
            return columns.stream().filter(c -> c.name().equals(column.name())).findFirst()
                    .orElseThrow(TableSpec::invalidCheck).type();
        }
        if (expression instanceof Expression.Value value) {
            Object v = value.value();
            if (v == null) return "NULL";
            if (v instanceof String s && s.indexOf('\0') < 0) {
                top.sywyar.pixivdownload.plugin.api.database.migration.MigrationValues.canonical(s, "TEXT");
                return "TEXT";
            }
            if (v instanceof Long) return "INTEGER";
            if (v instanceof Double) return "REAL";
            throw invalidCheck();
        }
        if (!(expression instanceof Expression.Call call)) throw invalidCheck();
        var types = call.arguments().stream().map(e -> checkType(table, columns, e)).toList();
        return switch (call.operator()) {
            case AND, OR, NOT -> {
                if (types.stream().anyMatch(t -> !t.equals("BOOLEAN"))) throw invalidCheck();
                yield "BOOLEAN";
            }
            case IS_NULL -> "BOOLEAN";
            case LENGTH, TRIM, LOWER_HEX -> {
                if (!List.of("TEXT", "NULL").contains(types.get(0))) throw invalidCheck();
                yield call.operator() == Expression.Operator.LENGTH ? "INTEGER"
                        : call.operator() == Expression.Operator.TRIM ? "TEXT" : "BOOLEAN";
            }
            case EQUAL, NOT_EQUAL, LESS, LESS_EQUAL, GREATER, GREATER_EQUAL, IN -> {
                var concrete = types.stream().filter(t -> !t.equals("NULL"))
                        .map(t -> t.equals("REAL") ? "INTEGER" : t).distinct().toList();
                if (concrete.size() > 1 || concrete.contains("BLOB") || concrete.contains("BOOLEAN")) throw invalidCheck();
                yield "BOOLEAN";
            }
            default -> throw invalidCheck();
        };
    }

    private static IllegalArgumentException invalidCheck() {
        return new IllegalArgumentException("STORAGE_CHECK_INVALID");
    }

    /**
     * 创建 {@code TableSpec} 实例。
     *
     * @param name 名称
     * @param columns 列集合
     * @param indexes 索引列表
     */
    public TableSpec(String name, List<ColumnSpec> columns, List<IndexSpec> indexes) {
        this(name, columns, indexes, null);
    }
}
