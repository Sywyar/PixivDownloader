package top.sywyar.pixivdownload.plugin.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.jdbc.datasource.AbstractDataSource;
import top.sywyar.pixivdownload.core.db.DatabaseTarget;
import top.sywyar.pixivdownload.core.db.UnavailableDataSource;
import top.sywyar.pixivdownload.plugin.api.database.DatabaseConnectionTarget;
import top.sywyar.pixivdownload.plugin.api.database.DatabasePrivateStorageSettings;
import top.sywyar.pixivdownload.plugin.api.storage.PluginDatabase;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.util.function.Supplier;
import java.util.List;
import java.util.Map;
import top.sywyar.pixivdownload.plugin.api.schema.SchemaContribution;
import top.sywyar.pixivdownload.plugin.api.storage.relational.*;
import top.sywyar.pixivdownload.plugin.api.database.migration.MigrationSchema;

/** 宿主管理的 owner 私库租约；缺席时不创建文件、凭据、schema 或替代连接池。 */
public final class AppPluginDatabase extends AbstractDataSource implements PluginDatabase, AutoCloseable {
    private final String owner;
    private final DatabaseTarget selected;
    private final DataSource main;
    private final Supplier<DatabasePrivateStorageSettings> settings;
    private HikariDataSource pool;
    private boolean closed;
    private boolean failed;
    private DatabaseConnectionTarget privateTarget;
    private MigrationSchema schema;
    private final ThreadLocal<Boolean> inCallback = new ThreadLocal<>();

    public AppPluginDatabase(String owner, DatabaseTarget selected, DataSource main,
                             Supplier<DatabasePrivateStorageSettings> settings) {
        this.owner = owner;
        this.selected = selected;
        this.main = main;
        this.settings = settings;
    }

    public void initialize(List<SchemaContribution> contributions) throws SQLException {
        initialize(contributions, List.of());
    }

    public void initialize(List<SchemaContribution> contributions, List<StorageMigration> migrations) throws SQLException {
        schema = PrivateStorageSchema.merge(contributions, migrations);
        if (schema.tables().isEmpty() || !available()) return;
        try (var connection = getConnection()) {
            connection.setAutoCommit(false);
            try {
                top.sywyar.pixivdownload.core.db.provider.DatabaseConnectionLease.prepareTransaction(connection, privateTarget, false);
                privateTarget.initializeStorage(connection, schema);
                try (var session = new top.sywyar.pixivdownload.core.db.ScopedDatabaseSession(privateTarget.storageSession(connection, schema), false, this::available)) {
                    inCallback.set(true);
                    try { StorageMigration.apply(session, migrations); }
                    finally { inCallback.remove(); }
                }
                connection.commit();
            } catch (SQLException | RuntimeException | Error failure) {
                try { connection.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
                failed = true;
                throw failure;
            }
        }
    }

    @Override public <T> T read(DatabaseSession.Work<T> work) throws SQLException { return execute(work, true); }
    @Override public <T> T transaction(DatabaseSession.Work<T> work) throws SQLException { return execute(work, false); }

    private <T> T execute(DatabaseSession.Work<T> work, boolean readOnly) throws SQLException {
        if (!available()) throw new SQLNonTransientConnectionException("DATABASE_UNAVAILABLE", "08003");
        if (schema == null || schema.tables().isEmpty()) throw new SQLException("PRIVATE_SCHEMA_MISSING", "0A000");
        if (inCallback.get() != null) throw new SQLException("STORAGE_NESTED_CALLBACK", "25000");
        try (var connection = getConnection()) {
            connection.setAutoCommit(false);
            try {
                top.sywyar.pixivdownload.core.db.provider.DatabaseConnectionLease.prepareTransaction(connection, privateTarget, readOnly);
                T result;
                try (var session = new top.sywyar.pixivdownload.core.db.ScopedDatabaseSession(privateTarget.storageSession(connection, schema), readOnly, this::available)) {
                    inCallback.set(true);
                    try {
                        result = work.run(session);
                        session.requireActive();
                        if (session.rollbackOnly()) throw new SQLException("STORAGE_TRANSACTION_FAILED", "25000");
                    } finally { inCallback.remove(); }
                }
                if (readOnly) connection.rollback(); else connection.commit();
                return result;
            } catch (SQLException | RuntimeException | Error failure) {
                try { connection.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
                throw failure;
            }
        }
    }

    @Override public String databaseId() {
        return selected.databaseId();
    }
    @Override public synchronized boolean available() {
        return !closed && !failed && selected.available() && !UnavailableDataSource.isUnavailable(main);
    }
    @Override public Connection getConnection() throws SQLException {
        HikariDataSource current;
        synchronized (this) {
            if (!available()) throw new SQLNonTransientConnectionException("DATABASE_UNAVAILABLE", "08003");
            if (pool == null) {
                try {
                    var target = selected.privateStorage(settings.get());
                    privateTarget = target;
                    var configuration = new HikariConfig();
                    configuration.setPoolName("plugin-database-" + owner);
                    configuration.setMaximumPoolSize(2);
                    configuration.setMinimumIdle(0);
                    configuration.setDataSource(new AbstractDataSource() {
                        @Override public Connection getConnection() throws SQLException {
                            if (!available()) throw new SQLNonTransientConnectionException("DATABASE_UNAVAILABLE", "08003");
                            return target.openConnection(DatabaseConnectionTarget.OpenMode.READ_WRITE);
                        }
                        @Override public Connection getConnection(String user, String password) throws SQLException {
                            throw new SQLNonTransientConnectionException("DATABASE_CREDENTIAL_OVERRIDE_DENIED", "08004");
                        }
                    });
                    pool = top.sywyar.pixivdownload.core.db.provider.DatabaseConnectionPool.create(configuration);
                } catch (VirtualMachineError | ThreadDeath fatal) {
                    throw fatal;
                } catch (Throwable failure) {
                    failed = true;
                    throw new SQLException("DATABASE_PRIVATE_STORAGE_UNAVAILABLE", "08003");
                }
            }
            current = pool;
        }
        try {
            return top.sywyar.pixivdownload.core.db.provider.DatabaseConnectionLease.wrap(
                    current.getConnection(), this::available);
        } catch (SQLException failure) {
            // 借用超时或线程中断不撤销已建立的 owner 绑定，后续请求仍可重试。
            throw new SQLException("DATABASE_PRIVATE_STORAGE_UNAVAILABLE", "08003");
        }
    }
    @Override public Connection getConnection(String user, String password) throws SQLException {
        throw new SQLNonTransientConnectionException("DATABASE_CREDENTIAL_OVERRIDE_DENIED", "08004");
    }
    @Override public synchronized void close() {
        closed = true;
        if (pool != null) pool.close();
    }
}
