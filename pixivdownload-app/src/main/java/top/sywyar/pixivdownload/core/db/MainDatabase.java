package top.sywyar.pixivdownload.core.db;

import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import top.sywyar.pixivdownload.plugin.api.database.migration.MigrationSchema;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import top.sywyar.pixivdownload.plugin.api.storage.relational.DatabaseSession;
import top.sywyar.pixivdownload.plugin.registry.schema.DatabaseSchemaRegistry;
import javax.sql.DataSource;
import java.sql.SQLException;

/** 核心主库操作加入既有 Spring 事务，和文件发布、CAS 及权限关联写入共同提交。 */
@Component
public final class MainDatabase {
    private final DataSource dataSource;
    private final DatabaseTarget target;
    private final DatabaseSchemaRegistry registry;
    private final PlatformTransactionManager transactions;

    public MainDatabase(DataSource dataSource, DatabaseTarget target, DatabaseSchemaRegistry registry,
                        PlatformTransactionManager transactions) {
        this.dataSource = dataSource;
        this.target = target;
        this.registry = registry;
        this.transactions = transactions;
    }
    public boolean available() { return target.available() && !UnavailableDataSource.isUnavailable(dataSource); }
    public <T> T read(DatabaseSession.Work<T> work) { return execute(work, true); }
    public <T> T transaction(DatabaseSession.Work<T> work) { return execute(work, false); }

    private <T> T execute(DatabaseSession.Work<T> work, boolean readOnly) {
        return execute(work, readOnly, registry.storageSchema(), table -> true, this::available);
    }

    /** 每个 child context 获得独立句柄；关闭后旧回调不能继续操作或提交。 */
    public OwnerStorage forOwner(String owner, java.util.List<String> requested) {
        return new OwnerStorage(registry.storageScope(owner, requested));
    }

    public final class OwnerStorage implements top.sywyar.pixivdownload.plugin.api.storage.PluginMainDatabase, AutoCloseable {
        private final DatabaseSchemaRegistry.StorageScope scope;
        private volatile boolean open = true;
        private OwnerStorage(DatabaseSchemaRegistry.StorageScope scope) { this.scope = scope; }
        @Override public boolean available() { return open && MainDatabase.this.available(); }
        @Override public <T> T read(DatabaseSession.Work<T> work) {
            return execute(work, true, scope.schema(), scope.writableTables()::contains, this::available);
        }
        @Override public <T> T transaction(DatabaseSession.Work<T> work) {
            return execute(work, false, scope.schema(), scope.writableTables()::contains, this::available);
        }
        @Override public void close() { open = false; }
    }

    private <T> T execute(DatabaseSession.Work<T> work, boolean readOnly, MigrationSchema schema,
                          Predicate<String> writable, BooleanSupplier available) {
        if (!available.getAsBoolean()) throw new top.sywyar.pixivdownload.core.database.DatabaseUnavailableException();
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.hasResource(dataSource)) {
            // Spring 回滚保存点时清除此标记；注解状态可能属于更外层，不能据此标记外层回滚。
            var holder = (org.springframework.jdbc.datasource.ConnectionHolder)
                    TransactionSynchronizationManager.getResource(dataSource);
            return executeBound(work, readOnly, schema, writable, available, holder::setRollbackOnly);
        }
        var transaction = new TransactionTemplate(transactions);
        transaction.setReadOnly(readOnly);
        return transaction.execute(status -> executeBound(work, readOnly, schema, writable, available, status::setRollbackOnly));
    }

    private <T> T executeBound(DatabaseSession.Work<T> work, boolean readOnly, MigrationSchema schema,
                               Predicate<String> writable, BooleanSupplier available, Runnable rollback) {
        var connection = DataSourceUtils.getConnection(dataSource);
        try (var session = new ScopedDatabaseSession(target.storageSession(connection, schema),
                readOnly || TransactionSynchronizationManager.isCurrentTransactionReadOnly(), available, writable)) {
            T result = work.run(session);
            session.requireActive();
            if (session.rollbackOnly()) throw new SQLException("STORAGE_TRANSACTION_FAILED", "25000");
            return result;
        } catch (SQLException failure) {
            rollback.run();
            var translated = new org.springframework.jdbc.support.SQLStateSQLExceptionTranslator()
                    .translate("DATABASE_OPERATION_FAILED", null, failure);
            throw translated != null ? translated : new UncategorizedSQLException("DATABASE_OPERATION_FAILED", null, failure);
        } catch (RuntimeException | Error failure) {
            rollback.run();
            throw failure;
        } finally { DataSourceUtils.releaseConnection(connection, dataSource); }
    }
}
