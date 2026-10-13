package top.sywyar.pixivdownload.core.db.schema;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.config.InstallIdentity;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures;
import top.sywyar.pixivdownload.migration.database.MigrationEndpoint;
import top.sywyar.pixivdownload.plugin.api.database.*;
import top.sywyar.pixivdownload.plugin.api.database.migration.*;
import top.sywyar.pixivdownload.plugin.api.schema.*;
import top.sywyar.pixivdownload.plugin.storage.PluginDatabaseFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("私库迁移凭据绑定可信源提供者、数据和目标")
class PrivateDatabaseMigrationStateTest {
    @TempDir Path directory;

    @Test
    @DisplayName("拒绝缺失、损坏或过期收据，源提供者缺席时不能借用其它驱动")
    void rejectsMissingOrStaleReceipt() throws Exception {
        String owner = "fixture-owner";
        Path source = Files.createDirectories(directory.resolve(owner)).resolve("plugin.db");
        var sourceEndpoint = DatabaseProviderTestFixtures.sqlite(source).migrationEndpoint();
        try (var connection = sourceEndpoint.target().openConnection(DatabaseConnectionTarget.OpenMode.READ_WRITE);
             var sql = connection.createStatement()) { sql.execute("CREATE TABLE facts(id INTEGER PRIMARY KEY)"); }
        Path config = directory.resolve("source.yaml");
        Files.writeString(config, "database.provider: fixture-file\ndatabase.jdbc-url: 'jdbc:sqlite:"
                + directory.resolve("main.db").toString().replace("'", "''") + "'\n");
        var targetProvider = mock(DatabaseProvider.class);
        var targetConnection = mock(DatabaseConnectionTarget.class);
        var privateConnection = mock(DatabaseConnectionTarget.class);
        when(targetConnection.description()).thenReturn("neutral:main");
        when(privateConnection.description()).thenReturn("neutral:fixture-owner");
        var target = new MigrationEndpoint("fixture-server", targetProvider, targetConnection);
        var settings = new DatabasePrivateStorageSettings(owner, directory.resolve(owner), "fixture-installation", "test-only");
        when(targetConnection.privateStorage(settings)).thenReturn(privateConnection);
        Path receipt = directory.resolve("migration/private/" + owner + "/verified.json");
        var schema = new MigrationSchema(1, List.of(new TableSpec("facts", List.of(
                new ColumnSpec("id", "INTEGER", false, null, 1)), List.of())), List.of(), List.of());
        try (var paths = mockStatic(RuntimeFiles.class); var identity = mockStatic(InstallIdentity.class);
             var factory = mockStatic(PluginDatabaseFactory.class)) {
            paths.when(RuntimeFiles::dataDirectory).thenReturn(directory);
            paths.when(() -> RuntimeFiles.normalizeRootFolder(anyString())).thenCallRealMethod();
            paths.when(RuntimeFiles::databaseMigrationDirectory).thenReturn(directory.resolve("migration"));
            paths.when(() -> RuntimeFiles.privateDatabaseMigrationReceipt(owner)).thenReturn(receipt);
            identity.when(InstallIdentity::get).thenReturn(settings.installationId());
            factory.when(() -> PluginDatabaseFactory.privateSettings(eq(owner), any())).thenReturn(settings);
            var providers = DatabaseProviderTestFixtures.providers();
            assertThatThrownBy(() -> PrivateDatabaseMigrationState.requireReady(target, providers)).isInstanceOf(Exception.class);
            var configIdentity = PrivateDatabaseMigrationState.sourceConfiguration(config, directory);
            try (var exported = sourceEndpoint.provider().exportDatabase(sourceEndpoint.target(),
                    new MigrationRequest(schema, directory.resolve("backup.db"), Set.of()), (table, rows) -> {})) {
                PrivateDatabaseMigrationState.recordVerified(owner, sourceEndpoint, target,
                        new MigrationEndpoint(target.owner(), target.provider(), privateConnection), configIdentity, exported.snapshot());
            }
            PrivateDatabaseMigrationState.requireReady(target, providers);
            String saved = Files.readString(receipt);
            for (String invalid : List.of("null", "{", " ".repeat(16 * 1024 + 1), saved.replace("\"formatVersion\":1", "\"formatVersion\":99"))) {
                Files.writeString(receipt, invalid);
                assertThatThrownBy(() -> PrivateDatabaseMigrationState.requireReady(target, providers)).isInstanceOf(Exception.class);
            }
            Files.writeString(receipt, saved);
            assertThatThrownBy(() -> PrivateDatabaseMigrationState.requireReady(target, List.of(providers.get(1))))
                    .hasMessage("DATABASE_PROVIDER_UNAVAILABLE");
            when(targetConnection.description()).thenReturn("neutral:different");
            assertThatThrownBy(() -> PrivateDatabaseMigrationState.requireReady(target, providers))
                    .hasMessage("DATABASE_PRIVATE_MIGRATION_REQUIRED");
            when(targetConnection.description()).thenReturn("neutral:main");
            try (var connection = sourceEndpoint.target().openConnection(DatabaseConnectionTarget.OpenMode.EXISTING);
                 var sql = connection.createStatement()) { sql.execute("INSERT INTO facts VALUES(9)"); }
            assertThatThrownBy(() -> PrivateDatabaseMigrationState.requireReady(target, providers)).hasMessage("MIGRATION_SOURCE_CHANGED");
            assertThat(Files.readString(config)).contains("database.provider: fixture-file");
        }
    }

    @Test
    @DisplayName("没有保留私库时检查不创建数据库")
    void emptyDataDoesNotRequireMigration() throws Exception {
        var target = DatabaseProviderTestFixtures.sqlite(directory.resolve("main.db")).migrationEndpoint();
        try (var paths = mockStatic(RuntimeFiles.class); var identity = mockStatic(InstallIdentity.class)) {
            paths.when(RuntimeFiles::dataDirectory).thenReturn(directory);
            paths.when(RuntimeFiles::databaseMigrationDirectory).thenReturn(directory.resolve("migration"));
            identity.when(InstallIdentity::get).thenReturn("fixture-installation");
            PrivateDatabaseMigrationState.requireReady(target, DatabaseProviderTestFixtures.providers());
            assertThat(directory.resolve("main.db")).doesNotExist();
        }
    }
}
