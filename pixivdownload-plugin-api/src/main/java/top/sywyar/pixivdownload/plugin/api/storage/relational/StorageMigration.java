package top.sywyar.pixivdownload.plugin.api.storage.relational;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import top.sywyar.pixivdownload.plugin.api.schema.*;
import static top.sywyar.pixivdownload.plugin.api.storage.relational.Expression.*;

/**
 * 有序且不可变的业务转换，在结构事务内仅执行一次。
 * 回调只能使用传入会话，不访问文件、网络、child Bean 或产生外部副作用。
 * 已发布 ID 与其含义保持稳定，缺失或重排历史均拒绝。
 */
public record StorageMigration(String id, DatabaseSession.Work<Void> conversion) {
    /**
     * 校验稳定转换身份并固定同步转换回调。
     * @param conversion 无外部副作用的同步业务转换
     * @param id 发布后不可更改含义的转换身份
     */
    public StorageMigration {
        top.sywyar.pixivdownload.plugin.api.database.migration.MigrationSchema.identifier(id);
        Objects.requireNonNull(conversion);
    }
    /**
     * 提供者管理的保留历史表，随 owner 数据一起复制与核验。
     * @return 包含稳定转换身份和位置的历史表声明
     */
    public static TableSpec historyTable() {
        return new TableSpec("_storage_migrations", List.of(
                new ColumnSpec("id", "TEXT", true, null, 1),
                new ColumnSpec("position", "INTEGER", true, null, 0)), List.of());
    }
    /**
     * 在调用方既有事务内执行待应用转换，不自行提交。
     * @param migrations 按发布顺序排列的稳定业务转换
     * @param session 当前结构事务内有效的中性会话
     * @throws java.sql.SQLException 数据库不可用、能力不支持或执行失败；当前事务不得继续提交
     */
    public static void apply(DatabaseSession session, List<StorageMigration> migrations) throws SQLException {
        int applied = appliedCount(session, migrations);
        for (int i = applied; i < migrations.size(); i++) {
            migrations.get(i).conversion().run(session);
            if (session.rollbackOnly()) throw new SQLException("STORAGE_TRANSACTION_FAILED", "25000");
            session.insert("_storage_migrations", Map.of("id", migrations.get(i).id(), "position", (long) i),
                    DatabaseSession.Conflict.error());
        }
    }
    /**
     * 无一次性升级副本能力的源提供者必须拒绝待应用转换。
     * @param migrations 按发布顺序排列的稳定业务转换
     * @param session 当前结构事务内有效的中性会话
     * @throws java.sql.SQLException 转换尚未应用、历史不一致或读取失败
     */
    public static void requireApplied(DatabaseSession session, List<StorageMigration> migrations) throws SQLException {
        if (appliedCount(session, migrations) != migrations.size())
            throw new SQLException("STORAGE_MIGRATION_REQUIRED", "0A000");
    }
    private static int appliedCount(DatabaseSession session, List<StorageMigration> migrations) throws SQLException {
        if (migrations.isEmpty()) return 0;
        var history = session.query(new Query("_storage_migrations",
                Map.of("id", column("_storage_migrations", "id"), "position", column("_storage_migrations", "position")),
                null, List.of(), List.of(new Query.Order(column("_storage_migrations", "position"), false, false)),
                Math.addExact(migrations.size(), 1), 0));
        if (history.size() > migrations.size()) throw new SQLException("STORAGE_MIGRATION_HISTORY_MISMATCH", "0A000");
        for (int i = 0; i < history.size(); i++) {
            if (!migrations.get(i).id().equals(history.get(i).get("id"))
                    || !Long.valueOf(i).equals(history.get(i).get("position")))
                throw new SQLException("STORAGE_MIGRATION_HISTORY_MISMATCH", "0A000");
        }
        return history.size();
    }
}
