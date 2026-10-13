package top.sywyar.pixivdownload.core.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import top.sywyar.pixivdownload.core.db.schema.DatabaseInitializer;
import top.sywyar.pixivdownload.core.db.schema.DatabaseSchemaInspector;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.plugin.api.schema.SchemaContribution;
import top.sywyar.pixivdownload.plugin.api.schema.TableSpec;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.registry.PluginSource;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.schema.PluginSchemaLifecycleCoordinator;
import top.sywyar.pixivdownload.plugin.registry.schema.DatabaseSchemaRegistry;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static top.sywyar.pixivdownload.core.db.schema.SchemaSpecs.*;

@EnabledIfEnvironmentVariable(named = "PIXIV_TEST_POSTGRES_URL", matches = ".+")
@DisplayName("真实 PostgreSQL 连接与 schema")
class PostgresInfrastructureTest {
    @Test
    @DisplayName("全部内置声明建库、重启、自省、生成键和安全补列")
    void builtInSchemaSurvivesRestartAndEvolution() throws Exception {
        try (var database = new PostgresTestDatabase()) {
            var registry = DatabaseSchemaRegistry.forBuiltInPlugins();
            var jdbc = new JdbcTemplate(database.pool());
            var initializer = initializer(jdbc, registry);
            initializer.initialize();
            try (var connection = database.pool().getConnection()) {
                assertThat(DatabaseEngine.detect(connection)).isEqualTo(DatabaseEngine.POSTGRESQL);
                assertThat(top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.compare(connection, registry.mergedSchema()).differences()).isEmpty();
                try (var statement = connection.prepareStatement(
                        "INSERT INTO tags(name) VALUES (?)", new String[]{"tag_id"})) {
                    statement.setString(1, "中文 日本語 🌸");
                    statement.executeUpdate();
                    try (var keys = statement.getGeneratedKeys()) {
                        assertThat(keys.next()).isTrue();
                        assertThat(keys.getLong(1)).isPositive();
                    }
                }
            }
            jdbc.update("INSERT INTO authors(author_id,name,updated_time) VALUES (?,?,?)",
                    5_000_000_000L, "作者 🌸", 1_700_000_000_123L);
            jdbc.update("INSERT INTO artworks(artwork_id,title,folder,count,extensions,time) VALUES (?,?,?,?,?,?)",
                    5_000_000_001L, "作品 🌸", "{0}/花", 1, "png", 1_700_000_000_124L);
            jdbc.execute("ALTER TABLE artworks DROP COLUMN file_name_max_length");
            initializer.initialize();
            assertThat(jdbc.queryForObject("SELECT file_name_max_length FROM artworks", Integer.class)).isEqualTo(180);
            assertThat(jdbc.queryForObject("SELECT author_id FROM authors", Long.class)).isEqualTo(5_000_000_000L);
            assertThat(jdbc.queryForObject("SELECT name FROM authors", String.class)).isEqualTo("作者 🌸");
            assertThat(jdbc.queryForObject("SELECT updated_time FROM authors", Long.class)).isEqualTo(1_700_000_000_123L);
            try (var connection = top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.resolve(database.properties(), "unused").openConnection()) {
                assertThat(top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.compare(connection, registry.mergedSchema()).differences()).isEmpty();
            }
            jdbc.execute("ALTER TABLE authors ALTER COLUMN name TYPE VARCHAR(20)");
            assertThatThrownBy(initializer::initialize).isInstanceOf(IllegalStateException.class);
            try (var connection = database.pool().getConnection()) {
                assertThat(top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.compare(connection, registry.mergedSchema()).matches()).isFalse();
            }
        }
    }

    @Test
    @DisplayName("同连接 DDL 校验失败时回滚结构且不发布 registry")
    void ownerDdlAndRegistryRollbackTogether() throws Exception {
        try (var database = new PostgresTestDatabase()) {
            var registry = new DatabaseSchemaRegistry(new PluginRegistry(List.of()));
            var jdbc = new JdbcTemplate(database.pool());
            var initializer = initializer(jdbc, registry);
            var contribution = new SchemaContribution(List.of(new TableSpec("owner_rows", List.of(
                    autoIncrementPrimaryKey("id"), column("value", "TEXT", false, null, 0)), List.of())),
                    List.of(), List.of());
            var transaction = new TransactionTemplate(database.transactionManager());
            try (var reservation = registry.reserveOwnerEvolution("fixture", List.of(contribution))) {
                assertThatThrownBy(() -> transaction.execute(status -> {
                    initializer.applyRuntimeOwnerSchema(List.of(contribution), reservation.ownerSchema(), Set.of("owner_rows"));
                    jdbc.execute("ALTER TABLE owner_rows ALTER COLUMN value TYPE VARCHAR(12)");
                    initializer.applyRuntimeOwnerSchema(List.of(contribution), reservation.ownerSchema(), Set.of("owner_rows"));
                    return null;
                })).isInstanceOf(IllegalStateException.class);
            }
            assertThat(registry.mergedSchema().tables()).isEmpty();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_schema = current_schema()",
                    Long.class)).isZero();
            transaction.executeWithoutResult(status -> {
                try (var reservation = registry.reserveOwnerEvolution("fixture", List.of(contribution))) {
                    initializer.applyRuntimeOwnerSchema(List.of(contribution), reservation.ownerSchema(), Set.of("owner_rows"));
                }
            });
            assertThat(jdbc.queryForObject("SELECT count(*) FROM owner_rows", Long.class)).isZero();
        }
    }

    @Test
    @DisplayName("不支持的声明在任何 DDL 之前失败，连接失败不回落 SQLite")
    void rejectsUnsupportedSchemaAndFailedConnection() throws Exception {
        try (var database = new PostgresTestDatabase()) {
            var registry = new DatabaseSchemaRegistry(new PluginRegistry(List.of()));
            registry.register("fixture", new SchemaContribution(List.of(
                    new TableSpec("first_table", List.of(autoIncrementPrimaryKey("id")), List.of()),
                    new TableSpec("unsupported_table", List.of(column("value", "MONEY", false, null, 0)), List.of())),
                    List.of(), List.of()));
            var jdbc = new JdbcTemplate(database.pool());
            assertThatThrownBy(() -> initializer(jdbc, registry).initialize()).isInstanceOf(IllegalArgumentException.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_schema = current_schema()",
                    Long.class)).isZero();
            database.properties().setPassword("deliberately-wrong-password");
            database.properties().setUsername("missing_" + java.util.UUID.randomUUID().toString().replace("-", ""));
            assertThatThrownBy(() -> top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.resolve(database.properties(), "unused").openConnection())
                    .isInstanceOf(java.sql.SQLException.class)
                    .hasMessageNotContaining("deliberately-wrong-password");
        }
    }

    @Test
    @DisplayName("真实 owner 协调器只在结构提交成功后发布演进")
    void coordinatorPublishesOnlyCommittedSchema() throws Exception {
        try (var database = new PostgresTestDatabase()) {
            var registry = new DatabaseSchemaRegistry(new PluginRegistry(List.of()));
            var jdbc = new JdbcTemplate(database.pool());
            var coordinator = new PluginSchemaLifecycleCoordinator(registry, initializer(jdbc, registry),
                    database.transactionManager());
            var first = new SchemaContribution(List.of(new TableSpec("retained_rows", List.of(
                    autoIncrementPrimaryKey("id"), column("value", "TEXT", false, null, 0)), List.of())), List.of(), List.of());
            var second = new SchemaContribution(List.of(new TableSpec("retained_rows", List.of(
                    autoIncrementPrimaryKey("id"), column("value", "TEXT", false, null, 0),
                    column("category", "TEXT", false, null, 0)), List.of())), List.of(), List.of());
            coordinator.activate(registered(first, 1));
            jdbc.execute("ALTER TABLE retained_rows ALTER COLUMN value TYPE VARCHAR(12)");
            assertThatThrownBy(() -> coordinator.activate(registered(second, 2))).isInstanceOf(IllegalStateException.class);
            assertThat(registry.mergedSchema().tables().get("retained_rows").columns())
                    .extracting(column -> column.name()).containsExactly("id", "value");
            assertThat(jdbc.queryForList("""
                    SELECT column_name FROM information_schema.columns
                    WHERE table_schema = current_schema() AND table_name = 'retained_rows'
                    ORDER BY ordinal_position
                    """, String.class)).containsExactly("id", "value");
            jdbc.execute("ALTER TABLE retained_rows ALTER COLUMN value TYPE TEXT COLLATE \"C\"");
            coordinator.activate(registered(second, 3));
            assertThat(registry.mergedSchema().tables().get("retained_rows").columns())
                    .extracting(column -> column.name()).containsExactly("id", "value", "category");
        }
    }

    @Test
    @DisplayName("错误 schema 和无建表权限均拒绝，失败事务不留下受管结构")
    void rejectsMissingSchemaAndInsufficientDdlPrivileges() throws Exception {
        try (var database = new PostgresTestDatabase(); var connection = database.pool().getConnection()) {
            String role = "fixture_" + java.util.UUID.randomUUID().toString().replace("-", "");
            String schema = connection.getSchema();
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE ROLE " + role);
                try {
                    statement.execute("GRANT USAGE ON SCHEMA " + schema + " TO " + role);
                    connection.setAutoCommit(false);
                    statement.execute("SET LOCAL ROLE " + role);
                    var registry = DatabaseSchemaRegistry.forBuiltInPlugins();
                    assertThatThrownBy(() -> top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.offline(connection, registry.contributions(),
                            registry.mergedSchema()).applySchema(registry.contributions(), registry.mergedSchema()))
                            .isInstanceOfSatisfying(org.springframework.jdbc.BadSqlGrammarException.class,
                                    error -> assertThat(error.getSQLException().getSQLState()).isEqualTo("42501"));
                    connection.rollback();
                    connection.setAutoCommit(true);
                    var jdbc = new JdbcTemplate(database.pool());
                    assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.tables"
                            + " WHERE table_schema = current_schema()", Long.class)).isZero();
                } finally {
                    if (!connection.getAutoCommit()) connection.rollback();
                    connection.setAutoCommit(true);
                    statement.execute("REVOKE USAGE ON SCHEMA " + schema + " FROM " + role);
                    statement.execute("DROP ROLE " + role);
                }
            }
            database.properties().setJdbcUrl(database.properties().getJdbcUrl()
                    .replace("currentSchema=" + schema, "currentSchema=" + role));
            assertThatThrownBy(() -> top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.resolve(database.properties(), "unused").openConnection())
                    .isInstanceOf(java.sql.SQLException.class);
        }
    }

    @Test
    @DisplayName("生成键与 CHECK 漂移拒绝启动且不留下其它补列")
    void rejectsGeneratedKeyAndCheckDrift() throws Exception {
        try (var database = new PostgresTestDatabase()) {
            var registry = DatabaseSchemaRegistry.forBuiltInPlugins();
            var jdbc = new JdbcTemplate(database.pool());
            var initializer = initializer(jdbc, registry);
            initializer.initialize();
            jdbc.execute("ALTER TABLE tags ALTER COLUMN tag_id DROP IDENTITY");
            jdbc.execute("ALTER TABLE artworks DROP COLUMN metadata_json");
            assertThatThrownBy(initializer::initialize).isInstanceOf(IllegalStateException.class);
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM information_schema.columns WHERE table_schema = current_schema()
                    AND table_name = 'artworks' AND column_name = 'metadata_json'
                    """, Long.class)).isZero();
            jdbc.execute("ALTER TABLE tags ALTER COLUMN tag_id ADD GENERATED BY DEFAULT AS IDENTITY");
            String check = jdbc.queryForObject("""
                    SELECT c.conname FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid
                    JOIN pg_namespace n ON n.oid = t.relnamespace
                    WHERE n.nspname = current_schema() AND t.relname = 'statistics' AND c.contype = 'c'
                    """, String.class);
            jdbc.execute("ALTER TABLE statistics DROP CONSTRAINT " + check);
            assertThatThrownBy(initializer::initialize).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    @DisplayName("identity 改为 ALWAYS 时拒绝初始化并回滚其它补列，恢复后支持显式身份")
    void rejectsAlwaysIdentityAndRollsBackEvolution() throws Exception {
        try (var database = new PostgresTestDatabase()) {
            var registry = DatabaseSchemaRegistry.forBuiltInPlugins();
            var jdbc = new JdbcTemplate(database.pool());
            var initializer = initializer(jdbc, registry);
            initializer.initialize();
            jdbc.execute("ALTER TABLE artworks ALTER COLUMN artwork_id SET GENERATED ALWAYS");
            jdbc.execute("ALTER TABLE artworks DROP COLUMN metadata_json");
            assertThatThrownBy(initializer::initialize).isInstanceOf(IllegalStateException.class);
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM information_schema.columns WHERE table_schema = current_schema()
                    AND table_name = 'artworks' AND column_name = 'metadata_json'
                    """, Long.class)).isZero();
            try (var connection = database.pool().getConnection()) {
                assertThat(top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.compare(connection, registry.mergedSchema()).matches()).isFalse();
            }
            jdbc.execute("ALTER TABLE artworks ALTER COLUMN artwork_id SET GENERATED BY DEFAULT");
            initializer.initialize();
            jdbc.update("INSERT INTO artworks(artwork_id,title,folder,count,extensions,time) VALUES(?,?,?,?,?,?)",
                    5_000_000_001L, "显式作品身份", "{0}/作品", 1, "png", 1_700_000_000_123L);
            assertThat(jdbc.queryForObject("SELECT artwork_id FROM artworks", Long.class)).isEqualTo(5_000_000_001L);
        }
    }

    private PluginRegistry.RegisteredPlugin registered(SchemaContribution schema, long generation) {
        var plugin = org.mockito.Mockito.mock(PixivFeaturePlugin.class);
        org.mockito.Mockito.when(plugin.id()).thenReturn("fixture");
        org.mockito.Mockito.when(plugin.schema()).thenReturn(List.of(schema));
        return new PluginRegistry.RegisteredPlugin(plugin, PluginSource.EXTERNAL,
                getClass().getClassLoader(), "fixture", generation);
    }

    private DatabaseInitializer initializer(JdbcTemplate jdbc, DatabaseSchemaRegistry registry) {
        return top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.initializer(jdbc, registry.contributions(), registry.mergedSchema(),
                TestI18nBeans.appMessages(), event -> {});
    }
}
