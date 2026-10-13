package top.sywyar.pixivdownload.core.db.schema;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import top.sywyar.pixivdownload.core.db.*;
import top.sywyar.pixivdownload.core.pixiv.filename.PixivWorkFileNameFormatter;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.plugin.registry.schema.DatabaseSchemaRegistry;
import java.nio.file.*;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

@DisplayName("真实 PostgreSQL 到 SQLite 的源快照和序列验证")
@EnabledIfEnvironmentVariable(named = "PIXIV_TEST_POSTGRES_URL", matches = ".+")
class PostgresToSqliteMigrationTest {
    @TempDir Path directory;
    private final DatabaseSchemaRegistry registry = DatabaseSchemaRegistry.forBuiltInPlugins();
    private void initialize(PostgresTestDatabase source) {
        var jdbc = new JdbcTemplate(source.pool());
        top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.initializer(jdbc, registry.contributions(), registry.mergedSchema(), TestI18nBeans.appMessages(), event -> {}).initialize();
        jdbc.update("INSERT INTO file_name_templates(id,template) VALUES(1,?)", PixivWorkFileNameFormatter.DEFAULT_TEMPLATE);
    }
    @Test @DisplayName("逻辑备份和回迁保留 Unicode、空值、删除后的高水位及孤儿记录")
    void copiesSnapshotAndRetainsDeletedIdentities() throws Exception {
        try (var source = new PostgresTestDatabase()) {
            initialize(source);
            var jdbc = new JdbcTemplate(source.pool());
            jdbc.update("INSERT INTO tags(tag_id,name,translated_name) VALUES(9007199254740993,'你好 🌸',NULL)");
            jdbc.execute("SELECT setval(pg_get_serial_sequence('tags','tag_id'),9007199254740999,true)");
            jdbc.update("INSERT INTO artwork_tags VALUES(42,9007199254740999)");
            var from = DatabaseProviderTestFixtures.resolve(source.properties(), "unused");
            var to = DatabaseProviderTestFixtures.sqlite(directory.resolve("restored.db"));
            var snapshot = DatabaseProviderTestFixtures.migrate(from, directory.resolve("snapshot.xml"), to, registry, true, Set.of());
            assertThat(snapshot.verification().referenceWarnings()).containsEntry("artwork_tags:tag_id:tags:tag_id", 1L);
            assertThat(snapshot.verification().sequences()).containsEntry("tags", 9007199254740999L);
            try (var connection = to.openConnection(); var sql = connection.createStatement()) {
                try (var rows = sql.executeQuery("SELECT tag_id,name,translated_name FROM tags")) {
                    assertThat(rows.next()).isTrue(); assertThat(rows.getLong(1)).isEqualTo(9007199254740993L);
                    assertThat(rows.getString(2)).isEqualTo("你好 🌸"); assertThat(rows.getString(3)).isNull();
                }
                try (var rows = sql.executeQuery("INSERT INTO tags(name) VALUES('next') RETURNING tag_id")) {
                    assertThat(rows.next()).isTrue(); assertThat(rows.getLong(1)).isEqualTo(9007199254741000L);
                }
                try (var rows = sql.executeQuery("SELECT COUNT(*) FROM artwork_tags a JOIN tags t ON t.tag_id=a.tag_id")) {
                    assertThat(rows.next()).isTrue(); assertThat(rows.getLong(1)).isZero();
                }
            }
            assertThat(Files.readString(snapshot.backup())).contains("provider-format=\"postgresql-logical\"", "high-water=\"9007199254740999\"", "<postgresql-object");
            from.migrationEndpoint().provider().verifySource(from.migrationEndpoint().target(), snapshot.sourceRevision());
            jdbc.execute("ALTER TABLE tags ADD CHECK(length(name)>0)");
            assertThatThrownBy(() -> from.migrationEndpoint().provider().verifySource(from.migrationEndpoint().target(), snapshot.sourceRevision()))
                    .hasMessage("MIGRATION_SOURCE_CHANGED");
        }
    }
    @Test @DisplayName("源默认行缺席时只播种临时副本，原库和原始备份保持缺席")
    void seedsOnlyTemporaryCopy() throws Exception {
        try (var source = new PostgresTestDatabase()) {
            initialize(source);
            var jdbc = new JdbcTemplate(source.pool());
            jdbc.execute("DELETE FROM file_name_templates");
            var from = DatabaseProviderTestFixtures.resolve(source.properties(), "unused");
            var to = DatabaseProviderTestFixtures.sqlite(directory.resolve("seeded.db"));
            var snapshot = DatabaseProviderTestFixtures.migrate(from, directory.resolve("unseeded.xml"), to, registry, true, Set.of());
            assertThat(snapshot.verification().tables().get("file_name_templates").rows()).isOne();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM file_name_templates", Long.class)).isZero();
            from.migrationEndpoint().provider().verifySource(from.migrationEndpoint().target(), snapshot.sourceRevision());
        }
    }
    @Test @DisplayName("运行期创建的同名清理触发器与函数可预检并迁出，未声明和重载函数仍拒绝")
    void migratesRuntimeCleanupObjects() throws Exception {
        try (var source = new PostgresTestDatabase()) {
            initialize(source);
            var from = DatabaseProviderTestFixtures.resolve(source.properties(), "unused").migrationEndpoint();
            var schema = CoreMigrationSchema.create(registry, true);
            var rule = new top.sywyar.pixivdownload.plugin.api.storage.relational.StorageFeatures.Cleanup(
                    "runtime_cleanup", "novels", "novel_id", "deleted", 1,
                    java.util.List.of(new top.sywyar.pixivdownload.plugin.api.storage.relational.StorageFeatures.Dependent("novel_tags", "novel_id")));
            try (var connection = from.target().openConnection(top.sywyar.pixivdownload.plugin.api.database.DatabaseConnectionTarget.OpenMode.EXISTING);
                 var session = from.target().storageSession(connection, schema)) {
                session.initializeCleanup(rule);
            }
            var inspection = from.provider().inspectMigration(from.target(), schema, true);
            assertThat(inspection.rebuildObjects()).containsExactly("runtime_cleanup");
            var to = DatabaseProviderTestFixtures.sqlite(directory.resolve("runtime.db"));
            assertThatThrownBy(() -> DatabaseProviderTestFixtures.migrate(from, directory.resolve("undeclared.xml"),
                    to.migrationEndpoint(), registry, true, Set.of())).hasMessage("UNDECLARED_OBJECT");
            var snapshot = DatabaseProviderTestFixtures.migrate(from, directory.resolve("runtime.xml"),
                    to.migrationEndpoint(), registry, true, inspection.rebuildObjects());
            to.migrationEndpoint().provider().verifyImported(to.migrationEndpoint().target(), snapshot, (table, rows) -> {});
            var jdbc = new JdbcTemplate(source.pool());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_trigger WHERE tgname='runtime_cleanup'", Long.class)).isOne();
            jdbc.execute("CREATE FUNCTION runtime_cleanup(bigint) RETURNS bigint LANGUAGE SQL AS 'SELECT $1'");
            assertThatThrownBy(() -> from.provider().inspectMigration(from.target(), schema, true)).hasMessage("UNDECLARED_OBJECT");
        }
    }
    @Test @DisplayName("未知源表、序列步长漂移和持有写锁分别拒绝，源行不变")
    void rejectsUnknownObjectsAndUnsafeSequenceOrWriter() throws Exception {
        try (var source = new PostgresTestDatabase()) {
            initialize(source);
            var jdbc = new JdbcTemplate(source.pool());
            jdbc.update("INSERT INTO tags(name) VALUES('retained')");
            var from = DatabaseProviderTestFixtures.resolve(source.properties(), "unused");
            var to = DatabaseProviderTestFixtures.sqlite(directory.resolve("empty.db"));
            jdbc.execute("CREATE TABLE unknown_history(id BIGINT)");
            assertThatThrownBy(() -> DatabaseProviderTestFixtures.migrate(from, directory.resolve("unknown.xml"), to, registry, true, Set.of()))
                    .hasMessage("UNDECLARED_OBJECT");
            assertThat(directory.resolve("unknown.xml")).doesNotExist();
            jdbc.execute("DROP TABLE unknown_history");
            jdbc.execute("ALTER SEQUENCE tags_tag_id_seq INCREMENT BY 2");
            assertThatThrownBy(() -> DatabaseProviderTestFixtures.migrate(from, directory.resolve("sequence.xml"), to, registry, true, Set.of()))
                    .hasMessage("SEQUENCE_UNSUPPORTED");
            jdbc.execute("ALTER SEQUENCE tags_tag_id_seq INCREMENT BY 1");
            try (var writer = source.pool().getConnection(); var statement = writer.createStatement()) {
                writer.setAutoCommit(false); statement.execute("UPDATE tags SET name='in-flight'");
                assertThatThrownBy(() -> DatabaseProviderTestFixtures.migrate(from, directory.resolve("locked.xml"), to, registry, true, Set.of()))
                        .hasMessage("SQLSTATE_55P03");
                writer.rollback();
            }
            assertThat(jdbc.queryForObject("SELECT name FROM tags", String.class)).isEqualTo("retained");
            DatabaseProviderTestFixtures.migrate(from, directory.resolve("retry.xml"), to, registry, true, Set.of());
        }
    }

    @Test @DisplayName("真实 PostgreSQL 迁出和迁入取消后释放锁与事务，允许重新迁移")
    void cancellationReleasesTransactions() throws Exception {
        try (var source = new PostgresTestDatabase(); var target = new PostgresTestDatabase()) {
            initialize(source);
            var jdbc = new JdbcTemplate(source.pool());
            jdbc.update("INSERT INTO tags(name) VALUES('retained')");
            var from = DatabaseProviderTestFixtures.resolve(source.properties(), "unused").migrationEndpoint();
            var to = DatabaseProviderTestFixtures.resolve(target.properties(), "unused").migrationEndpoint();
            var schema = CoreMigrationSchema.create(registry, true);
            top.sywyar.pixivdownload.plugin.api.database.migration.MigrationControl cancelled = (table, rows) -> {
                if (table.equals("tags")) throw new java.util.concurrent.CancellationException("MIGRATION_CANCELLED");
            };
            assertThatThrownBy(() -> from.provider().exportDatabase(from.target(),
                    new top.sywyar.pixivdownload.plugin.api.database.migration.MigrationRequest(schema,
                            directory.resolve("cancelled.xml"), Set.of()), cancelled))
                    .isInstanceOf(java.util.concurrent.CancellationException.class);
            jdbc.update("INSERT INTO tags(name) VALUES('after cancellation')");
            try (var exported = from.provider().exportDatabase(from.target(),
                    new top.sywyar.pixivdownload.plugin.api.database.migration.MigrationRequest(schema,
                            directory.resolve("retry.xml"), Set.of()), (table, rows) -> {})) {
                assertThatThrownBy(() -> {
                    try (var imported = to.provider().importDatabase(to.target(), schema, cancelled)) {
                        for (var table : schema.tables()) try (var rows = exported.rows(table.name())) {
                            imported.write(table.name(), rows);
                        }
                    }
                }).isInstanceOf(java.util.concurrent.CancellationException.class);
                try (var imported = to.provider().importDatabase(to.target(), schema, (table, rows) -> {})) {
                    for (var table : schema.tables()) try (var rows = exported.rows(table.name())) {
                        imported.write(table.name(), rows);
                    }
                    assertThat(imported.verify(exported.snapshot()).tables().get("tags").rows()).isEqualTo(2);
                    imported.commit();
                }
            }
            jdbc.update("INSERT INTO tags(name) VALUES('after release')");
            assertThat(new JdbcTemplate(target.pool()).queryForObject("SELECT COUNT(*) FROM tags", Long.class)).isEqualTo(2);
        }
    }
}
