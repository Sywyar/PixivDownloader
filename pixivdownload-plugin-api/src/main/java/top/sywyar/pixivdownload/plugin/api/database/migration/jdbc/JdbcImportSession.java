package top.sywyar.pixivdownload.plugin.api.database.migration.jdbc;

import top.sywyar.pixivdownload.plugin.api.database.migration.*;
import java.sql.Connection;
import java.util.*;
import static top.sywyar.pixivdownload.plugin.api.database.migration.jdbc.JdbcMigrationSupport.*;

/** JDBC provider 可复用的单事务实现；目标锁、空库、结构及序列操作由具体 provider 完成。 */
public abstract class JdbcImportSession implements DatabaseImportSession {
    /**
     * 当前目标独占事务连接，由本会话关闭。
     */
    protected final Connection connection;
    /**
     * 已在准备阶段核对的完整结构。
     */
    protected final MigrationSchema schema;
    /**
     * 宿主提供的取消及进度控制器。
     */
    protected final MigrationControl control;
    private final Set<String> written = new HashSet<>();
    private boolean verified, commitAttempted, committed, aborted, closed;
    /**
     * 接管已经锁定并准备好的目标事务连接。
     * @param connection 非自动提交连接
     * @param schema 已建立的中性结构
     * @param control 取消及进度控制器
     */
    protected JdbcImportSession(Connection connection, MigrationSchema schema, MigrationControl control) {
        this.connection = connection; this.schema = schema; this.control = control;
    }
    @Override public final void write(String name, MigrationRows rows) throws Exception {
        requireActive();
        var table = schema.table(name);
        if (verified || !written.add(name)) throw failure("MIGRATION_TABLE_STATE");
        String sql = "INSERT INTO " + quote(name) + " ("
                + String.join(",", table.columns().stream().map(c -> quote(c.name())).toList()) + ") VALUES ("
                + String.join(",", Collections.nCopies(table.columns().size(), "?")) + ")";
        try (var write = connection.prepareStatement(sql)) {
            long count = 0, bytes = 0;
            int buffered = 0;
            while (rows.next()) {
                control.checkpoint(name, count);
                var values = rows.values();
                if (values.size() != table.columns().size()) throw failure("MIGRATION_ROW_WIDTH");
                for (int i = 0; i < values.size(); i++) {
                    Object value = top.sywyar.pixivdownload.plugin.api.storage.relational.StorageValues.normalize(
                            MigrationValues.canonical(values.get(i), table.columns().get(i).type()));
                    write.setObject(i + 1, value);
                    bytes += value instanceof byte[] b ? b.length : value instanceof String s ? 3L * s.length() : 8;
                }
                write.addBatch();
                count++;
                // 一行可以超过批预算，但不能截断或拆成半行。
                if (++buffered == 500 || bytes >= 8L * 1024 * 1024) {
                    write.executeBatch(); write.clearBatch(); buffered = 0; bytes = 0;
                }
            }
            if (buffered != 0) write.executeBatch();
            control.checkpoint(name, count);
        }
    }
    @Override public final MigrationVerification verify(MigrationSnapshot expected) throws Exception {
        requireActive();
        if (!schema.equals(expected.schema()) || !written.equals(expected.verification().tables().keySet())
                || written.size() != schema.tables().size()) throw failure("MIGRATION_SCHEMA_MISMATCH");
        validateSchema();
        Map<String, Long> sequences = restoreSequences(expected.verification().sequences());
        var actual = new MigrationVerification(digests(schema, control, this::rows), sequences,
                references(connection, schema, control));
        if (!actual.equals(expected.verification())) throw failure("DATA_MISMATCH");
        verified = true;
        return actual;
    }
    /**
     * 按迁移合同的中性主键顺序回读。
     * @param table 声明表
     * @return 由核验过程关闭的游标
     * @throws Exception 无法按合同读取
     */
    protected abstract MigrationRows rows(top.sywyar.pixivdownload.plugin.api.schema.TableSpec table) throws Exception;
    /**
     * 由提供者回读物理结构和约束。
     * @throws Exception 结构不一致或无法核验
     */
    protected abstract void validateSchema() throws Exception;
    /**
     * 由提供者恢复历史高水位并回读实际序列。
     * @param expected 源历史高水位
     * @return 恢复后的高水位
     * @throws Exception 序列恢复或校验失败
     */
    protected abstract Map<String, Long> restoreSequences(Map<String, Long> expected) throws Exception;
    @Override public final void commit() throws Exception {
        requireActive();
        if (!verified) throw failure("MIGRATION_NOT_VERIFIED");
        control.checkpoint("", 0);
        commitAttempted = true;
        connection.commit();
        committed = true;
    }
    @Override public final void abort() throws Exception {
        if (committed || commitAttempted) throw failure("MIGRATION_COMMIT_OUTCOME_UNKNOWN");
        if (!aborted && !closed) { connection.rollback(); aborted = true; }
    }
    @Override public final void close() throws Exception {
        if (closed) return;
        try { if (!commitAttempted && !aborted) abort(); }
        finally { closed = true; connection.close(); }
    }
    private void requireActive() {
        if (closed || aborted || commitAttempted) throw failure("MIGRATION_SESSION_CLOSED");
    }
}
