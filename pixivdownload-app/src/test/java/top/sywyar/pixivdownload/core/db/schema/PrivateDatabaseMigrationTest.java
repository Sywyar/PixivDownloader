package top.sywyar.pixivdownload.core.db.schema;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures;
import top.sywyar.pixivdownload.core.db.PostgresTestDatabase;
import top.sywyar.pixivdownload.plugin.api.database.DatabaseConnectionTarget.OpenMode;
import top.sywyar.pixivdownload.plugin.api.database.DatabasePrivateStorageSettings;
import top.sywyar.pixivdownload.plugin.api.schema.*;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.registry.schema.DatabaseSchemaRegistry;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@DisplayName("受限 owner 私库真实迁移")
@EnabledIfEnvironmentVariable(named = "PIXIV_TEST_POSTGRES_URL", matches = ".+")
class PrivateDatabaseMigrationTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("短长路径私库保留长整数和 Unicode，迁移后隔离主库且重复迁移不覆盖")
    void migratesPrivateDataWithRestrictedRole(boolean longPath) throws Exception {
        Path sourceDirectory = directory;
        if (longPath) {
            sourceDirectory = sourceDirectory.resolve("私有 空格%#&mode=ro");
            while (sourceDirectory.toString().length() < 320) sourceDirectory = sourceDirectory.resolve("p".repeat(70));
        }
        Path source = sourceDirectory.resolve("private.db");
        Path backup = sourceDirectory.resolve("backup.db");
        var registry = new DatabaseSchemaRegistry(new PluginRegistry(List.of()));
        registry.register("fixture-owner", new SchemaContribution(List.of(new TableSpec("facts", List.of(
                new ColumnSpec("kind", "TEXT", true, null, 1),
                new ColumnSpec("id", "INTEGER", true, null, 2),
                new ColumnSpec("body", "TEXT", false, null, 0)), List.of())), List.of(), List.of()));
        var input = DatabaseProviderTestFixtures.sqlite(source);
        try (var connection = input.openConnection(); var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE facts(kind TEXT NOT NULL,id INTEGER NOT NULL,body TEXT,PRIMARY KEY(kind,id))");
            sql.execute("INSERT INTO facts VALUES('NOVEL',9007199254740993,'before WAL update')");
        }
        try (var retainedSource = input.openConnection(OpenMode.EXISTING);
             var database = new PostgresTestDatabase()) {
            try (var sql = retainedSource.createStatement()) {
                sql.execute("UPDATE facts SET body='私有 🌸' WHERE id=9007199254740993");
            }
            assertThat(java.nio.file.Files.size(Path.of(source + "-wal"))).isPositive();
            var target = DatabaseProviderTestFixtures.resolve(database.properties(), "unused");
            var privateEndpoint = target.migrationEndpoint().privateStorage(new DatabasePrivateStorageSettings(
                    "fixture-owner", directory, UUID.randomUUID().toString(), "fixture-private-password"));
            var privateTarget = privateEndpoint.target();
            String privateSchema;
            try (var connection = privateTarget.openConnection(OpenMode.READ_WRITE)) {
                privateSchema = connection.getSchema();
            }
            try {
                var result = DatabaseProviderTestFixtures.migrate(input.migrationEndpoint(), backup,
                        privateEndpoint, registry, false, java.util.Set.of());
                assertThat(result.verification().tables().get("facts").rows()).isOne();
                try (var connection = privateTarget.openConnection(OpenMode.EXISTING); var sql = connection.createStatement();
                     var rows = sql.executeQuery("SELECT id,body FROM facts")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getLong(1)).isEqualTo(9007199254740993L);
                    assertThat(rows.getString(2)).isEqualTo("私有 🌸");
                    String mainSchema;
                    try (var admin = database.pool().getConnection()) { mainSchema = admin.getSchema(); }
                    new org.springframework.jdbc.core.JdbcTemplate(database.pool()).execute("CREATE TABLE main_secret(value TEXT)");
                    assertThatThrownBy(() -> sql.executeQuery("SELECT * FROM " + mainSchema + ".main_secret"))
                            .isInstanceOf(java.sql.SQLException.class);
                }
                var restoredMain = DatabaseProviderTestFixtures.sqlite(sourceDirectory.resolve("restored-main.db")).migrationEndpoint();
                var restoredPrivate = restoredMain.privateStorage(new DatabasePrivateStorageSettings("fixture-owner",
                        sourceDirectory.resolve("restored-owner"), "fixture-installation", "unused"));
                var reversed = DatabaseProviderTestFixtures.migrate(privateEndpoint, sourceDirectory.resolve("postgres-backup.xml"),
                        restoredPrivate, registry, false, java.util.Set.of());
                assertThat(reversed.verification().tables().get("facts").rows()).isOne();
                try (var connection = restoredPrivate.target().openConnection(OpenMode.EXISTING); var sql = connection.createStatement();
                     var rows = sql.executeQuery("SELECT id,body FROM facts")) {
                    assertThat(rows.next()).isTrue(); assertThat(rows.getLong(1)).isEqualTo(9007199254740993L);
                    assertThat(rows.getString(2)).isEqualTo("私有 🌸");
                }
                assertThatThrownBy(() -> DatabaseProviderTestFixtures.migrate(input.migrationEndpoint(), directory.resolve("retry.db"),
                        privateEndpoint, registry, false, java.util.Set.of()))
                        .isInstanceOf(IllegalStateException.class);
                try (var connection = input.openConnection(OpenMode.EXISTING); var sql = connection.createStatement();
                     var rows = sql.executeQuery("SELECT body FROM facts")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString(1)).isEqualTo("私有 🌸");
                }
            } finally {
                try (var connection = database.pool().getConnection(); var sql = connection.createStatement()) {
                    sql.execute("DROP SCHEMA " + privateSchema + " CASCADE");
                    sql.execute("DROP ROLE " + privateSchema);
                }
            }
        }
    }
    @Test
    @DisplayName("先完成的 owner 在另一私库迁移期间被写入时拒绝签发迁移收据")
    void rejectsSourceWritesBetweenOwnerMigrations() throws Exception {
        var registry = new DatabaseSchemaRegistry(new PluginRegistry(List.of()));
        registry.register("fixture-owner", new SchemaContribution(List.of(new TableSpec("facts", List.of(
                new ColumnSpec("id", "INTEGER", true, null, 1)), List.of())), List.of(), List.of()));
        Path first = directory.resolve("first.db");
        Path second = directory.resolve("second.db");
        var inputs = List.of(DatabaseProviderTestFixtures.sqlite(first), DatabaseProviderTestFixtures.sqlite(second));
        for (var input : inputs) {
            try (var connection = input.openConnection(); var sql = connection.createStatement()) {
                sql.execute("CREATE TABLE facts(id INTEGER PRIMARY KEY NOT NULL)");
                sql.execute("INSERT INTO facts VALUES(1)");
            }
        }
        try (var database = new PostgresTestDatabase()) {
            var target = DatabaseProviderTestFixtures.resolve(database.properties(), "unused");
            String installation = UUID.randomUUID().toString();
            var targets = List.of(
                    target.migrationEndpoint().privateStorage(new DatabasePrivateStorageSettings("first-owner", directory, installation, "fixture-password")),
                    target.migrationEndpoint().privateStorage(new DatabasePrivateStorageSettings("second-owner", directory, installation, "fixture-password")));
            var schemas = new java.util.ArrayList<String>();
            try {
                for (var privateTarget : targets) {
                    try (var connection = privateTarget.target().openConnection(OpenMode.READ_WRITE)) { schemas.add(connection.getSchema()); }
                }
                var copied = DatabaseProviderTestFixtures.migrate(inputs.get(0).migrationEndpoint(), directory.resolve("first-backup.db"),
                        targets.get(0), registry, false, java.util.Set.of());
                inputs.get(0).migrationEndpoint().provider().verifySource(inputs.get(0).migrationEndpoint().target(), copied.sourceRevision());
                try (var connection = inputs.get(0).openConnection(OpenMode.EXISTING); var sql = connection.createStatement()) {
                    sql.execute("INSERT INTO facts VALUES(2)");
                }
                DatabaseProviderTestFixtures.migrate(inputs.get(1).migrationEndpoint(), directory.resolve("second-backup.db"),
                        targets.get(1), registry, false, java.util.Set.of());
                assertThatThrownBy(() -> inputs.get(0).migrationEndpoint().provider().verifySource(inputs.get(0).migrationEndpoint().target(),
                        copied.sourceRevision())).hasMessage("MIGRATION_SOURCE_CHANGED");
                try (var connection = targets.get(0).target().openConnection(OpenMode.EXISTING); var sql = connection.createStatement();
                     var rows = sql.executeQuery("SELECT COUNT(*) FROM facts")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getLong(1)).isOne();
                }
                try (var connection = inputs.get(0).openConnection(OpenMode.EXISTING); var sql = connection.createStatement();
                     var rows = sql.executeQuery("SELECT COUNT(*) FROM facts")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getLong(1)).isEqualTo(2);
                }
            } finally {
                try (var connection = database.pool().getConnection(); var sql = connection.createStatement()) {
                    for (String schema : schemas) {
                        sql.execute("DROP SCHEMA " + schema + " CASCADE");
                        sql.execute("DROP ROLE " + schema);
                    }
                }
            }
        }
    }

}
