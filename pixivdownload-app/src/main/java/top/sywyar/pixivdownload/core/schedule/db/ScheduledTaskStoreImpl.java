package top.sywyar.pixivdownload.core.schedule.db;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import top.sywyar.pixivdownload.core.db.schema.DatabaseInitializer;
import top.sywyar.pixivdownload.core.schedule.ScheduledTask;
import top.sywyar.pixivdownload.core.schedule.ScheduledTaskInsert;
import top.sywyar.pixivdownload.core.schedule.ScheduledTaskPending;
import top.sywyar.pixivdownload.core.schedule.ScheduledTaskStore;
import top.sywyar.pixivdownload.core.schedule.ScheduledTaskType;

import java.util.List;

/**
 * {@link ScheduledTaskStore} 的核心实现层（{@code core.schedule.db}）。
 *
 * <p><b>边界职责：</b>把底层 MyBatis {@link ScheduledTaskMapper} 收拢为内部实现，只透出
 * {@link ScheduledTaskStore} 声明的语义读写方法；插件托管的调度引擎 Bean 注入的是接口
 * {@link ScheduledTaskStore}、永远拿不到 mapper / 裸连接 / 自由 SQL。{@code scheduled_tasks} /
 * {@code scheduled_task_pending} 两张表的 schema 归核心（{@link ScheduleSchemaContribution}，owner = core）。
 *
 * <p>注入由 MyBatis {@code SqlSessionFactory} 提供的池化 {@code DataSource}（经 mapper），不自建连接、
 * 不绕过连接池。建表 / 补列 / 索引统一由 {@link DatabaseInitializer} 执行；{@link #init()} 只保留幂等的
 * 任务快照数据迁移。
 *
 * <p><b>cookie 红线（沿用 {@link ScheduledTaskMapper} 约定）：</b>{@code cookie_snapshot} 是完整登录凭证，
 * 不出现在 {@link #findAll()} / {@link #findById(long)} 这类行投影里（恒为 {@code null}）；调度器需要凭证时
 * 单独调 {@link #findCookieSnapshot(long)} 取裸标量，结果只在服务内部使用、绝不写日志 / 回显。
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class ScheduledTaskStoreImpl implements ScheduledTaskStore {

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

    // ── scheduled_tasks 读取（无 cookie） ──────────────────────────────────────────

    @Override
    public List<ScheduledTask> findAll() {
        return mapper.findAll();
    }

    @Override
    public ScheduledTask findById(long id) {
        return mapper.findById(id);
    }

    @Override
    public int countAll() {
        return mapper.countAll();
    }

    @Override
    public List<ScheduledTask> findDue(long now) {
        return mapper.findDue(now);
    }

    @Override
    public List<ScheduledTask> findByAccountId(String accountId) {
        return mapper.findByAccountId(accountId);
    }

    @Override
    public String findCookieSnapshot(long id) {
        return mapper.findCookieSnapshot(id);
    }

    // ── scheduled_tasks 写入 ──────────────────────────────────────────────────────

    @Override
    public void insert(ScheduledTaskInsert task) {
        mapper.insert(task);
    }

    @Override
    public int updateDefinition(long id, String name, ScheduledTaskType type, String paramsJson,
                                String triggerKind, Integer intervalMinutes, String cronExpr,
                                Long nextRunTime) {
        return mapper.updateDefinition(id, name, type, paramsJson, triggerKind, intervalMinutes,
                cronExpr, nextRunTime);
    }

    @Override
    public int updateEnabled(long id, boolean enabled) {
        return mapper.updateEnabled(id, enabled);
    }

    @Override
    public int updateCookie(long id, String cookieSnapshot, String cookieMode) {
        return mapper.updateCookie(id, cookieSnapshot, cookieMode);
    }

    @Override
    public int clearCookieAndAccount(long id, String cookieMode) {
        return mapper.clearCookieAndAccount(id, cookieMode);
    }

    @Override
    public int updateProxy(long id, String proxySnapshot) {
        return mapper.updateProxy(id, proxySnapshot);
    }

    @Override
    public int updateRunResult(long id, Long lastRunTime, String lastStatus, String lastMessage,
                               Long nextRunTime) {
        return mapper.updateRunResult(id, lastRunTime, lastStatus, lastMessage, nextRunTime);
    }

    @Override
    public int updateRunStarted(long id, Long runStartedTime) {
        return mapper.updateRunStarted(id, runStartedTime);
    }

    @Override
    public int updateWatermark(long id, Long watermarkId) {
        return mapper.updateWatermark(id, watermarkId);
    }

    @Override
    public int delete(long id) {
        return mapper.delete(id);
    }

    // ── 状态 / 挂起 / 账号冻结 ─────────────────────────────────────────────────────

    @Override
    public int setStatus(long id, String status) {
        return mapper.setStatus(id, status);
    }

    @Override
    public int updateAccountId(long id, String accountId) {
        return mapper.updateAccountId(id, accountId);
    }

    @Override
    public int freezeAccount(String accountId, String status, String message) {
        return mapper.freezeAccount(accountId, status, message);
    }

    @Override
    public int updateAckWarning(String accountId, Long ackTime) {
        return mapper.updateAckWarning(accountId, ackTime);
    }

    @Override
    public int clearSuspendForAccount(String accountId, Long nextRun) {
        return mapper.clearSuspendForAccount(accountId, nextRun);
    }

    @Override
    public int clearSuspend(long id, Long nextRun) {
        return mapper.clearSuspend(id, nextRun);
    }

    @Override
    public int clearSuspendIfStatus(long id, Long nextRun, String expectedStatus) {
        return mapper.clearSuspendIfStatus(id, nextRun, expectedStatus);
    }

    // ── scheduled_task_pending（单作品隔离重试表）CRUD ────────────────────────────

    @Override
    public int insertPending(long taskId, long workId, String reason, long now) {
        return mapper.insertPending(taskId, workId, reason, now);
    }

    @Override
    public int deletePending(long taskId, long workId) {
        return mapper.deletePending(taskId, workId);
    }

    @Override
    public int incPendingAttempts(long taskId, long workId, long now) {
        return mapper.incPendingAttempts(taskId, workId, now);
    }

    @Override
    public Integer selectPendingAttempts(long taskId, long workId) {
        return mapper.selectPendingAttempts(taskId, workId);
    }

    @Override
    public List<ScheduledTaskPending> listPending(long taskId) {
        return mapper.listPending(taskId);
    }

    @Override
    public int deleteAllPending(long taskId) {
        return mapper.deleteAllPending(taskId);
    }
}
