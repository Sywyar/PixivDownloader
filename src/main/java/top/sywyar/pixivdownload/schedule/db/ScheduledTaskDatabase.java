package top.sywyar.pixivdownload.schedule.db;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import top.sywyar.pixivdownload.core.db.schema.DatabaseInitializer;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;
import top.sywyar.pixivdownload.schedule.ScheduledTask;

import java.util.List;

/**
 * {@code scheduled_tasks} 表的底层访问门面。
 *
 * <p>建表 / 补列 / 索引已统一由 {@link DatabaseInitializer} 执行；{@link #init()} 只保留
 * 幂等的任务快照数据迁移。注入池化 {@code DataSource}（经 MyBatis {@code SqlSessionFactory}），不自建连接。
 */
@Slf4j
@PluginManagedBean
@RequiredArgsConstructor
public class ScheduledTaskDatabase {

    private final ScheduledTaskMapper mapper;
    /** 不直接使用：仅表达对 {@link DatabaseInitializer} 的初始化顺序依赖（{@link #init()} 要求表已建好）。 */
    @SuppressWarnings("unused")
    private final DatabaseInitializer databaseInitializer;

    @PostConstruct
    public void init() {
        backfillRedownloadDeletedSetting();
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

    public ScheduledTaskMapper mapper() {
        return mapper;
    }
}
