package top.sywyar.pixivdownload.maintenance.tasks;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.core.db.DatabaseTarget;
import top.sywyar.pixivdownload.i18n.MessageBundles;
import top.sywyar.pixivdownload.plugin.api.maintenance.MaintenanceContext;
import top.sywyar.pixivdownload.plugin.api.maintenance.MaintenanceTask;

import javax.sql.DataSource;
import java.sql.Connection;

/**
 * 由当前提供者刷新主库的查询规划统计。
 *
 * <p>{@link Order} 值排在清理类任务之后，让统计能反映清理后的真实规模。
 */
@Slf4j
@Component
@Order(200)
@RequiredArgsConstructor
public class DatabaseOptimizeTask implements MaintenanceTask {

    private final DataSource dataSource;
    private final DatabaseTarget target;

    @Override
    public String name() {
        return "database-optimize";
    }

    @Override
    public void execute(MaintenanceContext context) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            target.optimize(connection);
            log.info(MessageBundles.getForLog("maintenance.log.database-optimize.done"));
        }
    }
}
