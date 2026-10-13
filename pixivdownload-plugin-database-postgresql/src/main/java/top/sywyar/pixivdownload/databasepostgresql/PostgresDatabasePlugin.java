package top.sywyar.pixivdownload.databasepostgresql;

import top.sywyar.pixivdownload.plugin.api.database.DatabaseConnectionSettings;
import top.sywyar.pixivdownload.plugin.api.database.DatabaseConnectionField;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldType;
import top.sywyar.pixivdownload.plugin.api.database.DatabaseConnectionTarget;
import top.sywyar.pixivdownload.plugin.api.database.DatabaseProvider;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.api.web.I18nContribution;

import java.sql.SQLException;
import java.util.List;
import top.sywyar.pixivdownload.plugin.api.database.migration.*;

/** PostgreSQL 驱动与连接语义归属于本外置插件。 */
public final class PostgresDatabasePlugin implements PixivFeaturePlugin, DatabaseProvider {
    private static final List<DatabaseConnectionField> CONNECTION_FIELDS = List.of(
            new DatabaseConnectionField("database.jdbc-url", GuiConfigFieldType.STRING),
            new DatabaseConnectionField("database.username", GuiConfigFieldType.STRING),
            new DatabaseConnectionField("database.password", GuiConfigFieldType.PASSWORD));
    @Override public String id() { return "database-postgresql"; }
    @Override public String databaseId() { return "postgresql"; }
    @Override public List<DatabaseConnectionField> connectionFields() { return CONNECTION_FIELDS; }
    @Override public void prepareStorage(DatabaseConnectionSettings settings) throws SQLException {
        // 服务端位置不随本地运行目录移动，启动只验证当前配置。
        resolve(settings);
    }
    @Override public DatabaseConnectionSettings localFileSettings(DatabaseConnectionSettings settings, java.nio.file.Path file) throws SQLException {
        throw new SQLException("DATABASE_LOCAL_FILE_UNSUPPORTED", "0A000");
    }
    @Override public DatabaseConnectionSettings normalizeConfiguration(DatabaseConnectionSettings settings, boolean selectionChanged) { return settings; }
    @Override public String displayName() { return "plugin.name"; }
    @Override public String description() { return "plugin.summary"; }
    @Override public String iconKey() { return "database"; }
    @Override public String colorToken() { return "blue"; }
    @Override public PluginKind kind() { return PluginKind.FEATURE; }
    @Override public List<I18nContribution> i18n() {
        return List.of(new I18nContribution("database-postgresql", "i18n.web.database-postgresql"));
    }
    @Override public DatabaseConnectionTarget resolve(DatabaseConnectionSettings settings) throws SQLException {
        return new PostgresConnectionTarget(settings);
    }
    @Override public java.util.Set<String> privateDatabaseOwners(DatabaseConnectionTarget main,
            java.nio.file.Path directory, String installation) throws Exception {
        if (!(main instanceof PostgresConnectionTarget target)) throw new IllegalArgumentException("MIGRATION_TARGET_OWNER");
        return PostgresMigration.owners(target, installation);
    }
    @Override public java.util.Map<String, DatabaseConnectionTarget> retainedLocalPrivateDatabases(
            java.nio.file.Path directory, String installation) {
        // 关系存储在服务端，必须用精确连接配置通过 privateDatabaseOwners 枚举。
        return java.util.Map.of();
    }
    @Override public DatabaseExportSession exportDatabase(DatabaseConnectionTarget source, MigrationRequest request,
            MigrationControl control) throws Exception {
        requireTarget(source);
        return PostgresMigration.exportDatabase(source, request, control);
    }
    @Override public Inspection inspectMigration(DatabaseConnectionTarget target, MigrationSchema schema, boolean source) throws Exception {
        requireTarget(target);
        return PostgresMigration.inspect(target, schema, source);
    }
    @Override public void verifyImported(DatabaseConnectionTarget target, MigrationSnapshot snapshot, MigrationControl control) throws Exception {
        requireTarget(target);
        PostgresMigration.verifyImported(target, snapshot, control);
    }
    @Override public DatabaseImportSession importDatabase(DatabaseConnectionTarget target, MigrationSchema schema,
            MigrationControl control) throws Exception {
        requireTarget(target);
        return PostgresMigration.importDatabase(target, schema, control);
    }
    @Override public void verifySource(DatabaseConnectionTarget source, String revision) throws Exception {
        requireTarget(source);
        if (!PostgresMigration.revision(source).equals(revision)) throw new IllegalStateException("MIGRATION_SOURCE_CHANGED");
    }
    private static void requireTarget(DatabaseConnectionTarget target) {
        if (!(target instanceof PostgresConnectionTarget || target instanceof PostgresPrivateStorage))
            throw new IllegalArgumentException("MIGRATION_TARGET_OWNER");
    }
}
