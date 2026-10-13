package top.sywyar.pixivdownload.plugin.runtime.context;

import top.sywyar.pixivdownload.plugin.api.storage.PluginDatabase;
import top.sywyar.pixivdownload.plugin.api.storage.PluginDataSource;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLNonTransientConnectionException;
import java.util.logging.Logger;

/** 保留旧 SDK 的 SQLite 合同；选中其它方言时明确拒绝，不另建 SQLite。 */
final class LegacyPluginDataSource implements PluginDataSource {
    private final PluginDatabase database;
    LegacyPluginDataSource(PluginDatabase database) { this.database = database; }

    @Override public Connection getConnection() throws SQLException {
        if (!database.available()) throw new SQLNonTransientConnectionException("DATABASE_UNAVAILABLE", "08003");
        if (!"sqlite".equals(database.databaseId())) {
            throw new SQLFeatureNotSupportedException("PLUGIN_REQUIRES_SQLITE_STORAGE", "0A000");
        }
        if (database instanceof javax.sql.DataSource source) return source.getConnection();
        throw new SQLFeatureNotSupportedException("LEGACY_STORAGE_UNAVAILABLE", "0A000");
    }
    @Override public Connection getConnection(String user, String password) throws SQLException {
        throw new SQLNonTransientConnectionException("DATABASE_CREDENTIAL_OVERRIDE_DENIED", "08004");
    }
    @Override public <T> T unwrap(Class<T> type) throws SQLException { throw new SQLException("DATABASE_UNWRAP_DENIED"); }
    @Override public boolean isWrapperFor(Class<?> type) { return false; }
    @Override public PrintWriter getLogWriter() { return null; }
    @Override public void setLogWriter(PrintWriter writer) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public int getLoginTimeout() { return 0; }
    @Override public void setLoginTimeout(int seconds) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { throw new SQLFeatureNotSupportedException(); }
}
