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
 * 新表属当前未发布开发周期内引入，无需 {@code ALTER} 迁移兜底。
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class ScheduledTaskDatabase {

    private final ScheduledTaskMapper mapper;

    @PostConstruct
    public void init() {
        mapper.createScheduledTasksTable();
        mapper.createScheduledTasksNextRunIndex();
        log.info("scheduled_tasks schema initialized");
    }

    public ScheduledTaskMapper mapper() {
        return mapper;
    }
}
