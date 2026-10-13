package top.sywyar.pixivdownload.core.db.provider;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.jdbc.datasource.AbstractDataSource;
import top.sywyar.pixivdownload.core.db.DatabaseTarget;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;

/** 每次借连接均复核选定 owner 的失败事实；故障只撤回原目标，不选择替代库。 */
public final class ProviderDataSource extends AbstractDataSource implements AutoCloseable {
    private final HikariDataSource pool;
    private final DatabaseTarget target;
    private final PluginRegistry registry;
    private final PluginRegistry.RegisteredPlugin owner;

    public ProviderDataSource(HikariDataSource pool, DatabaseTarget target, PluginRegistry registry,
                              PluginRegistry.RegisteredPlugin owner) {
        this.pool = pool;
        this.target = target;
        this.registry = registry;
        this.owner = owner;
    }

    public boolean available() {
        if (!registry.isActiveIdentity(owner) || registry.lifecycleFailure(owner).isPresent()) target.invalidate();
        return target.available();
    }

    @Override public Connection getConnection() throws SQLException {
        if (!available()) throw new SQLNonTransientConnectionException("DATABASE_UNAVAILABLE", "08003");
        return DatabaseConnectionLease.wrap(pool.getConnection(), this::available);
    }

    @Override public Connection getConnection(String username, String password) throws SQLException {
        throw new SQLNonTransientConnectionException("DATABASE_CREDENTIAL_OVERRIDE_DENIED", "08004");
    }

    @Override public void close() { pool.close(); }
}
