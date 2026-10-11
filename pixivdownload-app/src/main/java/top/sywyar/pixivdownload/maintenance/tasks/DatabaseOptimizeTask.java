package top.sywyar.pixivdownload.maintenance.tasks;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.core.db.sqlite.SqliteDatabase;
import top.sywyar.pixivdownload.core.db.DatabaseEngine;
import top.sywyar.pixivdownload.i18n.MessageBundles;
import top.sywyar.pixivdownload.plugin.api.maintenance.MaintenanceContext;
import top.sywyar.pixivdownload.plugin.api.maintenance.MaintenanceTask;

import javax.sql.DataSource;
import java.sql.Connection;

/**
 * 刷新当前主库的查询规划统计。SQLite 保留 PRAGMA optimize 的按需策略；
 * PostgreSQL 只分析当前应用 schema 中的表。
 *
 * <p>{@link Order} 值排在清理类任务之后，让统计能反映清理后的真实规模。
 */
@Slf4j
@Component
@Order(200)
@RequiredArgsConstructor
public class DatabaseOptimizeTask implements MaintenanceTask {

    private final DataSource dataSource;

    @Override
    public String name() {
        return "database-optimize";
    }

    @Override
    public void execute(MaintenanceContext context) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            if (DatabaseEngine.detect(connection) == DatabaseEngine.SQLITE) {
                SqliteDatabase.optimize(connection);
            } else {
                var tables = new java.util.ArrayList<String>();
                try (var statement = connection.createStatement();
                     var rows = statement.executeQuery("""
                             SELECT quote_ident(schemaname) || '.' || quote_ident(tablename)
                             FROM pg_catalog.pg_tables WHERE schemaname = current_schema()
                             ORDER BY tablename
                             """)) {
                    while (rows.next()) tables.add(rows.getString(1));
                }
                try (var statement = connection.createStatement()) {
                    for (String table : tables) statement.execute("ANALYZE " + table);
                }
            }
            log.info(MessageBundles.getForLog("maintenance.log.database-optimize.done"));
        }
    }
}
