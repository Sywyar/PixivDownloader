package top.sywyar.pixivdownload.databasepostgresql;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.plugin.api.database.DatabaseConnectionSettings;
import top.sywyar.pixivdownload.plugin.api.database.DatabaseConnectionTarget;
import top.sywyar.pixivdownload.plugin.api.database.DatabasePrivateStorageSettings;
import top.sywyar.pixivdownload.plugin.api.schema.ColumnSpec;
import top.sywyar.pixivdownload.plugin.api.schema.SchemaContribution;
import top.sywyar.pixivdownload.plugin.api.schema.TableSpec;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static top.sywyar.pixivdownload.plugin.api.storage.relational.Expression.*;
import static top.sywyar.pixivdownload.plugin.api.storage.relational.Expression.Operator.*;

@DisplayName("真实 PostgreSQL provider 与私有角色隔离")
class PostgresProviderIntegrationTest {
    @TempDir Path directory;
    private Connection administration;
    private String schema;
    private String base;
    private PostgresConnectionTarget target;
    private final List<String> privateSchemas = new ArrayList<>();
    private final String installation = UUID.randomUUID().toString();

    @BeforeEach
    void createIsolatedSchema() throws Exception {
        String configured = System.getenv("PIXIV_TEST_POSTGRES_URL");
        Assumptions.assumeTrue(configured != null && !configured.isBlank(), "需要显式隔离 PostgreSQL 环境");
        base = configured.split("\\?", 2)[0];
        administration = new org.postgresql.Driver().connect(base, credentials());
        schema = "pgdata_fixture_" + UUID.randomUUID().toString().replace("-", "");
        try (var statement = administration.createStatement()) { statement.execute("CREATE SCHEMA " + schema); }
        target = new PostgresConnectionTarget(settings(base + "?currentSchema=" + schema));
    }

    @AfterEach
    void removeOnlyFixtureObjects() throws Exception {
        if (administration == null) return;
        try (Connection closing = administration; var statement = closing.createStatement()) {
            for (String privateSchema : privateSchemas) {
                statement.execute("DROP SCHEMA IF EXISTS " + privateSchema + " CASCADE");
                statement.execute("DROP ROLE IF EXISTS " + privateSchema);
            }
            statement.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    @Test
    @DisplayName("只读核验接受等价的文本和整数 IN 约束，拒绝真实约束漂移")
    void verifiesChecksWithoutWrites() throws Exception {
        var table = new TableSpec("facts", List.of(
                new ColumnSpec("kind", "TEXT", true, null, 0),
                new ColumnSpec("enabled", "INTEGER", true, null, 0),
                new ColumnSpec("digest", "TEXT", true, null, 0)), List.of()).withCheck(call(AND,
                call(IN, column("facts", "kind"), value("download"), value("system")),
                call(IN, column("facts", "enabled"), value(0), value(1)),
                call(GREATER, call(LENGTH, call(TRIM, column("facts", "kind"))), value(0)),
                call(LOWER_HEX, column("facts", "digest"))));
        var declarations = List.of(new SchemaContribution(List.of(table), List.of(), List.of()));
        try (var connection = target.openConnection(DatabaseConnectionTarget.OpenMode.READ_WRITE);
             var statement = connection.createStatement()) {
            statement.execute(PostgresMigrationSchema.renderCreateTable(table));
        }
        try (var connection = target.openConnection(DatabaseConnectionTarget.OpenMode.READ_ONLY)) {
            connection.setAutoCommit(false);
            PostgresMigrationSchema.validateConstraints(connection, declarations);
            connection.rollback();
        }
        try (var connection = target.openConnection(DatabaseConnectionTarget.OpenMode.READ_WRITE);
             var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE facts DROP CONSTRAINT facts_check");
            statement.execute("ALTER TABLE facts ADD CHECK (enabled IN (0,2))");
        }
        try (var connection = target.openConnection(DatabaseConnectionTarget.OpenMode.READ_ONLY)) {
            assertThatThrownBy(() -> PostgresMigrationSchema.validateConstraints(connection, declarations))
                    .isInstanceOf(IllegalStateException.class).hasMessage("SCHEMA_MISMATCH");
        }
    }

    private TableSpec evolvingCheck() {
        return new TableSpec("facts", List.of(new ColumnSpec("id", "INTEGER", true, null, 1),
                new ColumnSpec("active", "INTEGER", true, "1", 0)), List.of())
                .withCheck(call(AND, call(GREATER, column("facts", "id"), value(0)),
                        call(IN, column("facts", "active"), value(0), value(1))))
                .withPreviousChecks(List.of(call(GREATER, column("facts", "id"), value(0))));
    }

    @Test @DisplayName("已知旧约束升级幂等并参与调用方回滚")
    void upgradesKnownCheckTransactionally() throws Exception {
        var table = evolvingCheck();
        var model = new top.sywyar.pixivdownload.plugin.api.database.migration.MigrationSchema(1, List.of(table), List.of(), List.of());
        try (var connection = target.openConnection(DatabaseConnectionTarget.OpenMode.READ_WRITE); var sql = connection.createStatement()) {
            sql.execute(PostgresMigrationSchema.renderCreateTable(table.withCheck(table.previousChecks().get(0))));
            sql.execute("INSERT INTO facts(active) VALUES(1)");
            connection.setAutoCommit(false);
            sql.execute("UPDATE facts SET active=0");
            target.applySchema(connection, model, true);
            target.applySchema(connection, model, true);
            PostgresMigrationSchema.validateConstraints(connection, List.of(new SchemaContribution(List.of(table), List.of(), List.of())));
            connection.rollback();
            try (var rows = sql.executeQuery("SELECT active FROM facts")) { assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isEqualTo(1); }
            target.applySchema(connection, model, true);
            connection.commit();
            assertThatThrownBy(() -> sql.execute("INSERT INTO facts(active) VALUES(2)")).isInstanceOf(SQLException.class);
            connection.rollback();
        }
    }

    @Test @DisplayName("不合规记录使 CHECK 升级回滚，未知约束不能自动替换")
    void refusesInvalidCheckMigration() throws Exception {
        var table = evolvingCheck();
        var model = new top.sywyar.pixivdownload.plugin.api.database.migration.MigrationSchema(1, List.of(table), List.of(), List.of());
        try (var connection = target.openConnection(DatabaseConnectionTarget.OpenMode.READ_WRITE); var sql = connection.createStatement()) {
            sql.execute(PostgresMigrationSchema.renderCreateTable(table.withCheck(table.previousChecks().get(0))));
            sql.execute("INSERT INTO facts(active) VALUES(2)");
            connection.setAutoCommit(false);
            assertThatThrownBy(() -> target.applySchema(connection, model, true)).isInstanceOf(SQLException.class);
            sql.execute("INSERT INTO facts(active) VALUES(2)");
            connection.rollback();
            sql.execute("ALTER TABLE facts DROP CONSTRAINT facts_id_check");
            sql.execute("ALTER TABLE facts ADD CHECK(id>=0)");
            connection.commit();
            assertThatThrownBy(() -> target.applySchema(connection, model, true)).hasMessage("SCHEMA_MISMATCH");
            sql.execute("INSERT INTO facts(id,active) VALUES(0,2)");
            connection.rollback();
        }
    }

    @Test
    @DisplayName("目录中的自定义函数不能借同名列绕过 CHECK 表达式边界")
    void rejectsStoredCheckFunctions() throws Exception {
        var table = new TableSpec("facts", List.of(new ColumnSpec("kind", "TEXT", true, null, 0)),
                List.of(), "length(kind) > 0");
        var declarations = List.of(new SchemaContribution(List.of(table), List.of(), List.of()));
        try (var connection = target.openConnection(DatabaseConnectionTarget.OpenMode.READ_WRITE);
             var statement = connection.createStatement()) {
            statement.execute("CREATE FUNCTION kind(TEXT) RETURNS BOOLEAN LANGUAGE SQL IMMUTABLE AS 'SELECT true'");
            statement.execute("CREATE TABLE facts(kind TEXT COLLATE \"C\" NOT NULL CHECK (kind(kind)))");
        }
        try (var connection = target.openConnection(DatabaseConnectionTarget.OpenMode.READ_ONLY)) {
            assertThatThrownBy(() -> PostgresMigrationSchema.validateConstraints(connection, declarations))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("DATABASE_SCHEMA_UNSUPPORTED");
        }
    }

    @Test
    @DisplayName("草稿检查无 schema 写入，错误凭据和错误目标明确拒绝且不泄露密码")
    void probesWithoutSchemaMutationOrFallback() throws Exception {
        target.checkConnection();
        try (var connection = target.openConnection(DatabaseConnectionTarget.OpenMode.READ_WRITE);
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=current_schema()")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getLong(1)).isZero();
            var other = new PostgresConnectionTarget(settings(base + "?currentSchema=public"));
            assertThatThrownBy(() -> other.requireConnectionTarget(connection)).isInstanceOf(SQLException.class)
                    .hasMessage("DATABASE_TARGET_MISMATCH");
        }
        var wrong = new PostgresConnectionTarget(new DatabaseConnectionSettings(base + "?currentSchema=" + schema,
                System.getenv("PIXIV_TEST_POSTGRES_USER"), "wrong-fixture-password", directory.resolve("unused.db")));
        assertThatThrownBy(wrong::checkConnection).isInstanceOf(SQLException.class)
                .hasMessage("DATABASE_CONNECTION_FAILED").hasNoCause();
        assertThat(directory.resolve("unused.db")).doesNotExist();
    }

    @Test
    @DisplayName("私库持久化毫秒和长整数，拒绝主库、其它 owner、提权及跨 schema DDL")
    void privateRolesCannotAccessOtherOwners() throws Exception {
        try (var statement = administration.createStatement()) {
            statement.execute("CREATE TABLE " + schema + ".main_secret(value TEXT)");
            statement.execute("INSERT INTO " + schema + ".main_secret VALUES ('retained')");
        }
        var first = privateTarget("first-owner", installation, "first-fixture-secret");
        var second = privateTarget("second-owner", installation, "second-fixture-secret");
        try (var one = first.openConnection(DatabaseConnectionTarget.OpenMode.READ_WRITE);
             var two = second.openConnection(DatabaseConnectionTarget.OpenMode.READ_WRITE)) {
            String secondSchema = two.getSchema();
            try (var statement = two.createStatement()) { statement.execute("CREATE TABLE facts(value BIGINT)"); }
            try (var statement = one.createStatement()) {
                statement.execute("CREATE TABLE facts(value BIGINT)");
                statement.execute("INSERT INTO facts VALUES (9007199254740993), (1791600000123)");
                assertDenied(statement, "SELECT * FROM " + schema + ".main_secret");
                assertDenied(statement, "SELECT * FROM " + secondSchema + ".facts");
                assertDenied(statement, "DROP SCHEMA " + schema + " CASCADE");
                assertDenied(statement, "CREATE TABLE " + schema + ".unexpected(value BIGINT)");
                assertDenied(statement, "SET ROLE \"" + System.getenv("PIXIV_TEST_POSTGRES_USER").replace("\"", "\"\"") + "\"");
            }
            one.setAutoCommit(false);
            try (var statement = one.createStatement()) { statement.executeUpdate("DELETE FROM facts"); }
            one.rollback();
        }
        var restarted = privateTarget("first-owner", installation, "first-fixture-secret");
        try (var connection = restarted.openConnection(DatabaseConnectionTarget.OpenMode.READ_WRITE);
             var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT MAX(value), MIN(value), COUNT(*) FROM facts")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getLong(1)).isEqualTo(9007199254740993L);
            assertThat(rows.getLong(2)).isEqualTo(1791600000123L);
            assertThat(rows.getLong(3)).isEqualTo(2);
        }
        var anotherInstallation = privateTarget("first-owner", UUID.randomUUID().toString(), "another-fixture-secret");
        assertThat(anotherInstallation.description()).isNotEqualTo(first.description());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"table", "column", "sequence", "definer"})
    @DisplayName("PUBLIC 授权会破坏隔离时拒绝并回滚私有角色和 schema")
    void publicPrivilegesCannotSilentlyBroadenPrivateAccess(String grant) throws Exception {
        try (var statement = administration.createStatement()) {
            statement.execute("CREATE TABLE " + schema + ".public_fixture(value TEXT)");
            statement.execute("GRANT USAGE ON SCHEMA " + schema + " TO PUBLIC");
            switch (grant) {
                case "table" -> statement.execute("GRANT SELECT ON " + schema + ".public_fixture TO PUBLIC");
                case "column" -> statement.execute("GRANT SELECT(value) ON " + schema + ".public_fixture TO PUBLIC");
                case "sequence" -> {
                    statement.execute("CREATE SEQUENCE " + schema + ".private_sequence");
                    statement.execute("GRANT USAGE ON SEQUENCE " + schema + ".private_sequence TO PUBLIC");
                }
                case "definer" -> statement.execute("CREATE FUNCTION " + schema
                        + ".read_secret() RETURNS TEXT LANGUAGE SQL SECURITY DEFINER AS 'SELECT value FROM "
                        + schema + ".public_fixture LIMIT 1'");
                default -> throw new AssertionError(grant);
            }
        }
        var privateTarget = privateTarget("blocked-owner", installation, "fixture-secret");
        assertThatThrownBy(() -> privateTarget.openConnection(DatabaseConnectionTarget.OpenMode.READ_WRITE))
                .isInstanceOf(SQLException.class).hasMessage("DATABASE_PRIVATE_STORAGE_UNAVAILABLE").hasNoCause()
                .satisfies(failure -> assertThat(((SQLException) failure).getSQLState()).isEqualTo("42501"));
        try (var statement = administration.prepareStatement("SELECT 1 FROM pg_roles WHERE rolname=?")) {
            statement.setString(1, privateSchemas.get(0));
            try (var rows = statement.executeQuery()) { assertThat(rows.next()).isFalse(); }
        }
    }

    private DatabaseConnectionTarget privateTarget(String owner, String install, String password) throws SQLException {
        var result = target.privateStorage(new DatabasePrivateStorageSettings(owner, directory.resolve(owner), install, password));
        String description = result.description();
        String privateSchema = description.substring(description.lastIndexOf('[') + 1, description.length() - 1);
        if (!privateSchemas.contains(privateSchema)) privateSchemas.add(privateSchema);
        return result;
    }

    private static void assertDenied(java.sql.Statement statement, String sql) {
        assertThatThrownBy(() -> statement.execute(sql)).isInstanceOf(SQLException.class)
                .satisfies(failure -> assertThat(((SQLException) failure).getSQLState()).isEqualTo("42501"));
    }
    private DatabaseConnectionSettings settings(String url) {
        return new DatabaseConnectionSettings(url, System.getenv("PIXIV_TEST_POSTGRES_USER"),
                System.getenv("PIXIV_TEST_POSTGRES_PASSWORD"), directory.resolve("unused.db"));
    }
    private static java.util.Properties credentials() {
        var result = new java.util.Properties();
        result.setProperty("user", System.getenv("PIXIV_TEST_POSTGRES_USER"));
        result.setProperty("password", System.getenv("PIXIV_TEST_POSTGRES_PASSWORD"));
        return result;
    }
}
