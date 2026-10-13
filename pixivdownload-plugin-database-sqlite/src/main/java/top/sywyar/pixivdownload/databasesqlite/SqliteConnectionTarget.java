package top.sywyar.pixivdownload.databasesqlite;

import org.sqlite.JDBC;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteConnection;
import org.sqlite.SQLiteOpenMode;
import top.sywyar.pixivdownload.plugin.api.database.DatabaseConnectionSettings;
import top.sywyar.pixivdownload.plugin.api.database.DatabaseConnectionTarget;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;

/** 固定到单一 SQLite 文件的连接工厂；不创建或迁移业务 schema。 */
final class SqliteConnectionTarget implements DatabaseConnectionTarget {
    @Override public void optimize(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) { statement.execute("PRAGMA optimize"); }
    }

    @Override public SchemaApplyResult applySchema(Connection connection,
            top.sywyar.pixivdownload.plugin.api.database.migration.MigrationSchema schema, boolean strict) throws SQLException {
        return SqliteStorageSchema.apply(connection, schema, strict);
    }
    @Override public top.sywyar.pixivdownload.plugin.api.database.migration.jdbc.PhysicalDatabaseSchema.DatabaseSchema physicalSchema(
            top.sywyar.pixivdownload.plugin.api.database.migration.jdbc.PhysicalDatabaseSchema.DatabaseSchema schema) {
        return schema;
    }
    @Override public java.util.Map<String, top.sywyar.pixivdownload.plugin.api.database.migration.jdbc.PhysicalDatabaseSchema.TableSpec>
            readSchema(Connection connection) throws SQLException {
        return SqliteMigrationSchema.readActualTables(connection);
    }
    @Override public void initializeStorage(Connection connection,
            top.sywyar.pixivdownload.plugin.api.database.migration.MigrationSchema schema) throws SQLException {
        try {
            SqliteMigration.validateObjects(connection, schema, java.util.Set.of());
            SqliteMigration.applySchema(connection, schema, false);
        } catch (SQLException failure) { throw failure; }
        catch (Exception failure) { throw new SQLException("STORAGE_SCHEMA_MISMATCH", "0A000"); }
    }
    @Override public top.sywyar.pixivdownload.plugin.api.storage.relational.DatabaseSession storageSession(
            Connection connection, top.sywyar.pixivdownload.plugin.api.database.migration.MigrationSchema schema) {
        return new SqliteStorageSession(connection, schema);
    }
    private static final String PREFIX = "jdbc:sqlite:";
    private final Path file;

    SqliteConnectionTarget(Path file) { this.file = file.toAbsolutePath().normalize(); }

    SqliteConnectionTarget(DatabaseConnectionSettings settings) throws SQLException {
        if (!settings.username().isEmpty() || !settings.password().isEmpty()) throw invalid();
        String url = settings.jdbcUrl().trim();
        try {
            if (url.isEmpty()) file = SqliteStorageLocation.inspect(settings);
            else {
                if (!url.startsWith(PREFIX) || url.length() == PREFIX.length()
                        || url.contains("?") || url.contains(":memory:")) throw invalid();
                file = Path.of(url.substring(PREFIX.length())).toAbsolutePath().normalize();
            }
        } catch (RuntimeException invalidPath) {
            throw invalid();
        }
    }

    @Override public String description() { return "sqlite: " + file; }
    @Override public Optional<Path> localFile() { return Optional.of(file); }

    @Override public DatabaseConnectionTarget privateStorage(
            top.sywyar.pixivdownload.plugin.api.database.DatabasePrivateStorageSettings settings) throws SQLException {
        return new SqliteConnectionTarget(settings.directory().resolve("plugin.db"));
    }

    @Override public Connection openConnection(OpenMode mode) throws SQLException {
        if (mode == null) throw invalid();
        var configuration = new SQLiteConfig();
        configuration.setBusyTimeout(5000);
        if (mode == OpenMode.READ_WRITE) {
            configuration.setJournalMode(SQLiteConfig.JournalMode.WAL);
            try { Files.createDirectories(file.getParent()); }
            catch (IOException failure) { throw new SQLException("DATABASE_FILE_UNAVAILABLE", "08001"); }
        } else {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new SQLException("DATABASE_FILE_NOT_FOUND", "08001");
            }
            configuration.resetOpenMode(SQLiteOpenMode.CREATE);
            if (mode == OpenMode.READ_ONLY) configuration.setReadOnly(true);
        }
        try {
            // 使用此插件自己的驱动，不依赖全局 DriverManager 的 classloader 可见性。
            var connection = new JDBC().connect(connectionUrl(), configuration.toProperties());
            try {
                SqliteStorageFunctions.install(connection);
                return connection;
            } catch (SQLException failure) {
                connection.close();
                throw failure;
            }
        } catch (SQLException failure) {
            throw new SQLException("DATABASE_CONNECTION_FAILED", failure.getSQLState());
        }
    }

    @Override public void checkConnection() throws SQLException {
        try (Connection connection = openConnection(OpenMode.READ_ONLY);
             var statement = connection.createStatement();
             var result = statement.executeQuery("PRAGMA schema_version")) {
            if (!result.next()) throw new SQLException("DATABASE_CHECK_FAILED", "08001");
            requireConnectionTarget(connection);
        } catch (SQLException failure) {
            throw new SQLException("DATABASE_CHECK_FAILED", failure.getSQLState());
        }
    }

    @Override public void requireConnectionTarget(Connection connection) throws SQLException {
        if ("SQLite".equals(connection.getMetaData().getDatabaseProductName())) {
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("PRAGMA database_list")) {
                while (rows.next()) {
                    if (!"main".equals(rows.getString("name"))) continue;
                    String actual = rows.getString("file");
                    if (java.io.File.separatorChar == '\\' && actual != null) {
                        if (actual.startsWith("\\\\?\\UNC\\")) actual = "\\\\" + actual.substring(8);
                        else if (actual.startsWith("\\\\?\\")) actual = actual.substring(4);
                    }
                    try {
                        if (actual != null && !actual.isEmpty()
                                && file.equals(Path.of(actual).toAbsolutePath().normalize())) return;
                    } catch (RuntimeException invalidPath) {
                        // 非法文件身份按目标不匹配处理，不返回驱动路径。
                    }
                    break;
                }
            }
        }
        throw new SQLException("DATABASE_TARGET_MISMATCH", "08001");
    }

    private String connectionUrl() throws SQLException {
        if (java.io.File.separatorChar != '\\') return PREFIX + file;
        String path = file.toString();
        String extended = path.startsWith("\\\\") ? "\\\\?\\UNC\\" + path.substring(2) : "\\\\?\\" + path;
        try {
            // 扩展前缀避免 Win32 日志路径限制；URI 编码防止 JDBC 把路径中的 ?、& 当作参数。
            return PREFIX + "file:" + new URI(null, null, "/" + extended, null).toASCIIString()
                    + "?vfs=win32-longpath";
        } catch (URISyntaxException invalidPath) {
            throw invalid();
        }
    }

    @Override public void prepareTransaction(Connection connection, boolean readOnly) throws SQLException {
        SqliteStorageFunctions.install(connection);
        if (readOnly) return;
        var config = connection.unwrap(SQLiteConnection.class).getConnectionConfig();
        var previousMode = config.getTransactionMode();
        try {
            config.setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE);
            connection.rollback();
        } finally {
            config.setTransactionMode(previousMode);
        }
    }

    private static SQLException invalid() { return new SQLException("DATABASE_CONFIGURATION_INVALID", "08001"); }
    @Override public String toString() { return "sqlite"; }
}
