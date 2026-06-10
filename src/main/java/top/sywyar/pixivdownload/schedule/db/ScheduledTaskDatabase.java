package top.sywyar.pixivdownload.schedule.db;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import top.sywyar.pixivdownload.schedule.ScheduledTask;

import java.util.List;

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
        backfillRedownloadDeletedSetting();
        log.info("scheduled_tasks schema initialized");
    }

    /**
     * 幂等迁移：为旧版本创建的任务快照补齐 {@code download.redownloadDeleted}（允许已删除的作品被重新下载），
     * 默认 {@code false}（不勾选 = 软删除的作品视为已下载、跳过）。已有该字段的任务不动；
     * 单个任务解析失败仅记日志、不阻断启动（执行器对缺字段也按 false 兜底）。
     */
    private void backfillRedownloadDeletedSetting() {
        List<ScheduledTask> tasks = mapper.findAll();
        if (tasks == null || tasks.isEmpty()) {
            return;
        }
        ObjectMapper json = new ObjectMapper();
        for (ScheduledTask task : tasks) {
            try {
                JsonNode root = json.readTree(task.paramsJson() == null ? "{}" : task.paramsJson());
                if (!(root instanceof ObjectNode rootNode)) {
                    continue;
                }
                JsonNode downloadNode = rootNode.path("download");
                ObjectNode download = downloadNode.isObject()
                        ? (ObjectNode) downloadNode
                        : rootNode.putObject("download");
                if (download.has("redownloadDeleted")) {
                    continue;
                }
                download.put("redownloadDeleted", false);
                mapper.updateParamsJson(task.id(), json.writeValueAsString(rootNode));
            } catch (Exception e) {
                log.warn("Failed to backfill redownloadDeleted for scheduled task {}: {}",
                        task.id(), e.getMessage());
            }
        }
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
