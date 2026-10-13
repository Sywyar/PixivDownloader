package top.sywyar.pixivdownload.databasesqlite;

import top.sywyar.pixivdownload.plugin.api.database.DatabaseConnectionSettings;
import top.sywyar.pixivdownload.plugin.api.database.DatabaseConnectionField;
import top.sywyar.pixivdownload.plugin.api.database.DatabaseConnectionTarget;
import top.sywyar.pixivdownload.plugin.api.database.DatabaseProvider;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.api.web.I18nContribution;

import java.sql.SQLException;
import java.util.List;
import top.sywyar.pixivdownload.plugin.api.database.migration.*;

/** SQLite 驱动与连接语义归属于本外置插件。 */
public final class SqliteDatabasePlugin implements PixivFeaturePlugin, DatabaseProvider {
    @Override public String id() { return "database-sqlite"; }
    @Override public String databaseId() { return "sqlite"; }
    @Override public List<DatabaseConnectionField> connectionFields() { return List.of(); }
    @Override public void prepareStorage(DatabaseConnectionSettings settings) throws SQLException {
        try { SqliteStorageLocation.prepare(settings); }
        catch (java.io.IOException failure) { throw new SQLException("DATABASE_STORAGE_PREPARATION_FAILED", "08001"); }
    }
    @Override public DatabaseConnectionSettings localFileSettings(DatabaseConnectionSettings settings, java.nio.file.Path file) {
        return new DatabaseConnectionSettings("jdbc:sqlite:" + file.toAbsolutePath().normalize(), "", "", settings.dataDirectory());
    }
    @Override public DatabaseConnectionSettings normalizeConfiguration(DatabaseConnectionSettings settings, boolean selectionChanged) {
        String url = settings.jdbcUrl();
        if ((selectionChanged || url.startsWith("jdbc:")) && !url.startsWith("jdbc:sqlite:")) url = "";
        return new DatabaseConnectionSettings(url, "", "", settings.dataDirectory(), settings.legacyDirectories());
    }
    @Override public String displayName() { return "plugin.name"; }
    @Override public String description() { return "plugin.summary"; }
    @Override public String iconKey() { return "database"; }
    @Override public String colorToken() { return "blue"; }
    @Override public PluginKind kind() { return PluginKind.FEATURE; }
    @Override public List<I18nContribution> i18n() {
        return List.of(new I18nContribution("database-sqlite", "i18n.web.database-sqlite"));
    }
    @Override public DatabaseConnectionTarget resolve(DatabaseConnectionSettings settings) throws SQLException {
        return new SqliteConnectionTarget(settings);
    }
    @Override public java.util.Set<String> privateDatabaseOwners(DatabaseConnectionTarget target,
            java.nio.file.Path directory, String installation) throws Exception {
        requireTarget(target);
        return SqliteMigration.owners(directory);
    }
    @Override public java.util.Map<String, DatabaseConnectionTarget> retainedLocalPrivateDatabases(
            java.nio.file.Path directory, String installation) throws Exception {
        var result = new java.util.LinkedHashMap<String, DatabaseConnectionTarget>();
        for (String owner : SqliteMigration.owners(directory)) result.put(owner,
                new SqliteConnectionTarget(directory.resolve(owner).resolve("plugin.db")));
        return java.util.Map.copyOf(result);
    }
    @Override public DatabaseExportSession exportDatabase(DatabaseConnectionTarget source, MigrationRequest request,
            MigrationControl control) throws Exception {
        requireTarget(source);
        return SqliteMigration.exportDatabase(source, request, control);
    }
    @Override public Inspection inspectMigration(DatabaseConnectionTarget target, MigrationSchema schema, boolean source) throws Exception {
        requireTarget(target);
        return SqliteMigration.inspect(target, schema, source);
    }
    @Override public void verifyImported(DatabaseConnectionTarget target, MigrationSnapshot snapshot, MigrationControl control) throws Exception {
        requireTarget(target);
        SqliteMigration.verifyImported(target, snapshot, control);
    }
    @Override public String inspectReplacement(DatabaseConnectionTarget target, MigrationSchema schema) throws Exception {
        requireTarget(target);
        SqliteMigration.inspect(target, schema, true);
        return SqliteMigration.revision(target);
    }
    @Override public DatabaseImportSession replaceDatabase(DatabaseConnectionTarget target, MigrationSchema schema,
            MigrationReplacement replacement, MigrationControl control) throws Exception {
        requireTarget(target);
        return SqliteReplacement.open(target, schema, replacement, control);
    }
    @Override public DatabaseImportSession importDatabase(DatabaseConnectionTarget target, MigrationSchema schema,
            MigrationControl control) throws Exception {
        requireTarget(target);
        return SqliteMigration.importDatabase(target, schema, control);
    }
    @Override public void verifySource(DatabaseConnectionTarget source, String revision) throws Exception {
        requireTarget(source);
        if (!SqliteMigration.revision(source).equals(revision)) throw new IllegalStateException("MIGRATION_SOURCE_CHANGED");
    }
    private static void requireTarget(DatabaseConnectionTarget target) {
        if (!(target instanceof SqliteConnectionTarget)) throw new IllegalArgumentException("MIGRATION_TARGET_OWNER");
    }
}
