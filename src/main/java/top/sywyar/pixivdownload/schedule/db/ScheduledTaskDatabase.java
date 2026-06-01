package top.sywyar.pixivdownload.schedule.db;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

/**
 * {@code scheduled_tasks} 表的建表/索引初始化与底层访问门面。
 *
 * <p>沿用仓库既有 {@code *Database + *Mapper} 模式：DDL 在 {@link #init()}（{@code @PostConstruct}）里
 * 幂等执行，注入池化 {@code DataSource}（经 MyBatis {@code SqlSessionFactory}），不自建连接。
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class ScheduledTaskDatabase {

    private final ScheduledTaskMapper mapper;

    @PostConstruct
    public void init() {
        mapper.createScheduledTasksTable();
        // 幂等迁移：在建索引前补齐旧库可能缺失的列，否则建 account_id 索引或后续读写会失败。
        addColumnIfMissing(mapper::addAccountIdColumn);
        addColumnIfMissing(mapper::addAckWarningTimeColumn);
        addColumnIfMissing(mapper::addPendingRetryArmedColumn);
        mapper.createScheduledTasksNextRunIndex();
        mapper.createScheduledTasksAccountIndex();
        mapper.createScheduledTaskPendingTable();
        log.info("scheduled_tasks schema initialized");
    }

    private void addColumnIfMissing(Runnable addColumn) {
        try {
            addColumn.run();
        } catch (Exception e) {
            String msg = String.valueOf(e.getMessage());
            if (!msg.toLowerCase().contains("duplicate column")) {
                log.warn("Unexpected error adding scheduled_tasks column: {}", msg, e);
            }
        }
    }

    public ScheduledTaskMapper mapper() {
        return mapper;
    }
}
