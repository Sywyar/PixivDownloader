package top.sywyar.pixivdownload.core.db;

import top.sywyar.pixivdownload.plugin.api.database.DatabaseConnectionSettings;
import top.sywyar.pixivdownload.plugin.api.database.DatabaseProvider;
import top.sywyar.pixivdownload.plugin.runtime.bootstrap.database.DatabaseProviderBinding;
import top.sywyar.pixivdownload.plugin.runtime.bootstrap.database.DatabaseProviderSelection;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** 测试直接使用当前 reactor 的 provider；生产入口仍须经过可信插件发现。 */
public final class DatabaseProviderTestFixtures {
    private DatabaseProviderTestFixtures() {}

    public static DatabaseTarget target(javax.sql.DataSource source) {
        if (UnavailableDataSource.isUnavailable(source)) return DatabaseTarget.unavailable();
        try (var connection = source.getConnection()) { return target(connection); }
        catch (java.sql.SQLException failure) { throw new IllegalStateException(failure); }
    }

    public static DatabaseTarget target(java.sql.Connection connection) throws java.sql.SQLException {
        var metadata = connection.getMetaData();
        var engine = DatabaseEngine.detect(connection).databaseId();
        var selected = providers().stream().filter(p -> p.databaseId().equals(engine)).findFirst().orElseThrow();
        return DatabaseTarget.inspect(selected, new DatabaseConnectionSettings(
                selected.databaseId().equals("sqlite") ? "" : metadata.getURL().split("\\?", 2)[0] + "?currentSchema=" + connection.getSchema(),
                java.util.Objects.toString(metadata.getUserName(), ""), "",
                Path.of("target", "neutral-session-fixture.db").toAbsolutePath()));
    }

    public static top.sywyar.pixivdownload.core.db.schema.DatabaseInitializer initializer(
            org.springframework.jdbc.core.JdbcTemplate jdbc,
            List<top.sywyar.pixivdownload.plugin.api.schema.SchemaContribution> contributions,
            top.sywyar.pixivdownload.core.db.schema.ManagedDatabaseSchema.DatabaseSchema schema,
            top.sywyar.pixivdownload.i18n.AppMessages messages, org.springframework.context.ApplicationEventPublisher events) {
        return new top.sywyar.pixivdownload.core.db.schema.DatabaseInitializer(target(jdbc.getDataSource()), jdbc,
                contributions, schema, messages, events);
    }

    public static top.sywyar.pixivdownload.core.db.schema.DatabaseInitializer offline(java.sql.Connection connection,
            List<top.sywyar.pixivdownload.plugin.api.schema.SchemaContribution> contributions,
            top.sywyar.pixivdownload.core.db.schema.ManagedDatabaseSchema.DatabaseSchema schema) throws java.sql.SQLException {
        return top.sywyar.pixivdownload.core.db.schema.DatabaseInitializer.offline(connection, target(connection), contributions, schema);
    }

    public static void prepareOffline(java.sql.Connection connection) throws java.sql.SQLException {
        top.sywyar.pixivdownload.core.db.schema.DatabaseInitializer.prepareOffline(connection, target(connection));
    }

    public static top.sywyar.pixivdownload.core.db.schema.DatabaseSchemaInspector.SchemaComparison compare(
            java.sql.Connection connection, top.sywyar.pixivdownload.core.db.schema.ManagedDatabaseSchema.DatabaseSchema schema)
            throws java.sql.SQLException {
        return top.sywyar.pixivdownload.core.db.schema.DatabaseSchemaInspector.compare(connection, target(connection), schema);
    }
    public static top.sywyar.pixivdownload.core.db.schema.DatabaseSchemaInspector.SchemaComparison compare(
            DatabaseTarget target, top.sywyar.pixivdownload.core.db.schema.ManagedDatabaseSchema.DatabaseSchema schema)
            throws java.sql.SQLException {
        return top.sywyar.pixivdownload.core.db.schema.DatabaseSchemaInspector.compare(target, schema);
    }

    public static void initializePathPrefixes(javax.sql.DataSource source) {
        var table = top.sywyar.pixivdownload.core.db.schema.contribution.CoreSchemaContribution.CONTRIBUTION
                .tables().stream().filter(value -> value.name().equals("path_prefixes")).findFirst().orElseThrow();
        try (var connection = source.getConnection()) {
            var target = new top.sywyar.pixivdownload.databasesqlite.SqliteDatabasePlugin().resolve(
                    new DatabaseConnectionSettings("", "", "", Path.of("target", "fixture.db").toAbsolutePath()));
            target.initializeStorage(connection, new top.sywyar.pixivdownload.plugin.api.database.migration.MigrationSchema(
                    top.sywyar.pixivdownload.plugin.api.database.migration.MigrationSnapshot.FORMAT_VERSION,
                    List.of(table), List.of(), List.of()));
        } catch (java.sql.SQLException failure) { throw new IllegalStateException(failure); }
    }

    public static MainDatabase main(javax.sql.DataSource source) {
        DatabaseTarget target;
        if (UnavailableDataSource.isUnavailable(source)) target = DatabaseTarget.unavailable();
        else try (var connection = source.getConnection()) {
            var metadata = connection.getMetaData();
            var engine = DatabaseEngine.detect(connection).databaseId();
            var selected = providers().stream().filter(p -> p.databaseId().equals(engine)).findFirst().orElseThrow();
            target = DatabaseTarget.inspect(selected, new DatabaseConnectionSettings(
                    selected.databaseId().equals("sqlite") ? "" : metadata.getURL().split("\\?", 2)[0] + "?currentSchema=" + connection.getSchema(),
                    java.util.Objects.toString(metadata.getUserName(), ""), "",
                    Path.of("target", "neutral-session-fixture.db").toAbsolutePath()));
        } catch (java.sql.SQLException failure) { throw new IllegalStateException(failure); }
        return new MainDatabase(source, target,
                top.sywyar.pixivdownload.plugin.registry.schema.DatabaseSchemaRegistry.forBuiltInPlugins(),
                new DatabaseConfig(null, null, null).transactionManager(source, target));
    }

    public static List<DatabaseProviderSelection.Result> providers() {
        return List.of(provider(new top.sywyar.pixivdownload.databasesqlite.SqliteDatabasePlugin(), "fixture-file"),
                provider(new top.sywyar.pixivdownload.databasepostgresql.PostgresDatabasePlugin(), "fixture-server"));
    }

    private static DatabaseProviderSelection.Result provider(DatabaseProvider provider, String owner) {
        return new DatabaseProviderSelection.Result(owner, provider.databaseId(), Optional.of(provider),
                DatabaseProviderSelection.Failure.NONE);
    }

    public static DatabaseTarget sqlite(Path file) {
        return DatabaseTarget.fromBinding(DatabaseProviderBinding.resolve(providers().get(0),
                () -> new DatabaseConnectionSettings("jdbc:sqlite:" + file, "", "", file.getParent()), java.util.function.UnaryOperator.identity()));
    }

    public static DatabaseTarget resolve(DatabasePoolProperties config, String root) throws java.io.IOException {
        var engine = DatabaseEngine.parse(config.getEngine());
        var selected = providers().stream().filter(value -> value.databaseId().equals(engine.databaseId())).findFirst().orElseThrow();
        var settings = top.sywyar.pixivdownload.core.db.provider.DatabaseBootstrap.settings(config.getJdbcUrl(),
                config.getUsername(), config.getPassword(), root);
        DatabaseTarget.inspect(selected, settings);
        try { selected.provider().orElseThrow().prepareStorage(settings); }
        catch (java.sql.SQLException failure) { throw new java.io.IOException(failure); }
        return DatabaseTarget.inspect(selected, settings);
    }

    public static DatabaseTarget fromConfiguration(String... args) throws java.io.IOException {
        var environment = DatabaseTarget.configurationEnvironment(args);
        var config = org.springframework.boot.context.properties.bind.Binder.get(environment)
                .bind("database", DatabasePoolProperties.class).orElseGet(DatabasePoolProperties::new);
        return resolve(config, environment.getProperty("download.root-folder", "pixiv-download"));
    }

    public static java.sql.Connection openConnection(Path file) throws java.sql.SQLException {
        return sqlite(file).openConnection();
    }

    public static java.sql.Connection openExisting(Path file) throws java.sql.SQLException {
        return sqlite(file).openConnection(top.sywyar.pixivdownload.plugin.api.database.DatabaseConnectionTarget.OpenMode.EXISTING);
    }

    public static com.zaxxer.hikari.HikariDataSource openPool(Path file, int maximum) throws java.io.IOException {
        return sqlite(file).openPool(maximum);
    }

    public static top.sywyar.pixivdownload.plugin.registry.PluginRegistry registry(DatabaseTarget target) {
        var feature = org.mockito.Mockito.mock(top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin.class);
        String owner = target.providerOwner();
        org.mockito.Mockito.when(feature.id()).thenReturn(owner);
        return new top.sywyar.pixivdownload.plugin.registry.PluginRegistry(List.of(feature));
    }
    public static top.sywyar.pixivdownload.plugin.runtime.bootstrap.PluginBootstrapSession session() {
        var session = org.mockito.Mockito.mock(top.sywyar.pixivdownload.plugin.runtime.bootstrap.PluginBootstrapSession.class);
        org.mockito.Mockito.when(session.databaseProviders()).thenReturn(providers());
        return session;
    }

    public static top.sywyar.pixivdownload.plugin.api.database.migration.MigrationSnapshot migrate(
            DatabaseTarget source, Path backup, DatabaseTarget target,
            top.sywyar.pixivdownload.plugin.registry.schema.DatabaseSchemaRegistry registry,
            boolean main, java.util.Set<String> rebuild) throws Exception {
        return migrate(source.migrationEndpoint(), backup, target.migrationEndpoint(), registry, main, rebuild);
    }

    public static top.sywyar.pixivdownload.plugin.api.database.migration.MigrationSnapshot migrate(
            top.sywyar.pixivdownload.migration.database.MigrationEndpoint source, Path backup,
            top.sywyar.pixivdownload.migration.database.MigrationEndpoint target,
            top.sywyar.pixivdownload.plugin.registry.schema.DatabaseSchemaRegistry registry,
            boolean main, java.util.Set<String> rebuild) throws Exception {
        var request = new top.sywyar.pixivdownload.plugin.api.database.migration.MigrationRequest(
                top.sywyar.pixivdownload.core.db.schema.CoreMigrationSchema.create(registry, main), backup, rebuild);
        var store = new top.sywyar.pixivdownload.migration.database.ProviderDatabaseMigration.Store(
                main ? "main" : "fixture-owner", source, target, request);
        return top.sywyar.pixivdownload.migration.database.ProviderDatabaseMigration.migrate(List.of(store),
                report -> {}, (completed, snapshot) -> {}, (table, rows) -> {}).stores().get(store.owner()).snapshot();
    }

}
