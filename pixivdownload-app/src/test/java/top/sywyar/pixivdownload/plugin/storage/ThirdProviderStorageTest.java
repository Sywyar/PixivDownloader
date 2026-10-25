package top.sywyar.pixivdownload.plugin.storage;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import top.sywyar.pixivdownload.core.db.DatabaseTarget;
import top.sywyar.pixivdownload.core.work.importing.WorkFileImportRequest;
import top.sywyar.pixivdownload.core.work.model.WorkType;
import top.sywyar.pixivdownload.databasesqlite.SqliteDatabasePlugin;
import top.sywyar.pixivdownload.migration.database.*;
import top.sywyar.pixivdownload.plugin.api.database.*;
import top.sywyar.pixivdownload.plugin.api.database.migration.*;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopDashboardSource;
import top.sywyar.pixivdownload.plugin.api.plugin.*;
import top.sywyar.pixivdownload.plugin.api.storage.PluginDatabase;
import top.sywyar.pixivdownload.plugin.api.storage.relational.*;
import top.sywyar.pixivdownload.plugin.runtime.bootstrap.PluginEnabledSnapshot;
import top.sywyar.pixivdownload.plugin.runtime.bootstrap.database.DatabaseProviderSelection;
import top.sywyar.pixivdownload.plugin.runtime.context.*;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.*;
import top.sywyar.pixivdownload.plugin.runtime.discovery.*;
import top.sywyar.pixivdownload.plugin.runtime.status.PluginStatus;
import top.sywyar.pixivdownload.plugin.runtime.stream.PluginStreamRegistry;
import top.sywyar.pixivdownload.plugin.runtime.task.PluginRuntimeTaskRegistry;
import java.nio.file.Path;
import java.net.URLClassLoader;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@DisplayName("第三提供者复用真实插件与宿主装配合同")
class ThirdProviderStorageTest {
    @TempDir Path directory;
    @Test @DisplayName("第三提供者预置主库经标准初始化器和真实核心消费者执行，保持生成键、冲突及回滚")
    void mainConsumersUseIndependentDialect() throws Exception {
        var provider = new H2ContractProvider();
        var selected = new DatabaseProviderSelection.Result(provider.id(), provider.databaseId(), Optional.of(provider),
                DatabaseProviderSelection.Failure.NONE);
        var target = DatabaseTarget.inspect(selected, new DatabaseConnectionSettings(
                "contract:main_" + UUID.randomUUID().toString().replace("-", ""), "", "", directory));
        var registry = top.sywyar.pixivdownload.plugin.registry.schema.DatabaseSchemaRegistry.forBuiltInPlugins();
        var declaration = registry.storageSchema();
        try (var connection = target.openConnection()) {
            target.applySchema(connection, declaration, true);
        }
        try (var pool = target.openPool(2)) {
            var initializer = new top.sywyar.pixivdownload.core.db.schema.DatabaseInitializer(target,
                    new org.springframework.jdbc.core.JdbcTemplate(pool), registry.contributions(), registry.mergedSchema(),
                    top.sywyar.pixivdownload.i18n.TestI18nBeans.appMessages(), event -> {});
            initializer.initialize();
            assertThat(top.sywyar.pixivdownload.core.db.schema.DatabaseSchemaInspector
                    .compare(target, registry.mergedSchema()).matches()).isTrue();
            new top.sywyar.pixivdownload.maintenance.tasks.DatabaseOptimizeTask(pool, target)
                    .execute(new top.sywyar.pixivdownload.plugin.api.maintenance.MaintenanceContext("fixture", 1L));
            var transactions = new top.sywyar.pixivdownload.core.db.DatabaseConfig(null, null).transactionManager(pool, target);
            var database = new top.sywyar.pixivdownload.core.db.MainDatabase(pool, target, registry, transactions);
            var authors = new top.sywyar.pixivdownload.author.AuthorMapper(database);
            assertThat(authors.insertIfAbsent(9007199254740993L, "作\0者 🌸\0", 1700000000123L)).isOne();
            assertThat(authors.insertIfAbsent(9007199254740993L, "不覆盖", 1)).isZero();
            assertThat(authors.findById(9007199254740993L).name()).isEqualTo("作者 🌸");
            var collections = new top.sywyar.pixivdownload.collection.CollectionMapper(database);
            var collection = new top.sywyar.pixivdownload.collection.CollectionInsert();
            collection.setName("收藏"); collection.setCreatedTime(1700000000124L);
            collections.insert(collection);
            assertThat(collection.getId()).isPositive();
            assertThat(collections.addArtwork(collection.getId(), 9007199254740993L, 1700000000125L)).isOne();
            assertThat(collections.addArtwork(collection.getId(), 9007199254740993L, 1700000000126L)).isZero();
            var tx = new org.springframework.transaction.support.TransactionTemplate(transactions);
            assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
                authors.insertIfAbsent(42, "rollback", 1);
                collections.addArtwork(collection.getId(), 42, 2);
                throw new IllegalStateException("fixture");
            })).isInstanceOf(IllegalStateException.class);
            assertThat(authors.findById(42)).isNull();
            assertThat(collections.findById(collection.getId()).name()).isEqualTo("收藏");
            var pixiv = new top.sywyar.pixivdownload.core.db.PixivMapper(database);
            pixiv.ensureDefaultFileNameTemplate("fixture-default");
            pixiv.insertFileNameTemplateIfAbsent("fixture-custom");
            assertThat(pixiv.findFileNameTemplateId("fixture-custom")).isGreaterThan(1);
            pixiv.upsertTag("花", null); pixiv.upsertTag("花", "Flower"); pixiv.upsertTag("花", "不覆盖");
            assertThat(pixiv.findTagIdByName("花")).isPositive();
        }
    }

    @Test @DisplayName("第三提供者不支持事务 DDL 时拒绝且不提交既有事务")
    void unsupportedDdlDoesNotCommit() throws Exception {
        var provider = new H2ContractProvider();
        var target = provider.resolve(new DatabaseConnectionSettings(
                "contract:ddl_" + UUID.randomUUID().toString().replace("-", ""), "", "", directory));
        var schema = PrivateStorageSchema.merge(List.of(top.sywyar.pixivdownload.author.AuthorSchemaContribution.CONTRIBUTION));
        try (var connection = target.openConnection(DatabaseConnectionTarget.OpenMode.READ_WRITE)) {
            try (var statement = connection.createStatement()) { statement.execute("CREATE TABLE retained(id BIGINT)"); }
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) { statement.execute("INSERT INTO retained VALUES (1)"); }
            assertThatThrownBy(() -> target.initializeStorage(connection, schema))
                    .isInstanceOfSatisfying(java.sql.SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("0A000"));
            connection.rollback();
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT COUNT(*) FROM retained")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getLong(1)).isZero();
            }
            assertThat(target.readSchema(connection)).containsOnlyKeys("retained");
        }
    }
    @Test @DisplayName("第三提供者经真实设置入口列出、检查和保存，不受宿主引擎枚举限制")
    void settingsAcceptIndependentProvider() throws Exception {
        var provider = new H2ContractProvider();
        var selected = new DatabaseProviderSelection.Result(provider.id(), provider.databaseId(), Optional.of(provider),
                DatabaseProviderSelection.Failure.NONE);
        var file = new top.sywyar.pixivdownload.gui.config.ConfigFileEditor(directory.resolve("config.yaml")).asConfigFile();
        var draft = Map.of("database.provider", provider.id(), "database.jdbc-url",
                "contract:settings_" + UUID.randomUUID().toString().replace("-", ""));
        file.writeAll(draft);
        var service = new top.sywyar.pixivdownload.gui.config.DatabaseSettingsService(file, MockEnvironment::new,
                Optional::empty, () -> List.of(selected));
        assertThat(service.providers()).extracting(top.sywyar.pixivdownload.plugin.api.gui.DesktopDatabaseSettings.ProviderChoice::owner)
                .containsExactly(provider.id());
        assertThat(service.check(draft).outcome()).isEqualTo(
                top.sywyar.pixivdownload.plugin.api.gui.DesktopDatabaseSettings.Outcome.CONNECTED);
        String dataKey = top.sywyar.pixivdownload.config.RuntimeFiles.DATA_DIR_PROPERTY;
        String stateKey = top.sywyar.pixivdownload.config.RuntimeFiles.STATE_DIR_PROPERTY;
        String oldData = System.getProperty(dataKey), oldState = System.getProperty(stateKey);
        try {
            System.setProperty(dataKey, directory.resolve("data").toString());
            System.setProperty(stateKey, directory.resolve("state").toString());
            file.writeAll(service.prepare(draft));
            assertThat(file.readAll(Set.of("database.provider", "database.engine")))
                    .containsEntry("database.provider", provider.id()).containsEntry("database.engine", provider.databaseId());
        } finally {
            if (oldData == null) System.clearProperty(dataKey); else System.setProperty(dataKey, oldData);
            if (oldState == null) System.clearProperty(stateKey); else System.setProperty(stateKey, oldState);
        }
    }
    @Test @DisplayName("未知方言经选择、child context、实际消费者和双向迁移运行，无需业务引擎分支")
    void runsUnchangedConsumerAndMigration() throws Exception {
        var provider = new H2ContractProvider();
        var descriptor = new PluginDescriptor(provider.id(), provider.id(), "7.2.0", VersionRequirement.unspecified(), List.of(),
                provider.getClass().getName(), null, "fixture.name", "fixture.summary", "database", "neutral",
                PluginKind.FEATURE, List.of(), PluginLifecyclePolicy.PROCESS_RESTART, PluginExecutionMode.HOST_PROCESS_FULL_TRUST);
        var inventory = new PluginInventory(List.of(new PluginInstallation(descriptor, PluginStatus.STARTED,
                getClass().getClassLoader(), provider)), List.of());
        var selected = DatabaseProviderSelection.select(provider.id(), "", inventory, PluginEnabledSnapshot.empty());
        assertThat(selected.available()).isTrue();
        var settings = new DatabaseConnectionSettings("contract:test_" + UUID.randomUUID().toString().replace("-", ""), "", "", directory.resolve("unused"));
        var target = DatabaseTarget.inspect(selected, settings);
        Path classes = Path.of(System.getProperty("user.dir")).resolve("../pixivdownload-plugin-pixiv-batch-downloader-import/target/classes").normalize();
        assertThat(classes).isDirectory();
        try (var loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, getClass().getClassLoader());
             var main = target.openPool(2);
             var parent = new AnnotationConfigApplicationContext()) {
            String packageName = "top.sywyar.pixivdownload.pixivbatchdownloaderimport.";
            var entry = (PixivPluginProvider) loader.loadClass(packageName + "PixivBatchDownloaderImportPlugin").getConstructor().newInstance();
            var feature = entry.featurePlugin();
            var statisticsClass = loader.loadClass(packageName + "PixivBatchDownloaderImportStatistics");
            var record = statisticsClass.getDeclaredMethod("recordImported", WorkFileImportRequest.class);
            record.setAccessible(true);
            var privateSettings = new DatabasePrivateStorageSettings(feature.id(), directory.resolve("source"), "contract-install", "fixture-password");
            // H2 不支持事务 DDL，预置夹具结构；正式初始化只核验，并测试缺失结构时明确拒绝。
            var privateTarget = target.privateStorage(privateSettings);
            try (var connection = privateTarget.openConnection(DatabaseConnectionTarget.OpenMode.READ_WRITE)) {
                privateTarget.initializeStorage(connection, PrivateStorageSchema.merge(feature.privateSchema()));
            }
            var factory = new PluginApplicationContextFactory(PluginContextPropertySourceProvider.EMPTY,
                    new PluginStreamRegistry(), new PluginRuntimeTaskRegistry(), null, null, module -> {
                        var database = new AppPluginDatabase(module.sourcePluginId(), target, main, () -> privateSettings);
                        try { database.initialize(module.privateSchema(), module.storageMigrations()); return database; }
                        catch (Exception failure) { database.close(); throw new IllegalStateException(failure); }
                    });
            parent.refresh();
            try (var child = factory.create(parent, new PluginContextModule(feature.id(), loader, List.of(),
                    feature.privateSchema(), feature.privateStorageMigrations()))) {
                var database = child.getBean(PluginDatabase.class);
                var statistics = (DesktopDashboardSource) statisticsClass.getConstructor(PluginDatabase.class).newInstance(database);
                var request = new WorkFileImportRequest(WorkType.ARTWORK, 9007199254740993L, "fixture", 1, 0, false,
                        1L, null, null, null, null, List.of(), directory, List.of(directory.resolve("page.png")), null);
                record.invoke(statistics, request);
                record.invoke(statistics, request);
                assertThat(statistics.snapshot().cards().get(0).primaryValue().fallback()).isEqualTo("1");

                var environment = new MockEnvironment().withProperty("database.provider", provider.id())
                        .withProperty("database.jdbc-url", settings.jdbcUrl());
                var endpoint = MigrationEndpoint.resolve(List.of(selected), environment).privateStorage(privateSettings);
                var sqlite = new SqliteDatabasePlugin();
                var sqliteSettings = new DatabasePrivateStorageSettings(feature.id(), directory.resolve("destination"), "contract-install", "fixture-password");
                var sqliteEndpoint = new MigrationEndpoint(sqlite.id(), sqlite,
                        sqlite.resolve(new DatabaseConnectionSettings("", "", "", directory.resolve("sqlite-main.db"))))
                        .privateStorage(sqliteSettings);
                var schema = PrivateStorageSchema.merge(feature.privateSchema());
                migrate(endpoint, sqliteEndpoint, schema, "h2-source.sql");
                try (var connection = sqliteEndpoint.target().openConnection(DatabaseConnectionTarget.OpenMode.EXISTING);
                     var session = sqliteEndpoint.target().storageSession(connection, schema)) {
                    assertThat(session.query(Query.count("imported_works", null)).get(0).get("count")).isEqualTo(1L);
                }
                var backSettings = new DatabasePrivateStorageSettings("another-owner", directory.resolve("back"), "contract-install", "fixture-password");
                var back = MigrationEndpoint.resolve(List.of(selected), environment).privateStorage(backSettings);
                migrate(sqliteEndpoint, back, schema, "sqlite-source.db");
                try (var connection = back.target().openConnection(DatabaseConnectionTarget.OpenMode.EXISTING);
                     var session = back.target().storageSession(connection, schema)) {
                    assertThat(session.query(Query.count("imported_works", null)).get(0).get("count")).isEqualTo(1L);
                }
                assertThatThrownBy(() -> migrate(sqliteEndpoint, back, schema, "repeat.db"))
                        .isInstanceOf(ProviderDatabaseMigration.MigrationFailure.class);
            }
        }
    }
    private void migrate(MigrationEndpoint source, MigrationEndpoint destination, MigrationSchema schema, String backup) throws Exception {
        var report = ProviderDatabaseMigration.migrate(List.of(new ProviderDatabaseMigration.Store("private:fixture", source, destination,
                new MigrationRequest(schema, directory.resolve(backup), Set.of()))), reportValue -> {}, (store, snapshot) -> {}, (table, count) -> {});
        assertThat(report.status()).isEqualTo("DATA_VERIFIED");
    }
}
