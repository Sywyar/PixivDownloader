package top.sywyar.pixivdownload.plugin.storage;

import top.sywyar.pixivdownload.plugin.api.database.*;
import top.sywyar.pixivdownload.plugin.api.database.migration.*;
import top.sywyar.pixivdownload.plugin.api.database.migration.jdbc.*;
import top.sywyar.pixivdownload.plugin.api.schema.*;
import top.sywyar.pixivdownload.plugin.api.storage.relational.*;
import top.sywyar.pixivdownload.plugin.api.storage.relational.jdbc.JdbcDatabaseSession;
import java.sql.*;
import java.nio.file.*;
import java.util.*;
import static top.sywyar.pixivdownload.plugin.api.database.migration.jdbc.JdbcMigrationSupport.*;

/** 独立 H2 合同夹具：使用原生 MERGE，不委托 SQLite / PostgreSQL；事务性 DDL 明确不支持。 */
public final class H2ContractProvider implements DatabaseProvider, top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin {
    @Override public String id() { return "contract-provider"; }
    @Override public String displayName() { return "fixture.name"; }
    @Override public String description() { return "fixture.summary"; }
    @Override public top.sywyar.pixivdownload.plugin.api.plugin.PluginKind kind() {
        return top.sywyar.pixivdownload.plugin.api.plugin.PluginKind.FEATURE;
    }
    private final Map<String, Target> targets = new HashMap<>();
    @Override public String databaseId() { return "contract-h2"; }
    @Override public List<DatabaseConnectionField> connectionFields() {
        return List.of(new DatabaseConnectionField("database.jdbc-url", top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldType.STRING));
    }
    @Override public void prepareStorage(DatabaseConnectionSettings settings) { resolve(settings); }
    @Override public DatabaseConnectionSettings localFileSettings(DatabaseConnectionSettings settings, Path file) throws SQLException {
        throw new SQLException("DATABASE_LOCAL_FILE_UNSUPPORTED", "0A000");
    }
    @Override public DatabaseConnectionSettings normalizeConfiguration(DatabaseConnectionSettings settings, boolean selectionChanged) { return settings; }
    @Override public DatabaseConnectionTarget resolve(DatabaseConnectionSettings settings) {
        if (!settings.jdbcUrl().matches("contract:[a-z0-9_]+")) throw new IllegalArgumentException("FIXTURE_ADDRESS");
        return targets.computeIfAbsent(settings.jdbcUrl(), Target::new);
    }
    @Override public Map<String, DatabaseConnectionTarget> retainedLocalPrivateDatabases(Path data, String installation) { return Map.of(); }
    @Override public Set<String> privateDatabaseOwners(DatabaseConnectionTarget main, Path data, String installation) {
        return Set.copyOf(((Target) main).owners.keySet());
    }
    @Override public DatabaseExportSession exportDatabase(DatabaseConnectionTarget source, MigrationRequest request,
            MigrationControl control) throws Exception {
        var target = (Target) source;
        var connection = target.openConnection(DatabaseConnectionTarget.OpenMode.EXISTING);
        try {
            target.validate(connection, request.schema());
            try (var session = target.storageSession(connection, request.schema())) {
                StorageMigration.requireApplied(session, request.storageMigrations());
            }
            if (!request.rebuildObjects().isEmpty()) throw new IllegalArgumentException("FIXTURE_REBUILD_UNSUPPORTED");
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            String revision = revision(target, request.schema());
            var verification = verification(connection, request.schema(), control);
            MigrationFiles.createPrivateFile(request.backup());
            try (var writer = Files.newBufferedWriter(request.backup(), java.nio.charset.StandardCharsets.UTF_8);
                 var statement = connection.createStatement(); var script = statement.executeQuery("SCRIPT")) {
                while (script.next()) { writer.write(script.getString(1)); writer.newLine(); }
            }
            try (var file = java.nio.channels.FileChannel.open(request.backup(), StandardOpenOption.WRITE)) { file.force(true); }
            var snapshot = new MigrationSnapshot(1, request.schema(), verification, request.backup(), revision, List.of());
            return new DatabaseExportSession() {
                @Override public MigrationSnapshot snapshot() { return snapshot; }
                @Override public MigrationRows rows(String table) throws Exception { return H2ContractProvider.rows(connection, request.schema().table(table)); }
                @Override public void close() throws Exception { try { connection.rollback(); } finally { connection.close(); } }
            };
        } catch (Exception failure) { connection.close(); throw failure; }
    }
    @Override public DatabaseImportSession importDatabase(DatabaseConnectionTarget destination, MigrationSchema schema,
            MigrationControl control) throws Exception {
        var target = (Target) destination;
        var connection = target.openConnection(DatabaseConnectionTarget.OpenMode.READ_WRITE);
        try {
            try (var tables = connection.getMetaData().getTables(null, "PUBLIC", null, new String[]{"TABLE"})) {
                if (tables.next()) throw new IllegalStateException("TARGET_NOT_EMPTY");
            }
            target.initializeStorage(connection, schema);
            connection.setAutoCommit(false);
            var jdbc = new JdbcImportSession(connection, schema, control) {
                @Override protected MigrationRows rows(TableSpec table) throws Exception { return H2ContractProvider.rows(connection, table); }
                @Override protected void validateSchema() throws Exception { target.validate(connection, schema); }
                @Override protected Map<String, Long> restoreSequences(Map<String, Long> expected) {
                    if (!expected.isEmpty()) throw new IllegalArgumentException("FIXTURE_SEQUENCE_UNSUPPORTED");
                    return Map.of();
                }
            };
            return new DatabaseImportSession() {
                boolean committed, attempted, aborted;
                @Override public void write(String table, MigrationRows rows) throws Exception { jdbc.write(table, rows); }
                @Override public MigrationVerification verify(MigrationSnapshot snapshot) throws Exception { return jdbc.verify(snapshot); }
                @Override public void commit() throws Exception { attempted = true; jdbc.commit(); committed = true; }
                @Override public void abort() throws Exception {
                    if (attempted) { jdbc.abort(); return; }
                    if (aborted) return;
                    jdbc.abort();
                    for (var table : schema.tables()) execute(connection, "DROP TABLE " + quote(table.name()));
                    target.schema = null;
                    aborted = true;
                }
                @Override public void close() throws Exception {
                    try { if (!committed && !attempted) abort(); } finally { jdbc.close(); }
                }
            };
        } catch (Exception failure) { connection.close(); throw failure; }
    }
    @Override public void verifySource(DatabaseConnectionTarget source, String revision) throws Exception {
        var target = (Target) source;
        if (!revision.equals(revision(target, Objects.requireNonNull(target.schema)))) throw new IllegalStateException("MIGRATION_SOURCE_CHANGED");
    }
    @Override public Inspection inspectMigration(DatabaseConnectionTarget target, MigrationSchema schema, boolean source) throws Exception {
        try (var connection = target.openConnection(DatabaseConnectionTarget.OpenMode.READ_ONLY)) {
            if (source) ((Target) target).validate(connection, schema);
            try (var tables = connection.getMetaData().getTables(null, "PUBLIC", null, new String[]{"TABLE"})) {
                return new Inspection(!tables.next(), Set.of());
            }
        }
    }
    @Override public void verifyImported(DatabaseConnectionTarget target, MigrationSnapshot snapshot, MigrationControl control) throws Exception {
        try (var connection = target.openConnection(DatabaseConnectionTarget.OpenMode.READ_ONLY)) {
            ((Target) target).validate(connection, snapshot.schema());
            if (!verification(connection, snapshot.schema(), control).equals(snapshot.verification()))
                throw new IllegalStateException("DATA_MISMATCH");
        }
    }
    private static MigrationRows rows(Connection connection, TableSpec table) throws SQLException {
        return JdbcMigrationSupport.rows(connection, table, c -> c.type().equals("TEXT")
                ? "STRINGTOUTF8(" + quote(c.name()) + ")" : quote(c.name()));
    }
    private static MigrationVerification verification(Connection connection, MigrationSchema schema, MigrationControl control) throws Exception {
        return new MigrationVerification(digests(schema, control, table -> rows(connection, table)), Map.of(), references(connection, schema, control));
    }
    private static String revision(Target target, MigrationSchema schema) throws Exception {
        try (var connection = target.openConnection(DatabaseConnectionTarget.OpenMode.EXISTING)) {
            var digest = MigrationValues.newDigest();
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("SCRIPT NODATA")) {
                while (rows.next()) MigrationValues.hash(digest, rows.getString(1));
            }
            MigrationValues.hash(digest, new TreeMap<>(digests(schema, (t, n) -> {}, table -> rows(connection, table))).toString());
            return MigrationValues.finish(digest);
        }
    }
    private static final class Target implements DatabaseConnectionTarget {
        @Override public void optimize(Connection connection) throws SQLException { execute(connection, "ANALYZE"); }
        @Override public SchemaApplyResult applySchema(Connection connection, MigrationSchema declared, boolean strict) throws SQLException {
            boolean fresh = schema == null;
            initializeStorage(connection, declared);
            return new SchemaApplyResult(fresh ? declared.tables().size() : 0, 0, 0, List.of());
        }
        @Override public top.sywyar.pixivdownload.plugin.api.database.migration.jdbc.PhysicalDatabaseSchema.DatabaseSchema physicalSchema(
                top.sywyar.pixivdownload.plugin.api.database.migration.jdbc.PhysicalDatabaseSchema.DatabaseSchema declared) {
            var result = new LinkedHashMap<String, top.sywyar.pixivdownload.plugin.api.database.migration.jdbc.PhysicalDatabaseSchema.TableSpec>();
            declared.tables().forEach((name, table) -> result.put(name,
                    new top.sywyar.pixivdownload.plugin.api.database.migration.jdbc.PhysicalDatabaseSchema.TableSpec(name,
                            table.columns().stream().map(c -> new top.sywyar.pixivdownload.plugin.api.database.migration.jdbc.PhysicalDatabaseSchema.ColumnSpec(
                                    c.name(), physicalType(c.type()),
                                    c.notNull() || c.primaryKeyPosition() > 0, c.defaultValue(), c.primaryKeyPosition())).toList(), table.indexes())));
            return new top.sywyar.pixivdownload.plugin.api.database.migration.jdbc.PhysicalDatabaseSchema.DatabaseSchema(result);
        }
        @Override public Map<String, top.sywyar.pixivdownload.plugin.api.database.migration.jdbc.PhysicalDatabaseSchema.TableSpec>
                readSchema(Connection connection) throws SQLException {
            var result = new LinkedHashMap<String, top.sywyar.pixivdownload.plugin.api.database.migration.jdbc.PhysicalDatabaseSchema.TableSpec>();
            var metadata = connection.getMetaData();
            try (var tables = metadata.getTables(null, "PUBLIC", null, new String[]{"TABLE"})) {
                while (tables.next()) {
                    String table = tables.getString("TABLE_NAME");
                    var keys = new HashMap<String, Integer>();
                    try (var pk = metadata.getPrimaryKeys(null, "PUBLIC", table)) {
                        while (pk.next()) keys.put(pk.getString("COLUMN_NAME"), pk.getInt("KEY_SEQ"));
                    }
                    var columns = new ArrayList<top.sywyar.pixivdownload.plugin.api.database.migration.jdbc.PhysicalDatabaseSchema.ColumnSpec>();
                    try (var rows = metadata.getColumns(null, "PUBLIC", table, null)) {
                        while (rows.next()) columns.add(new top.sywyar.pixivdownload.plugin.api.database.migration.jdbc.PhysicalDatabaseSchema.ColumnSpec(
                                rows.getString("COLUMN_NAME"), rows.getString("TYPE_NAME"),
                                rows.getInt("NULLABLE") == java.sql.DatabaseMetaData.columnNoNulls, rows.getString("COLUMN_DEF"),
                                keys.getOrDefault(rows.getString("COLUMN_NAME"), 0)));
                    }
                    var indexColumns = new LinkedHashMap<String, List<String>>();
                    var unique = new HashMap<String, Boolean>();
                    try (var rows = metadata.getIndexInfo(null, "PUBLIC", table, false, false)) {
                        while (rows.next()) {
                            String name = rows.getString("INDEX_NAME"), column = rows.getString("COLUMN_NAME");
                            if (name == null || column == null) continue;
                            indexColumns.computeIfAbsent(name, ignored -> new ArrayList<>()).add(column);
                            unique.put(name, !rows.getBoolean("NON_UNIQUE"));
                        }
                    }
                    var keyColumns = keys.entrySet().stream().sorted(Map.Entry.comparingByValue()).map(Map.Entry::getKey).toList();
                    Set<String> explicit = schema == null ? Set.of() : schema.table(table).indexes().stream()
                            .filter(i -> i.origin() == IndexOrigin.CREATE_INDEX).map(IndexSpec::name).collect(java.util.stream.Collectors.toSet());
                    var indexes = new ArrayList<PhysicalDatabaseSchema.IndexSpec>();
                    indexColumns.forEach((name, fields) -> {
                        if (!fields.equals(keyColumns)) indexes.add(new PhysicalDatabaseSchema.IndexSpec(name,
                                explicit.contains(name) ? PhysicalDatabaseSchema.IndexOrigin.CREATE_INDEX
                                        : PhysicalDatabaseSchema.IndexOrigin.UNIQUE_CONSTRAINT, unique.get(name), fields));
                    });
                    result.put(PhysicalDatabaseSchema.normalizeIdentifier(table), new PhysicalDatabaseSchema.TableSpec(table, columns, indexes));
                }
            }
            return result;
        }
        final String id;
        final String url;
        final Map<String, Target> owners = new HashMap<>();
        MigrationSchema schema;
        Target(String id) { this.id = id; this.url = "jdbc:h2:mem:" + id.substring(9) + ";DB_CLOSE_DELAY=-1;MODE=LEGACY"; }
        @Override public String description() { return id; }
        @Override public Optional<Path> localFile() { return Optional.empty(); }
        @Override public Connection openConnection(OpenMode mode) throws SQLException { return new org.h2.Driver().connect(url, new Properties()); }
        @Override public void checkConnection() throws SQLException { try (var ignored = openConnection(OpenMode.READ_ONLY)) {} }
        @Override public void requireConnectionTarget(Connection connection) throws SQLException {
            if (!connection.getMetaData().getURL().equals(url.split(";")[0])) throw new SQLException("DATABASE_TARGET_MISMATCH");
        }
        @Override public void prepareTransaction(Connection connection, boolean readOnly) throws SQLException { requireConnectionTarget(connection); }
        @Override public DatabaseConnectionTarget privateStorage(DatabasePrivateStorageSettings settings) {
            return owners.computeIfAbsent(settings.owner(), owner -> new Target(id + "_" + owner.replace('-', '_')));
        }
        @Override public void initializeStorage(Connection connection, MigrationSchema declared) throws SQLException {
            if (schema != null) { validate(connection, declared); return; }
            // H2 DDL 会提交事务；夹具仅允许在独立预置或空迁移目标阶段创建结构。
            if (!connection.getAutoCommit()) throw new SQLException("STORAGE_TRANSACTIONAL_DDL_UNSUPPORTED", "0A000");
            for (var table : declared.tables()) {
                if (table.checkExpression() != null && !table.checkExpression().equals("id = 1"))
                    throw new SQLException("FIXTURE_SCHEMA_UNSUPPORTED", "0A000");
                for (var column : table.columns()) physicalType(column.type());
            }
            var created = new ArrayList<String>();
            try {
            for (var table : declared.tables()) {
                var columns = new ArrayList<String>();
                for (var column : table.columns()) columns.add(quote(column.name()) + " " + physicalType(column.type())
                        + (column.autoIncrement() ? " GENERATED BY DEFAULT AS IDENTITY" : "")
                        + (column.defaultValue() == null ? "" : " DEFAULT " + column.defaultValue())
                        + (column.notNull() ? " NOT NULL" : ""));
                columns.add("PRIMARY KEY (" + String.join(",", MigrationSchema.primaryKey(table).stream().map(JdbcMigrationSupport::quote).toList()) + ")");
                for (var index : table.indexes()) if (index.origin() == IndexOrigin.UNIQUE_CONSTRAINT)
                    columns.add("UNIQUE (" + quoted(index.columns()) + ")");
                String check = table.check() == null ? table.checkExpression()
                        : top.sywyar.pixivdownload.plugin.api.database.migration.jdbc.JdbcSchemaCheck.render(table,
                                text -> "NOT REGEXP_LIKE(" + text + ", '[^0-9a-f]')");
                if (check != null && !check.isBlank()) columns.add("CHECK (" + check + ")");
                execute(connection, "CREATE TABLE " + quote(table.name()) + " (" + String.join(",", columns) + ")");
                created.add(table.name());
                for (var index : table.indexes()) if (index.origin() == IndexOrigin.CREATE_INDEX)
                    execute(connection, "CREATE " + (index.unique() ? "UNIQUE " : "") + "INDEX " + quote(index.name())
                            + " ON " + quote(table.name()) + " (" + quoted(index.columns()) + ")");
            }
            schema = declared;
            validate(connection, declared);
            } catch (SQLException | RuntimeException failure) {
                for (var name : created) try { execute(connection, "DROP TABLE " + quote(name)); }
                catch (SQLException cleanup) { failure.addSuppressed(cleanup); }
                schema = null;
                throw failure;
            }
        }
        private static String quoted(List<String> columns) { return String.join(",", columns.stream().map(JdbcMigrationSupport::quote).toList()); }
        private static String physicalType(String type) {
            return switch (type) { case "INTEGER" -> "BIGINT"; case "REAL" -> "DOUBLE PRECISION";
                case "TEXT" -> "CHARACTER VARYING"; case "BLOB" -> "BINARY LARGE OBJECT";
                default -> throw new IllegalArgumentException("FIXTURE_TYPE_UNSUPPORTED"); };
        }
        void validate(Connection connection, MigrationSchema expected) throws SQLException {
            if (schema != null && !schema.tables().equals(expected.tables())) throw new SQLException("FIXTURE_SCHEMA_MISMATCH");
            var names = new HashSet<String>();
            try (var tables = connection.getMetaData().getTables(null, "PUBLIC", null, new String[]{"TABLE"})) {
                while (tables.next()) names.add(tables.getString("TABLE_NAME"));
            }
            if (!names.equals(expected.tables().stream().map(TableSpec::name).collect(java.util.stream.Collectors.toSet())))
                throw new SQLException("FIXTURE_SCHEMA_MISMATCH");
            for (var table : expected.tables()) {
                var columns = new HashMap<String, String>();
                try (var rows = connection.getMetaData().getColumns(null, "PUBLIC", table.name(), null)) {
                    while (rows.next()) columns.put(rows.getString("COLUMN_NAME"), rows.getString("TYPE_NAME"));
                }
                if (columns.size() != table.columns().size()) throw new SQLException("FIXTURE_SCHEMA_MISMATCH");
                for (var column : table.columns()) if (!Objects.equals(columns.get(column.name()),
                        physicalType(column.type()))) throw new SQLException("FIXTURE_SCHEMA_MISMATCH");
            }
        }
        @Override public DatabaseSession storageSession(Connection connection, MigrationSchema schema) {
            return new JdbcDatabaseSession(connection, schema) {
                @Override protected String epochMonth(String epochMillis, String zone, boolean fixedOffset) {
                    return "FORMATDATETIME(DATEADD('MILLISECOND'," + epochMillis
                            + ",TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00'),'yyyy-MM','en'," + zone + ")";
                }
                // H2 的 LEGACY 模式在显式 identity 写入时原生推进生成器。
                @Override protected void beforeInsert(String table, Set<String> columns) {}
                @Override protected void afterInsert(String table, Set<String> columns) {}
                @Override protected String substring(String text, String start, String length) {
                    return "substring(" + text + ",CAST(" + start + " AS INTEGER),CAST(" + length + " AS INTEGER))";
                }
                @Override protected String updateReturningStatement(String update, List<String> columns) {
                    return "SELECT " + String.join(",", columns) + " FROM FINAL TABLE (" + update + ")";
                }
                @Override protected String generatedInsertStatement(String insert, String key) {
                    return "SELECT " + quote(key) + " FROM FINAL TABLE (" + insert + ")";
                }
                @Override protected String insertFromStatement(String table, List<String> columns, String select,
                                                              Conflict conflict, String updates) {
                    if (conflict.mode() == Mode.ERROR) return "INSERT INTO " + quote(table) + " (" + String.join(",", columns) + ") " + select;
                    String match = conflictMatch(table, columns, conflict);
                    return "MERGE INTO " + quote(table) + " USING (" + select + ") AS excluded ON " + match
                            + (conflict.mode() == Mode.UPDATE ? " WHEN MATCHED THEN UPDATE SET " + updates : "")
                            + " WHEN NOT MATCHED THEN INSERT (" + String.join(",", columns) + ") VALUES ("
                            + String.join(",", columns.stream().map(c -> "excluded." + c).toList()) + ")";
                }
                @Override protected String arithmetic(top.sywyar.pixivdownload.plugin.api.storage.relational.Expression.Operator operator,
                                                      String left, String right, boolean integral) {
                    String symbol = switch (operator) { case ADD -> "+"; case SUBTRACT -> "-"; case MULTIPLY -> "*"; default -> throw new IllegalArgumentException(); };
                    return "(" + left + symbol + right + ")";
                }
                @Override protected String lowerAscii(String value) { return "TRANSLATE(" + value + ",'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz')"; }
                @Override protected String minimum(String left, String right) {
                    return "(SELECT CASE WHEN v.a IS NULL OR v.b IS NULL THEN NULL ELSE LEAST(v.a,v.b) END FROM (VALUES ("
                            + left + "," + right + ")) AS v(a,b))";
                }
                @Override protected String conflictClause(String table, Conflict conflict, String updates) { throw new AssertionError("native MERGE required"); }
                @Override protected String insertStatement(String table, List<String> columns, Conflict conflict, String updates) {
                    String values = String.join(",", Collections.nCopies(columns.size(), "?"));
                    if (conflict.mode() == Mode.ERROR) return "INSERT INTO " + quote(table) + " (" + String.join(",", columns) + ") VALUES (" + values + ")";
                    String match = conflictMatch(table, columns, conflict);
                    return "MERGE INTO " + quote(table) + " USING (VALUES (" + values + ")) AS excluded(" + String.join(",", columns)
                            + ") ON " + match + (conflict.mode() == Mode.UPDATE ? " WHEN MATCHED THEN UPDATE SET " + updates : "")
                            + " WHEN NOT MATCHED THEN INSERT (" + String.join(",", columns) + ") VALUES ("
                            + String.join(",", columns.stream().map(c -> "excluded." + c).toList()) + ")";
                }
                private String conflictMatch(String table, List<String> columns, Conflict conflict) {
                    var keys = new ArrayList<List<String>>();
                    if (!conflict.keys().isEmpty()) keys.add(conflict.keys());
                    else {
                        keys.add(MigrationSchema.primaryKey(schema.table(table)));
                        schema.table(table).indexes().stream().filter(IndexSpec::unique).map(IndexSpec::columns).forEach(keys::add);
                    }
                    var predicates = keys.stream().filter(k -> columns.containsAll(k.stream().map(JdbcMigrationSupport::quote).toList()))
                            .map(k -> "(" + String.join(" AND ", k.stream().map(c -> quote(table) + "." + quote(c) + "=excluded." + quote(c)).toList()) + ")").toList();
                    return predicates.isEmpty() ? "FALSE" : String.join(" OR ", predicates);
                }
            };
        }
    }
}
