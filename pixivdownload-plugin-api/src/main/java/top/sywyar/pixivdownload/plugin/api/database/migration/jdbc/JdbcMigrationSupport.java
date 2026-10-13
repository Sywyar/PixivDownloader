package top.sywyar.pixivdownload.plugin.api.database.migration.jdbc;

import top.sywyar.pixivdownload.plugin.api.database.migration.*;
import top.sywyar.pixivdownload.plugin.api.schema.*;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;

/** 两个 JDBC provider 共用的参数化流与声明校验；不探测产品、不执行方言 DDL 或事务提交。 */
public final class JdbcMigrationSupport {
    private JdbcMigrationSupport() {}
    /**
     * 引用已校验的中性标识符。
     * @param name 规范标识符
     * @return 双引号引用名
     */
    public static String quote(String name) { return '"' + MigrationSchema.identifier(name) + '"'; }
    /**
     * 执行由提供者生成的单条 SQL 并关闭语句。
     * @param connection 当前事务连接
     * @param sql 可信提供者生成的 SQL
     * @throws SQLException 执行失败
     */
    public static void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) { statement.execute(sql); }
    }
    /**
     * 生成仅用于结构比较的物理声明。
     * @param schema 中性结构
     * @return 规范物理模型
     */
    public static PhysicalDatabaseSchema.DatabaseSchema physical(MigrationSchema schema) {
        Map<String, PhysicalDatabaseSchema.TableSpec> tables = new LinkedHashMap<>();
        for (var table : schema.tables()) tables.put(table.name(), new PhysicalDatabaseSchema.TableSpec(table.name(),
                table.columns().stream().map(c -> new PhysicalDatabaseSchema.ColumnSpec(c.name(), c.type(),
                        c.notNull(), c.defaultValue(), c.primaryKeyPosition())).toList(),
                table.indexes().stream().map(i -> new PhysicalDatabaseSchema.IndexSpec(i.name(),
                        PhysicalDatabaseSchema.IndexOrigin.valueOf(i.origin().name()), i.unique(), i.columns())).toList()));
        return new PhysicalDatabaseSchema.DatabaseSchema(tables);
    }
    /**
     * 核对所有表、列、默认值和索引，不接受多余对象。
     * @param expected 声明结构
     * @param actual 提供者回读结构
     */
    public static void requireSchema(PhysicalDatabaseSchema.DatabaseSchema expected,
                                     Map<String, PhysicalDatabaseSchema.TableSpec> actual) {
        if (!expected.tables().keySet().equals(actual.keySet())) throw failure("SCHEMA_MISMATCH");
        for (var table : expected.tables().values()) {
            var found = actual.get(table.name());
            Map<String, PhysicalDatabaseSchema.ColumnSpec> columns = new TreeMap<>(), actualColumns = new TreeMap<>();
            table.columns().forEach(c -> columns.put(c.name(), c));
            found.columns().forEach(c -> actualColumns.put(c.name(), c));
            var indexes = table.indexes().stream().map(PhysicalDatabaseSchema.IndexSpec::signature).sorted().toList();
            var actualIndexes = found.indexes().stream().map(PhysicalDatabaseSchema.IndexSpec::signature).sorted().toList();
            if (!columns.equals(actualColumns) || !indexes.equals(actualIndexes)) throw failure("SCHEMA_MISMATCH");
        }
    }
    /**
     * 将中性表声明作为单份结构贡献。
     * @param schema 中性结构
     * @return 结构贡献
     */
    public static List<SchemaContribution> contributions(MigrationSchema schema) {
        return List.of(new SchemaContribution(schema.tables(), List.of(), List.of()));
    }
    /**
     * 生成普通索引声明，方言扩展由提供者处理。
     * @param table 表名
     * @param index 索引声明
     * @return 建索引语句
     */
    public static String createIndex(String table, IndexSpec index) {
        return "CREATE " + (index.unique() ? "UNIQUE " : "") + "INDEX IF NOT EXISTS " + quote(index.name())
                + " ON " + quote(table) + " (" + String.join(",", index.columns().stream().map(JdbcMigrationSupport::quote).toList()) + ")";
    }
    /**
     * 打开按主键排序的单行游标，调用方必须关闭。
     * @param connection 固定快照连接
     * @param table 声明表
     * @param orderKey 提供者生成的逻辑主键排序表达式
     * @return 中性行游标
     * @throws SQLException 查询失败
     */
    public static MigrationRows rows(Connection connection, TableSpec table,
            java.util.function.Function<ColumnSpec, String> orderKey) throws SQLException {
        String sql = "SELECT " + String.join(",", table.columns().stream().map(c -> quote(c.name())).toList())
                + " FROM " + quote(table.name()) + " ORDER BY "
                + String.join(",", table.columns().stream().filter(c -> c.primaryKeyPosition() > 0)
                        .sorted(Comparator.comparingInt(ColumnSpec::primaryKeyPosition))
                        .map(c -> orderKey.apply(c) + " ASC NULLS FIRST").toList());
        var statement = connection.prepareStatement(sql);
        try {
            statement.setFetchSize(1);
            var result = statement.executeQuery();
            return new MigrationRows() {
                private List<Object> values;
                @Override public boolean next() throws SQLException {
                    if (!result.next()) { values = null; return false; }
                    var row = new ArrayList<Object>(table.columns().size());
                    for (int i = 0; i < table.columns().size(); i++)
                        row.add(MigrationValues.canonical(result.getObject(i + 1), table.columns().get(i).type()));
                    values = Collections.unmodifiableList(row);
                    return true;
                }
                @Override public List<Object> values() { return Objects.requireNonNull(values, "MIGRATION_CURSOR_STATE"); }
                @Override public void close() throws SQLException {
                    try { result.close(); } finally { statement.close(); }
                }
            };
        } catch (SQLException | RuntimeException failure) {
            try { statement.close(); } catch (SQLException close) { failure.addSuppressed(close); }
            throw failure;
        }
    }
    /**
     * 流式计算全部声明表的摘要。
     * @param source 提供者按中性主键顺序打开的游标
     * @param schema 完整声明
     * @param control 取消及进度控制器
     * @return 逐表行数和摘要
     * @throws Exception 读取或取消失败
     */
    public static Map<String, MigrationVerification.TableDigest> digests(MigrationSchema schema,
            MigrationControl control, RowSource source) throws Exception {
        Map<String, MigrationVerification.TableDigest> result = new LinkedHashMap<>();
        for (var table : schema.tables()) try (var rows = source.rows(table)) {
            result.put(table.name(), MigrationValues.digest(rows, control, table.name()));
        }
        return result;
    }
    /** 提供者拥有行序与游标资源；摘要算法只消费中性行。 */
    @FunctionalInterface
    public interface RowSource {
        /**
         * 打开指定表的有序游标。
         * @param table 声明表
         * @return 调用方必须关闭的游标
         * @throws Exception 读取准备失败
         */
        MigrationRows rows(TableSpec table) throws Exception;
    }
    /**
     * 只插入规范化副本中缺失的主键。
     * @param connection 工作副本事务连接
     * @param schema 含初始数据的结构
     * @throws SQLException 校验或插入失败
     */
    public static void seed(Connection connection, MigrationSchema schema) throws SQLException {
        for (var seed : schema.seeds()) {
            var table = schema.table(seed.table());
            var keys = MigrationSchema.primaryKey(table);
            String condition = String.join(" AND ", keys.stream().map(k -> quote(k) + "=?").toList());
            try (var check = connection.prepareStatement("SELECT 1 FROM " + quote(table.name()) + " WHERE " + condition)) {
                for (int i = 0; i < keys.size(); i++) check.setObject(i + 1, seed.values().get(keys.get(i)));
                try (var found = check.executeQuery()) { if (found.next()) continue; }
            }
            var names = seed.values().keySet().stream().sorted().toList();
            try (var write = connection.prepareStatement("INSERT INTO " + quote(table.name()) + " ("
                    + String.join(",", names.stream().map(JdbcMigrationSupport::quote).toList()) + ") VALUES ("
                    + String.join(",", Collections.nCopies(names.size(), "?")) + ")")) {
                for (int i = 0; i < names.size(); i++) write.setObject(i + 1, seed.values().get(names.get(i)));
                write.executeUpdate();
            }
        }
    }
    /**
     * 统计引用缺失，不删除源行或添加约束。
     * @param connection 固定快照连接
     * @param schema 含引用的结构
     * @param control 取消及进度控制器
     * @return 仅含非零缺失的警告映射
     * @throws Exception 读取或取消失败
     */
    public static Map<String, Long> references(Connection connection, MigrationSchema schema,
                                               MigrationControl control) throws Exception {
        Map<String, Long> result = new TreeMap<>();
        for (var reference : schema.references()) {
            long missing = 0;
            if (reference.encoding() == MigrationSchema.Encoding.VALUE) {
                try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                        "SELECT COUNT(*) FROM " + quote(reference.table()) + " c LEFT JOIN " + quote(reference.targetTable())
                                + " p ON c." + quote(reference.column()) + "=p." + quote(reference.targetColumn())
                                + " WHERE c." + quote(reference.column()) + " IS NOT NULL AND p."
                                + quote(reference.targetColumn()) + " IS NULL")) {
                    if (!rows.next()) throw failure("REFERENCE_QUERY_FAILED");
                    missing = rows.getLong(1);
                }
                control.checkpoint(reference.table(), 0);
            } else {
                try (var read = connection.prepareStatement("SELECT " + quote(reference.column()) + " FROM " + quote(reference.table()));
                     var find = connection.prepareStatement("SELECT 1 FROM " + quote(reference.targetTable()) + " WHERE "
                             + quote(reference.targetColumn()) + "=?")) {
                    read.setFetchSize(1);
                    try (var rows = read.executeQuery()) {
                        long count = 0;
                        while (rows.next()) {
                            control.checkpoint(reference.table(), count++);
                            String path = rows.getString(1);
                            if (path == null || !path.startsWith("{")) continue;
                            int end = path.indexOf('}');
                            long id;
                            try { id = end > 1 ? Long.parseLong(path.substring(1, end)) : -1; }
                            catch (NumberFormatException invalid) { id = -1; }
                            if (id == 0) continue;
                            if (id < 0) { missing++; continue; }
                            find.setLong(1, id);
                            try (var found = find.executeQuery()) { if (!found.next()) missing++; }
                        }
                    }
                }
            }
            if (missing > 0) result.put(reference.key(), missing);
        }
        return result;
    }
    /**
     * 读取生成键最大值，空表或全负值返回零。
     * @param connection 固定快照连接
     * @param table 表名
     * @param column 生成键列名
     * @return 非负高水位
     * @throws SQLException 读取失败
     */
    public static long maximum(Connection connection, String table, String column) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                "SELECT COALESCE(MAX(" + quote(column) + "),0) FROM " + quote(table))) {
            if (!rows.next()) throw failure("SEQUENCE_QUERY_FAILED");
            return Math.max(0, rows.getLong(1));
        }
    }
    /**
     * 确定格式支持的单列整数生成键。
     * @param table 声明表
     * @return 生成键列或明确缺席
     */
    public static Optional<ColumnSpec> generatedKey(TableSpec table) {
        return table.columns().stream().filter(c -> c.primaryKeyPosition() == 1 && c.type().equals("INTEGER")
                && c.defaultValue() == null && MigrationSchema.primaryKey(table).size() == 1).findFirst();
    }
    /**
     * 构造仅含机器码的合同异常。
     * @param code 不含数据或秘密的机器码
     * @return 受控异常
     */
    public static IllegalStateException failure(String code) { return new IllegalStateException(code); }
}
