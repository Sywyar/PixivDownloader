package top.sywyar.pixivdownload.maintenance.tasks;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.maintenance.MaintenanceContext;
import top.sywyar.pixivdownload.maintenance.MaintenanceTask;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;

/**
 * 维护任务：刷新 SQLite 查询规划器统计信息（sqlite_stat1），让索引选择保持稳定。
 *
 * <p>用 {@code PRAGMA optimize} 而不是无脑 {@code ANALYZE}：它会自适应地仅对统计明显过时的表运行
 * {@code ANALYZE}，开销可控、更安全。
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
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA optimize");
            log.info("Database optimize (PRAGMA optimize) done");
        }
    }
}
