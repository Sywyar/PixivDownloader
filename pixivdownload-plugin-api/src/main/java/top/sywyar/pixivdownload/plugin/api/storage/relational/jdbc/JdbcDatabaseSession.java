package top.sywyar.pixivdownload.plugin.api.storage.relational.jdbc;

import top.sywyar.pixivdownload.plugin.api.database.migration.*;
import top.sywyar.pixivdownload.plugin.api.schema.*;
import top.sywyar.pixivdownload.plugin.api.storage.relational.*;
import java.sql.*;
import java.util.*;
import static top.sywyar.pixivdownload.plugin.api.database.migration.jdbc.JdbcMigrationSupport.quote;

/**
 * 提供者可复用的 JDBC 实现支持；宿主不在此选择方言。
 * 此类校验操作和绑定参数，具体差异由提供者实现。
 */
public abstract class JdbcDatabaseSession implements DatabaseSession {
    /** 由事务所有者管理的连接；会话不得自行提交或关闭。 */
    protected final Connection connection;
    /** 当前作用域可访问的固定结构声明。 */
    protected final MigrationSchema schema;
    private final Thread owner = Thread.currentThread();
    private boolean active = true;
    private boolean failed;
    private final List<Object> parameters = new ArrayList<>();

    /**
     * 绑定连接和声明，不接管连接生命周期。
     * @param connection 调用方持有并管理事务的连接
     * @param schema 当前存储作用域的结构声明
     */
    protected JdbcDatabaseSession(Connection connection, MigrationSchema schema) {
        this.connection = Objects.requireNonNull(connection);
        this.schema = Objects.requireNonNull(schema);
    }

    /**
     * 驱动错误可能带有失败行或参数；只保留供宿主分类的 SQLState 与厂商错误码。
     * @param failure 待脱敏的驱动异常
     * @return 只保留稳定机器码、SQLState 和厂商错误码的异常
     */
    protected SQLException storageFailure(SQLException failure) {
        String state = failure.getSQLState();
        return new SQLException("STORAGE_OPERATION_FAILED", state != null && state.matches("[0-9A-Z]{5}") ? state : "HY000",
                failure.getErrorCode());
    }

    /**
     * 生成仅折叠 ASCII 大小写的表达式。
     * @param expression 已编译的 SQL 表达式
     * @return 由提供者生成的 SQL，保留原有参数占位符顺序
     */
    protected abstract String lowerAscii(String expression);
    /**
     * 可选派生能力的缺省实现明确拒绝；提供者不能假装成功。
     * @param cleanup 同语句关联清理规则
     * @throws java.sql.SQLException 数据库不可用、能力不支持或执行失败；当前事务不得继续提交
     */
    protected void createCleanup(StorageFeatures.Cleanup cleanup) throws SQLException { throw unsupportedFeature(); }
    /**
     * 初始化正文索引；缺省以 0A000 拒绝。
     * @param index 正文索引声明
     * @return 实际采用的索引策略
     * @throws java.sql.SQLException 数据库不可用、能力不支持或执行失败；当前事务不得继续提交
     */
    protected StorageFeatures.TextIndexMode createTextIndex(StorageFeatures.TextIndex index) throws SQLException { throw unsupportedFeature(); }
    /**
     * 刷新正文索引；缺省以 0A000 拒绝。
     * @param index 正文索引声明
     * @param key 待同步的主键；null 表示回收不可见行并回填缺失索引
     * @return 实际采用的索引策略
     * @throws java.sql.SQLException 数据库不可用、能力不支持或执行失败；当前事务不得继续提交
     */
    protected StorageFeatures.TextIndexMode refreshIndex(StorageFeatures.TextIndex index, Long key) throws SQLException { throw unsupportedFeature(); }
    /**
     * 执行字面子串检索；缺省以 0A000 拒绝。
     * @param index 正文索引声明
     * @param term 按字面解释的搜索文本
     * @return 匹配的主记录身份
     * @throws java.sql.SQLException 数据库不可用、能力不支持或执行失败；当前事务不得继续提交
     */
    protected List<Long> searchIndex(StorageFeatures.TextIndex index, String term) throws SQLException { throw unsupportedFeature(); }
    private static SQLException unsupportedFeature() { return new SQLException("STORAGE_FEATURE_UNSUPPORTED", "0A000"); }

    private void validateIndex(StorageFeatures.TextIndex index) {
        var table = schema.table(index.table());
        if (!MigrationSchema.primaryKey(table).equals(List.of(index.key()))
                || !column(index.table(), index.key()).type().equals("INTEGER")
                || !column(index.table(), index.content()).type().equals("TEXT")
                || !column(index.table(), index.activeColumn()).type().equals("INTEGER")
                || schema.tables().stream().anyMatch(t -> t.name().equals(index.name()))) throw invalid();
    }
    private <T> T feature(java.util.concurrent.Callable<T> work) throws SQLException {
        begin();
        try { return work.call(); }
        catch (SQLException failure) { failed = true; throw storageFailure(failure); }
        catch (RuntimeException | Error failure) { failed = true; throw failure; }
        catch (Exception failure) { failed = true; throw new SQLException("STORAGE_OPERATION_FAILED", "HY000"); }
    }
    @Override public final void initializeCleanup(StorageFeatures.Cleanup cleanup) throws SQLException {
        feature(() -> {
            var key = column(cleanup.table(), cleanup.key());
            if (!column(cleanup.table(), cleanup.changedColumn()).type().equals("INTEGER")
                    || !MigrationSchema.primaryKey(schema.table(cleanup.table())).equals(List.of(cleanup.key()))) throw invalid();
            for (var dependent : cleanup.dependents())
                if (!column(dependent.table(), dependent.key()).type().equals(key.type()) || dependent.table().equals(cleanup.table())) throw invalid();
            createCleanup(cleanup); return null;
        });
    }
    @Override public final StorageFeatures.TextIndexMode initializeTextIndex(StorageFeatures.TextIndex index) throws SQLException {
        return feature(() -> { validateIndex(index); return createTextIndex(index); });
    }
    @Override public final StorageFeatures.TextIndexMode refreshTextIndex(StorageFeatures.TextIndex index, Long key) throws SQLException {
        return feature(() -> { validateIndex(index); return refreshIndex(index, key); });
    }
    @Override public final List<Long> searchText(StorageFeatures.TextIndex index, String term) throws SQLException {
        return feature(() -> { validateIndex(index); Objects.requireNonNull(term); return List.copyOf(searchIndex(index, term)); });
    }
    /**
     * 生成两个标量的最小值表达式，任一为空时结果为空。
     * @param left 左操作数 SQL 表达式
     * @param right 右操作数 SQL 表达式
     * @return 由提供者生成的 SQL，保留原有参数占位符顺序
     */
    protected abstract String minimum(String left, String right);
    /**
     * 生成位置从一开始的子串表达式。
     * @param length 截取长度 SQL 表达式
     * @param start 从一开始的起始位置 SQL 表达式
     * @param text 原文本 SQL 表达式
     * @return 由提供者生成的 SQL，保留原有参数占位符顺序
     */
    protected abstract String substring(String text, String start, String length);
    /**
     * 生成提供者的唯一键冲突子句。
     * @param conflict 唯一键冲突处理规则
     * @param table 当前作用域中已声明的表或引用别名
     * @param updates 已校验并引用列名的冲突更新赋值 SQL
     * @return 由提供者生成的 SQL，保留原有参数占位符顺序
     */
    protected abstract String conflictClause(String table, Conflict conflict, String updates);
    /**
     * 显式写入自增身份时，提供者负责锁定并推进生成器，不能覆盖历史高水位。
     * @param columns 参与语句的列集合
     * @param table 当前作用域中已声明的表或引用别名
     * @throws java.sql.SQLException 数据库不可用、能力不支持或执行失败；当前事务不得继续提交
     */
    protected abstract void beforeInsert(String table, Set<String> columns) throws SQLException;
    /**
     * 写入后同步身份生成器，保留已经分配的高水位。
     * @param columns 参与语句的列集合
     * @param table 当前作用域中已声明的表或引用别名
     * @throws java.sql.SQLException 数据库不可用、能力不支持或执行失败；当前事务不得继续提交
     */
    protected abstract void afterInsert(String table, Set<String> columns) throws SQLException;
    /**
     * 提供者可覆盖完整写入语句以实现 MERGE 等语法。
     * @param columns 已经引用的 SQL 列名，按写入或返回顺序排列
     * @param conflict 唯一键冲突处理规则
     * @param table 当前作用域中已声明的表或引用别名
     * @param updates 已校验并引用列名的冲突更新赋值 SQL
     * @return 由提供者生成的 SQL，保留原有参数占位符顺序
     */
    protected String insertStatement(String table, List<String> columns, Conflict conflict, String updates) {
        return "INSERT INTO " + quote(table) + " (" + String.join(",", columns) + ") VALUES ("
                + String.join(",", Collections.nCopies(columns.size(), "?")) + ")"
                + conflictClause(table, conflict, updates);
    }

    /** 事务所有者在所有退出路径关闭会话，包括回调失败。 */
    @Override public final void close() { active = false; }
    @Override public final boolean rollbackOnly() { return failed; }

    private void begin() throws SQLException {
        if (!active || Thread.currentThread() != owner) throw new SQLException("STORAGE_SESSION_EXPIRED", "08003");
        if (failed) throw new SQLException("STORAGE_TRANSACTION_FAILED", "25000");
        parameters.clear();
    }

    @Override public final List<Map<String, Object>> query(Query query) throws SQLException {
        var rows = new ArrayList<Map<String, Object>>();
        scan(query, row -> { rows.add(row); return true; });
        return List.copyOf(rows);
    }

    @Override public final void scan(Query query, RowVisitor visitor) throws SQLException {
        begin();
        try {
            Objects.requireNonNull(visitor);
            String sql = querySql(query, new Scope(Map.of()));
            var types = projectionTypes(query, new Scope(Map.of()));
            try (var statement = prepare(sql); var result = statement.executeQuery()) {
                while (result.next()) {
                    if (!active || Thread.currentThread() != owner) throw new SQLException("STORAGE_SESSION_EXPIRED", "08003");
                    if (failed) throw new SQLException("STORAGE_TRANSACTION_FAILED", "25000");
                    var row = new LinkedHashMap<String, Object>();
                    int index = 1;
                    for (var projection : types.entrySet()) {
                        String type = projection.getValue();
                        Object value = result.getObject(index++);
                        if ("BOOLEAN".equals(type)) value = value == null ? null
                                : value instanceof Boolean b ? (b ? 1L : 0L) : ((Number) value).longValue();
                        else if (type != null) value = resultValue(value, type);
                        row.put(projection.getKey(), value);
                    }
                    if (!visitor.visit(Collections.unmodifiableMap(row))) break;
                }
            }
        } catch (SQLException failure) { failed = true; throw storageFailure(failure); }
        catch (RuntimeException failure) { failed = true; throw failure; }
        catch (Error failure) { failed = true; throw failure; }
    }

    @Override public final int insert(String table, Map<String, Object> values, Conflict conflict) throws SQLException {
        begin();
        try {
            String sql = insertSql(table, values, conflict);
            beforeInsert(table, values.keySet());
            int affected = execute(sql);
            afterInsert(table, values.keySet());
            return affected;
        } catch (SQLException failure) { failed = true; throw storageFailure(failure); }
        catch (RuntimeException failure) { failed = true; throw failure; }
    }

    /**
     * 生成可在同一语句中返回新主键的插入语句。
     * @param insert 已绑定参数占位符的插入 SQL
     * @param key 已声明的身份列
     * @return 由提供者生成的 SQL，保留原有参数占位符顺序
     */
    protected abstract String generatedInsertStatement(String insert, String key);
    /**
     * 生成按查询投影写入并应用冲突规则的语句。
     * @param columns 已经引用的 SQL 列名，按写入或返回顺序排列
     * @param conflict 唯一键冲突处理规则
     * @param select 已经编译并绑定占位符的查询 SQL
     * @param table 当前作用域中已声明的表或引用别名
     * @param updates 已校验并引用列名的冲突更新赋值 SQL
     * @return 由提供者生成的 SQL，保留原有参数占位符顺序
     */
    protected abstract String insertFromStatement(String table, List<String> columns, String select,
                                                   Conflict conflict, String updates);

    @Override public final int insertFrom(String table, Query query, Conflict conflict) throws SQLException {
        begin();
        try {
            var types = projectionTypes(query, new Scope(Map.of()));
            types.forEach((name, type) -> {
                if (!column(table, name).type().equals(type)) throw invalid();
            });
            String select = querySql(query, new Scope(Map.of()));
            String updates = conflictUpdates(table, query.select().keySet(), conflict);
            String sql = insertFromStatement(table, query.select().keySet().stream().map(
                    top.sywyar.pixivdownload.plugin.api.database.migration.jdbc.JdbcMigrationSupport::quote).toList(),
                    select, conflict, updates);
            beforeInsert(table, query.select().keySet());
            int affected = execute(sql);
            afterInsert(table, query.select().keySet());
            return affected;
        } catch (SQLException failure) { failed = true; throw storageFailure(failure); }
        catch (RuntimeException failure) { failed = true; throw failure; }
    }

    @Override public final long insertGenerated(String table, Map<String, Object> values, String key) throws SQLException {
        begin();
        try {
            var spec = schema.table(table);
            if (!MigrationSchema.primaryKey(spec).equals(List.of(key)) || values.containsKey(key)
                    || !column(table, key).autoIncrement()) throw invalid();
            String sql = generatedInsertStatement(insertSql(table, values, Conflict.error()), key);
            try (var statement = prepare(sql); var rows = statement.executeQuery()) {
                if (!rows.next()) throw new SQLException("STORAGE_GENERATED_KEY_MISSING", "23000");
                Object result = resultValue(rows.getObject(1), "INTEGER");
                if (!(result instanceof Long keyValue) || rows.next()) throw new SQLException("STORAGE_GENERATED_KEY_INVALID", "23000");
                return keyValue;
            }
        } catch (SQLException failure) { failed = true; throw storageFailure(failure); }
        catch (RuntimeException failure) { failed = true; throw failure; }
    }

    private String insertSql(String table, Map<String, Object> values, Conflict conflict) {
            schema.table(table);
            if (values.isEmpty()) throw invalid();
            var columns = new ArrayList<String>();
            for (var entry : values.entrySet()) {
                var column = column(table, entry.getKey());
                Object value = MigrationValues.canonical(entry.getValue(), column.type());
                if (value == null && column.notNull()) throw invalid();
                columns.add(quote(entry.getKey()));
                parameters.add(value);
            }
            String updates = conflictUpdates(table, values.keySet(), conflict);
            return insertStatement(table, columns, conflict, updates);
    }

    private String conflictUpdates(String table, Set<String> names, Conflict conflict) {
            var spec = schema.table(table);
            if (!conflict.keys().isEmpty()) {
                boolean key = MigrationSchema.primaryKey(spec).equals(conflict.keys())
                        || spec.indexes().stream().anyMatch(i -> i.unique() && i.columns().equals(conflict.keys()));
                if (!key) throw invalid();
            }
            if (conflict.mode() == Mode.UPDATE && !names.containsAll(conflict.keys())) throw invalid();
            var updates = new ArrayList<String>();
            for (var entry : conflict.changes().entrySet()) {
                requireType(column(table, entry.getKey()).type(), entry.getValue(), table);
                updates.add(quote(entry.getKey()) + "=" + expression(entry.getValue(), tableScope(table), table));
            }
            return String.join(",", updates);
    }

    private String updateSql(String table, Map<String, Expression> changes, Expression where) {
        schema.table(table);
        if (changes.isEmpty() || where == null) throw invalid();
        var updates = new ArrayList<String>();
        for (var entry : changes.entrySet()) {
            requireType(column(table, entry.getKey()).type(), entry.getValue(), table);
            updates.add(quote(entry.getKey()) + "=" + expression(entry.getValue(), tableScope(table), null));
        }
        return "UPDATE " + quote(table) + " SET " + String.join(",", updates)
                + " WHERE " + predicate(where, tableScope(table), false);
    }

    @Override public final int update(String table, Map<String, Expression> changes, Expression where) throws SQLException {
        begin();
        try { return execute(updateSql(table, changes, where)); }
        catch (SQLException failure) { failed = true; throw storageFailure(failure); }
        catch (RuntimeException failure) { failed = true; throw failure; }
    }

    /**
     * 生成原子更新并返回修改后列的语句。
     * @param columns 已经引用的 SQL 列名，按写入或返回顺序排列
     * @param update 带有条件和绑定参数占位符的更新 SQL
     * @return 由提供者生成的 SQL，保留原有参数占位符顺序
     */
    protected abstract String updateReturningStatement(String update, List<String> columns);
    @Override public final List<Map<String, Object>> updateReturning(String table, Map<String, Expression> changes,
                                                                     Expression where, List<String> returning) throws SQLException {
        begin();
        try {
            if (returning.isEmpty() || new HashSet<>(returning).size() != returning.size()) throw invalid();
            returning.forEach(name -> column(table, name));
            String sql = updateReturningStatement(updateSql(table, changes, where), returning.stream().map(
                    top.sywyar.pixivdownload.plugin.api.database.migration.jdbc.JdbcMigrationSupport::quote).toList());
            try (var statement = prepare(sql); var result = statement.executeQuery()) {
                var rows = new ArrayList<Map<String, Object>>();
                while (result.next()) {
                    var row = new LinkedHashMap<String, Object>();
                    for (int i = 0; i < returning.size(); i++) row.put(returning.get(i),
                            resultValue(result.getObject(i + 1), column(table, returning.get(i)).type()));
                    rows.add(Collections.unmodifiableMap(row));
                }
                return List.copyOf(rows);
            }
        } catch (SQLException failure) { failed = true; throw storageFailure(failure); }
        catch (RuntimeException failure) { failed = true; throw failure; }
    }

    @Override public final int delete(String table, Expression where) throws SQLException {
        begin();
        try {
            schema.table(table);
            if (where == null) throw invalid();
            return execute("DELETE FROM " + quote(table) + " WHERE " + predicate(where, tableScope(table), false));
        } catch (SQLException failure) { failed = true; throw storageFailure(failure); }
        catch (RuntimeException failure) { failed = true; throw failure; }
    }

    private int execute(String sql) throws SQLException {
        try (var statement = prepare(sql)) { return statement.executeUpdate(); }
    }
    private static Object resultValue(Object value, String type) throws SQLException {
        // JDBC 聚合可能提升为十进制；只接受能够精确回到声明类型的结果。
        try {
            if ("INTEGER".equals(type) && value instanceof java.math.BigDecimal number) value = number.longValueExact();
            if ("INTEGER".equals(type) && value instanceof java.math.BigInteger number) value = number.longValueExact();
            return MigrationValues.canonical(value, type);
        } catch (ArithmeticException failure) {
            throw new SQLException("STORAGE_INTEGER_OVERFLOW", "22003");
        }
    }
    private PreparedStatement prepare(String sql) throws SQLException {
        var statement = connection.prepareStatement(sql);
        try {
            for (int i = 0; i < parameters.size(); i++) {
                Object value = StorageValues.normalize(parameters.get(i));
                if (value instanceof byte[] bytes) statement.setBytes(i + 1, bytes);
                else statement.setObject(i + 1, value);
            }
            return statement;
        } catch (SQLException failure) { statement.close(); throw failure; }
    }
    private record Scope(Map<String, Map<String, String>> tables) {
        Scope with(String alias, Map<String, String> columns) {
            var copy = new LinkedHashMap<>(tables);
            if (copy.putIfAbsent(alias, columns) != null) throw invalid();
            return new Scope(copy);
        }
        String column(String table, String name) {
            var columns = tables.get(table);
            if (columns == null || !columns.containsKey(name)) throw invalid();
            return columns.get(name);
        }
    }
    private Scope tableScope(String table) {
        return sourceScope(new Query.Table(table), new Scope(Map.of()));
    }
    private Scope sourceScope(Query.Source source, Scope outer) {
        Map<String, String> columns = new LinkedHashMap<>();
        if (source instanceof Query.Table table) {
            schema.table(table.name()).columns().forEach(c -> columns.put(c.name(), c.type()));
        } else if (source instanceof Query.Derived derived) {
            columns.putAll(projectionTypes(derived.query(), new Scope(Map.of())));
        } else {
            var union = (Query.Union) source;
            columns.putAll(projectionTypes(union.queries().get(0), new Scope(Map.of())));
            for (var branch : union.queries()) {
                var other = projectionTypes(branch, new Scope(Map.of()));
                if (!new ArrayList<>(columns.keySet()).equals(new ArrayList<>(other.keySet())) || !columns.equals(other)) throw invalid();
            }
        }
        return outer.with(source.alias(), Collections.unmodifiableMap(columns));
    }
    private Scope queryScope(Query query, Scope outer) {
        Scope scope = sourceScope(query.from(), outer);
        for (var join : query.joins()) scope = sourceScope(join.source(), scope);
        return scope;
    }
    private Map<String, String> projectionTypes(Query query, Scope outer) {
        var scope = queryScope(query, outer);
        var types = new LinkedHashMap<String, String>();
        query.select().forEach((name, expression) -> types.put(name, type(expression, scope, null)));
        return types;
    }
    private String sourceSql(Query.Source source) {
        if (source instanceof Query.Table table) return quote(table.name()) + " AS " + quote(table.alias());
        if (source instanceof Query.Derived derived)
            return "(" + querySql(derived.query(), new Scope(Map.of())) + ") AS " + quote(source.alias());
        var union = (Query.Union) source;
        var branches = new ArrayList<String>();
        for (int i = 0; i < union.queries().size(); i++) {
            branches.add("SELECT * FROM (" + querySql(union.queries().get(i), new Scope(Map.of()))
                    + ") AS " + quote("branch_" + i));
        }
        return "(" + String.join(union.all() ? " UNION ALL " : " UNION ", branches) + ") AS " + quote(union.alias());
    }
    private String querySql(Query query, Scope outer) {
        validateGrouping(query);
        var scope = queryScope(query, outer);
        var select = new ArrayList<String>();
        query.select().forEach((name, value) -> select.add(expression(value, scope, null) + " AS " + quote(name)));
        var sql = new StringBuilder(query.distinct() ? "SELECT DISTINCT " : "SELECT ")
                .append(String.join(",", select)).append(" FROM ").append(sourceSql(query.from()));
        Scope joined = sourceScope(query.from(), outer);
        for (var join : query.joins()) {
            joined = sourceScope(join.source(), joined);
            sql.append(join.kind() == Query.JoinKind.LEFT ? " LEFT JOIN " : " INNER JOIN ")
                    .append(sourceSql(join.source())).append(" ON ").append(predicate(join.on(), joined, false));
        }
        if (query.where() != null) sql.append(" WHERE ").append(predicate(query.where(), scope, false));
        if (!query.groupBy().isEmpty()) sql.append(" GROUP BY ").append(String.join(",",
                query.groupBy().stream().map(e -> expression(e, scope, null)).toList()));
        if (query.having() != null) sql.append(" HAVING ").append(predicate(query.having(), scope, true));
        if (!query.orderBy().isEmpty()) sql.append(" ORDER BY ").append(String.join(",", query.orderBy().stream()
                .map(o -> expression(o.expression(), scope, null) + (o.descending() ? " DESC" : " ASC")
                        + (o.nullsFirst() ? " NULLS FIRST" : " NULLS LAST")).toList()));
        parameters.add((long) query.limit());
        parameters.add(query.offset());
        return sql.append(" LIMIT ? OFFSET ?").toString();
    }
    private String predicate(Expression e, Scope scope, boolean allowAggregate) {
        if (!allowAggregate && aggregate(e)) throw invalid();
        if (!"BOOLEAN".equals(type(e, scope, null))) throw invalid();
        return expression(e, scope, null);
    }
    /**
     * 提供者须保持整数溢出失败，不能把溢出结果静默提升为浮点数。
     * @param integral 是否必须保持 64 位整数精度
     * @param left 左操作数 SQL 表达式
     * @param operator 具有固定语义的操作
     * @param right 右操作数 SQL 表达式
     * @return 由提供者生成的 SQL，保留原有参数占位符顺序
     */
    protected abstract String arithmetic(Expression.Operator operator, String left, String right, boolean integral);
    /**
     * 生成仅含小写十六进制字符的文本谓词；未实现时在写入前明确拒绝。
     * @param value 待检查文本的 SQL 表达式
     * @return 提供者生成的布尔 SQL 表达式
     */
    protected String lowerHex(String value) { throw new IllegalArgumentException("STORAGE_OPERATION_UNSUPPORTED"); }
    /**
     * 将 epoch 毫秒按指定 IANA 时区投影为 yyyy-MM；不使用数据库服务进程的默认时区。
     * @param epochMillis 毫秒时间戳的 SQL 表达式
     * @param fixedOffset 时区参数是否为固定偏移
     * @param zone IANA 时区或固定偏移的 SQL 表达式
     * @return 由提供者生成的 SQL，保留原有参数占位符顺序
     */
    protected abstract String epochMonth(String epochMillis, String zone, boolean fixedOffset);
    private String expression(Expression e, Scope scope, String incomingTable) {
        String resultType = type(e, scope, incomingTable);
        if (e instanceof Expression.Column c) {
            return quote(c.table()) + "." + quote(c.name());
        }
        if (e instanceof Expression.Incoming c) {
            return "excluded." + quote(c.name());
        }
        if (e instanceof Expression.Value v) { parameters.add(v.value()); return "?"; }
        if (e instanceof Expression.Exists exists) return "EXISTS (" + querySql(exists.query(), scope) + ")";
        if (e instanceof Expression.Scalar scalar) return "(" + querySql(scalar.query(), scope) + ")";
        if (e instanceof Expression.Conditional conditional)
            return "(CASE WHEN " + expression(conditional.condition(), scope, incomingTable)
                    + " THEN " + expression(conditional.whenTrue(), scope, incomingTable)
                    + " ELSE " + expression(conditional.whenFalse(), scope, incomingTable) + " END)";
        var call = (Expression.Call) e;
        if (call.operator() == Expression.Operator.EPOCH_MONTH) {
            String epoch = expression(call.arguments().get(0), scope, incomingTable);
            var zone = java.time.ZoneId.of((String) ((Expression.Value) call.arguments().get(1)).value());
            boolean fixed = zone.getRules().isFixedOffset();
            String name = fixed ? zone.getRules().getOffset(java.time.Instant.EPOCH).getId() : zone.getId();
            parameters.add(name.equals("Z") ? "+00:00" : name);
            return epochMonth(epoch, "?", fixed);
        }
        var args = call.arguments().stream().map(a -> expression(a, scope, incomingTable)).toList();
        return switch (call.operator()) {
            case COUNT -> "COUNT(*)";
            case COUNT_DISTINCT -> "COUNT(DISTINCT " + args.get(0) + ")";
            case LOWER_ASCII -> lowerAscii(args.get(0));
            case LOWER_HEX -> lowerHex(args.get(0));
            case MINIMUM -> minimum(args.get(0), args.get(1));
            case TEXT -> "CAST(" + args.get(0) + " AS VARCHAR)";
            case ADD, SUBTRACT, MULTIPLY -> arithmetic(call.operator(), args.get(0), args.get(1), "INTEGER".equals(resultType));
            case CONCAT -> "(" + String.join(" || ", args) + ")";
            case SUBSTRING -> substring(args.get(0), args.get(1), args.get(2));
            case TRIM, RTRIM, LENGTH, MAX, MIN, SUM, NULL_IF, COALESCE ->
                    (call.operator() == Expression.Operator.NULL_IF ? "NULLIF" : call.operator().name())
                    + "(" + String.join(",", args) + ")";
            case IS_NULL -> "(" + args.get(0) + " IS NULL)";
            case NOT -> "(NOT " + args.get(0) + ")";
            case IN -> "(" + args.get(0) + " IN (" + String.join(",", args.subList(1, args.size())) + "))";
            case LIKE -> "(" + lowerAscii(args.get(0)) + " LIKE " + lowerAscii(args.get(1)) + " ESCAPE '!')";
            default -> "(" + String.join(switch (call.operator()) {
                case EQUAL -> " = "; case NOT_EQUAL -> " <> "; case LESS -> " < "; case LESS_EQUAL -> " <= ";
                case GREATER -> " > "; case GREATER_EQUAL -> " >= "; case AND -> " AND "; case OR -> " OR ";
                default -> throw invalid();
            }, args) + ")";
        };
    }
    private ColumnSpec column(String table, String name) {
        return schema.table(table).columns().stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow(JdbcDatabaseSession::invalid);
    }
    private void requireType(String expected, Expression value, String table) {
        if (aggregate(value)) throw invalid();
        String actual = type(value, tableScope(table), table);
        if (actual != null && !expected.equals(actual)) throw invalid();
    }
    private static boolean aggregation(Expression.Operator operator) {
        return switch (operator) { case COUNT, COUNT_DISTINCT, MAX, MIN, SUM -> true; default -> false; };
    }
    private static boolean aggregate(Expression expression) {
        if (expression instanceof Expression.Conditional c)
            return aggregate(c.condition()) || aggregate(c.whenTrue()) || aggregate(c.whenFalse());
        return expression instanceof Expression.Call call
                && (aggregation(call.operator()) || call.arguments().stream().anyMatch(JdbcDatabaseSession::aggregate));
    }
    private static void validateGrouping(Query query) {
        if (query.groupBy().stream().anyMatch(JdbcDatabaseSession::aggregate)) throw invalid();
        boolean grouped = !query.groupBy().isEmpty() || query.having() != null
                || query.select().values().stream().anyMatch(JdbcDatabaseSession::aggregate)
                || query.orderBy().stream().anyMatch(order -> aggregate(order.expression()));
        if (grouped) {
            query.select().values().forEach(e -> groupedExpression(e, query.groupBy(), false));
            query.orderBy().forEach(order -> groupedExpression(order.expression(), query.groupBy(), false));
            if (query.having() != null) groupedExpression(query.having(), query.groupBy(), false);
        }
    }
    private static void groupedExpression(Expression expression, List<Expression> groups, boolean inAggregate) {
        if (expression instanceof Expression.Value || groups.contains(expression)) return;
        if (expression instanceof Expression.Column && inAggregate) return;
        if (expression instanceof Expression.Conditional c) {
            groupedExpression(c.condition(), groups, inAggregate);
            groupedExpression(c.whenTrue(), groups, inAggregate);
            groupedExpression(c.whenFalse(), groups, inAggregate);
            return;
        }
        if (!(expression instanceof Expression.Call call)) throw invalid();
        boolean aggregation = aggregation(call.operator());
        if (inAggregate && aggregation) throw invalid();
        call.arguments().forEach(e -> groupedExpression(e, groups, inAggregate || aggregation));
    }
    private static String commonType(List<String> types) {
        var distinct = types.stream().filter(Objects::nonNull).distinct().toList();
        if (distinct.size() > 1) throw invalid();
        return distinct.isEmpty() ? null : distinct.get(0);
    }
    private String type(Expression e, Scope scope, String table) {
        if (e instanceof Expression.Column c) return scope.column(c.table(), c.name());
        if (e instanceof Expression.Incoming c) {
            if (table == null) throw invalid();
            return column(table, c.name()).type();
        }
        if (e instanceof Expression.Value v) {
            Object value = v.value();
            if (value instanceof String) { MigrationValues.canonical(value, "TEXT"); return "TEXT"; }
            return value == null ? null : value instanceof Long ? "INTEGER" : value instanceof Double ? "REAL" : "BLOB";
        }
        if (e instanceof Expression.Exists exists) { projectionTypes(exists.query(), scope); return "BOOLEAN"; }
        if (e instanceof Expression.Scalar scalar) return projectionTypes(scalar.query(), scope).values().iterator().next();
        if (e instanceof Expression.Conditional c) {
            if (!"BOOLEAN".equals(type(c.condition(), scope, table))) throw invalid();
            return commonType(Arrays.asList(type(c.whenTrue(), scope, table), type(c.whenFalse(), scope, table)));
        }
        var call = (Expression.Call) e;
        var types = call.arguments().stream().map(a -> type(a, scope, table)).toList();
        if (call.operator() == Expression.Operator.EPOCH_MONTH) {
            if (!"INTEGER".equals(types.get(0)) || !(call.arguments().get(1) instanceof Expression.Value zone)
                    || !(zone.value() instanceof String name)) throw invalid();
            java.time.ZoneId.of(name);
            return "TEXT";
        }
        if (call.operator() == Expression.Operator.SUBSTRING) {
            if (!"TEXT".equals(types.get(0)) || !"INTEGER".equals(types.get(1)) || !"INTEGER".equals(types.get(2))) throw invalid();
            return "TEXT";
        }
        String argument = commonType(types);
        return switch (call.operator()) {
            case COUNT, COUNT_DISTINCT -> "INTEGER";
            case TEXT -> {
                if (argument != null && !argument.equals("INTEGER") && !argument.equals("TEXT")) throw invalid();
                yield "TEXT";
            }
            case LOWER_ASCII, TRIM, RTRIM, CONCAT, LENGTH, LIKE, LOWER_HEX -> {
                if (argument != null && !argument.equals("TEXT")) throw invalid();
                yield (call.operator() == Expression.Operator.LIKE || call.operator() == Expression.Operator.LOWER_HEX) ? "BOOLEAN" : call.operator() == Expression.Operator.LENGTH ? "INTEGER" : "TEXT";
            }
            case AND, OR, NOT -> {
                if (!"BOOLEAN".equals(argument)) throw invalid();
                yield "BOOLEAN";
            }
            case ADD, SUBTRACT, MULTIPLY, SUM -> {
                if (argument != null && !argument.equals("INTEGER") && !argument.equals("REAL")) throw invalid();
                yield argument;
            }
            case EQUAL, NOT_EQUAL, LESS, LESS_EQUAL, GREATER, GREATER_EQUAL, IS_NULL, IN -> "BOOLEAN";
            case MAX, MIN, NULL_IF, COALESCE, MINIMUM -> argument;
            default -> throw invalid();
        };
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("STORAGE_OPERATION_INVALID"); }
}
