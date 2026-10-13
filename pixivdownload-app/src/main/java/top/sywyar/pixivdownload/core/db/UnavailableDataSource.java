package top.sywyar.pixivdownload.core.db;

import org.springframework.jdbc.datasource.AbstractDataSource;

import java.sql.Connection;
import java.sql.SQLNonTransientConnectionException;

/** 缺少选定数据库能力时保留装配边界；所有连接请求均明确失败，不创建替代库。 */
public final class UnavailableDataSource extends AbstractDataSource implements AutoCloseable {
    public static boolean isUnavailable(javax.sql.DataSource source) {
        return source instanceof UnavailableDataSource
                || source instanceof top.sywyar.pixivdownload.core.db.provider.ProviderDataSource provider
                && !provider.available();
    }

    @Override public void close() { }
    @Override
    public Connection getConnection() throws SQLNonTransientConnectionException {
        throw new SQLNonTransientConnectionException("DATABASE_UNAVAILABLE", "08003");
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLNonTransientConnectionException {
        return getConnection();
    }
}
