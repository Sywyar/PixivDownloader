package top.sywyar.pixivdownload.core.db;

import top.sywyar.pixivdownload.plugin.api.storage.relational.*;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** 宿主统一限制会话的线程、有效期、只读性和连接可用状态。 */
public final class ScopedDatabaseSession implements DatabaseSession {
    private final DatabaseSession delegate;
    private final BooleanSupplier available;
    private final boolean readOnly;
    private final java.util.function.Predicate<String> writable;
    private final Thread thread = Thread.currentThread();
    private boolean active = true;
    private boolean rejected;

    public ScopedDatabaseSession(DatabaseSession delegate, boolean readOnly, BooleanSupplier available) {
        this(delegate, readOnly, available, table -> true);
    }
    public ScopedDatabaseSession(DatabaseSession delegate, boolean readOnly, BooleanSupplier available,
                                 java.util.function.Predicate<String> writable) {
        this.delegate = delegate;
        this.readOnly = readOnly;
        this.available = available;
        this.writable = writable;
    }
    public void requireActive() throws SQLException {
        if (!active || thread != Thread.currentThread() || !available.getAsBoolean()) {
            rejected = true;
            throw new SQLException("STORAGE_SESSION_UNAVAILABLE", "08003");
        }
    }
    private void requireWrite(String table) throws SQLException {
        requireActive();
        if (readOnly) { rejected = true; throw new SQLException("STORAGE_READ_ONLY", "25006"); }
        if (!writable.test(table.toLowerCase(java.util.Locale.ROOT))) {
            rejected = true;
            throw new SQLException("STORAGE_TABLE_READ_ONLY", "42501");
        }
    }
    @Override public List<Map<String, Object>> query(Query query) throws SQLException {
        requireActive(); return delegate.query(query);
    }
    @Override public void initializeCleanup(StorageFeatures.Cleanup cleanup) throws SQLException {
        requireWrite(cleanup.table());
        for (var dependent : cleanup.dependents()) requireWrite(dependent.table());
        delegate.initializeCleanup(cleanup);
    }
    @Override public StorageFeatures.TextIndexMode initializeTextIndex(StorageFeatures.TextIndex index) throws SQLException {
        requireWrite(index.table()); return delegate.initializeTextIndex(index);
    }
    @Override public StorageFeatures.TextIndexMode refreshTextIndex(StorageFeatures.TextIndex index, Long key) throws SQLException {
        requireWrite(index.table()); return delegate.refreshTextIndex(index, key);
    }
    @Override public List<Long> searchText(StorageFeatures.TextIndex index, String term) throws SQLException {
        requireActive(); return delegate.searchText(index, term);
    }
    @Override public void scan(Query query, RowVisitor visitor) throws SQLException {
        requireActive();
        delegate.scan(query, row -> { requireActive(); return visitor.visit(row); });
        requireActive();
    }
    @Override public int insert(String table, Map<String, Object> values, Conflict conflict) throws SQLException {
        requireWrite(table); return delegate.insert(table, values, conflict);
    }
    @Override public long insertGenerated(String table, Map<String, Object> values, String key) throws SQLException {
        requireWrite(table); return delegate.insertGenerated(table, values, key);
    }
    @Override public int insertFrom(String table, Query query, Conflict conflict) throws SQLException {
        requireWrite(table); return delegate.insertFrom(table, query, conflict);
    }
    @Override public int update(String table, Map<String, Expression> changes, Expression where) throws SQLException {
        requireWrite(table); return delegate.update(table, changes, where);
    }
    @Override public List<Map<String, Object>> updateReturning(String table, Map<String, Expression> changes,
                                                               Expression where, List<String> returning) throws SQLException {
        requireWrite(table); return delegate.updateReturning(table, changes, where, returning);
    }
    @Override public int delete(String table, Expression where) throws SQLException {
        requireWrite(table); return delegate.delete(table, where);
    }
    @Override public boolean rollbackOnly() { return rejected || delegate.rollbackOnly(); }
    @Override public void close() { active = false; delegate.close(); }
}
