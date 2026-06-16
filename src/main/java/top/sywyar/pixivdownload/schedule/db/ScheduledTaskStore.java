package top.sywyar.pixivdownload.schedule.db;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import top.sywyar.pixivdownload.core.db.schema.DatabaseInitializer;
import top.sywyar.pixivdownload.schedule.ScheduledTask;
import top.sywyar.pixivdownload.schedule.ScheduledTaskType;

import java.util.List;

/**
 * {@code scheduled_tasks} / {@code scheduled_task_pending} 两张<b>核心 owned</b> 表的语义数据访问门面。
 *
 * <p><b>边界职责：</b>计划任务调度引擎（{@code ScheduleExecutor} / {@code ScheduleService} /
 * {@code ScheduleRunner} 等）随 schedule 能力收编进下载工作台插件、为 {@code @PluginManagedBean}；
 * 这两张表的 schema 仍归核心（{@link ScheduleSchemaContribution}，owner = core）。为避免插件托管 Bean
 * 直接拿 MyBatis {@link ScheduledTaskMapper} 做「自由 SQL」访问核心表，本类作为<b>核心 owned、根包扫描</b>
 * 的语义 Store：把 mapper 收拢为内部实现，只对外暴露调度引擎实际需要的读写方法。插件托管 Bean 只注入本 Store、
 * 不再触达 mapper；唯一允许依赖 {@link ScheduledTaskMapper} 的就是核心数据层 {@code schedule.db} 自身。
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
public class ScheduledTaskStore {

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

    public List<ScheduledTask> findAll() {
        return mapper.findAll();
    }

    public ScheduledTask findById(long id) {
        return mapper.findById(id);
    }

    public int countAll() {
        return mapper.countAll();
    }

    /** 到期或被进程强杀中断、且未被挂起的任务（见 {@link ScheduledTaskMapper#findDue(long)}）。 */
    public List<ScheduledTask> findDue(long now) {
        return mapper.findDue(now);
    }

    public List<ScheduledTask> findByAccountId(String accountId) {
        return mapper.findByAccountId(accountId);
    }

    /** cookie 专用裸标量取值（仅供调度器内部，绝不写日志 / 回显）。 */
    public String findCookieSnapshot(long id) {
        return mapper.findCookieSnapshot(id);
    }

    // ── scheduled_tasks 写入 ──────────────────────────────────────────────────────

    public void insert(ScheduledTaskInsert task) {
        mapper.insert(task);
    }

    public int updateDefinition(long id, String name, ScheduledTaskType type, String paramsJson,
                                String triggerKind, Integer intervalMinutes, String cronExpr,
                                Long nextRunTime) {
        return mapper.updateDefinition(id, name, type, paramsJson, triggerKind, intervalMinutes,
                cronExpr, nextRunTime);
    }

    public int updateEnabled(long id, boolean enabled) {
        return mapper.updateEnabled(id, enabled);
    }

    public int updateCookie(long id, String cookieSnapshot, String cookieMode) {
        return mapper.updateCookie(id, cookieSnapshot, cookieMode);
    }

    /** 解除授权 / 失效自动降级：清 Cookie 转受限并清除由 Cookie 派生的账号绑定（见 {@link ScheduledTaskMapper#clearCookieAndAccount}）。 */
    public int clearCookieAndAccount(long id, String cookieMode) {
        return mapper.clearCookieAndAccount(id, cookieMode);
    }

    /** 设置 / 清除任务级单独代理（host:port，非凭证；{@code null} = 回退全局代理设置）。 */
    public int updateProxy(long id, String proxySnapshot) {
        return mapper.updateProxy(id, proxySnapshot);
    }

    /** 本轮跑完落库结果（CASE 保留运行中被手动设置的 PAUSED，见 {@link ScheduledTaskMapper#updateRunResult}）。 */
    public int updateRunResult(long id, Long lastRunTime, String lastStatus, String lastMessage,
                               Long nextRunTime) {
        return mapper.updateRunResult(id, lastRunTime, lastStatus, lastMessage, nextRunTime);
    }

    public int updateRunStarted(long id, Long runStartedTime) {
        return mapper.updateRunStarted(id, runStartedTime);
    }

    public int updateWatermark(long id, Long watermarkId) {
        return mapper.updateWatermark(id, watermarkId);
    }

    public int delete(long id) {
        return mapper.delete(id);
    }

    // ── 状态 / 挂起 / 账号冻结 ─────────────────────────────────────────────────────

    /** 仅置状态（手动暂停等），不动其它列。 */
    public int setStatus(long id, String status) {
        return mapper.setStatus(id, status);
    }

    /** 写入授权解析出的非敏感 Pixiv userId（PHPSESSID 下划线前缀）。 */
    public int updateAccountId(long id, String accountId) {
        return mapper.updateAccountId(id, accountId);
    }

    /** 账号级过度访问冻结：把同账号所有非挂起态任务一并标 OVERUSE_PAUSED。返回受影响行数。 */
    public int freezeAccount(String accountId, String status, String message) {
        return mapper.freezeAccount(accountId, status, message);
    }

    /** 记录管理员已显式放行的最新警告 modifiedAt（同账号）。 */
    public int updateAckWarning(String accountId, Long ackTime) {
        return mapper.updateAckWarning(accountId, ackTime);
    }

    /** 账号级（过度访问）恢复：同账号仅 OVERUSE_PAUSED 任务清挂起 + 重置 next_run。 */
    public int clearSuspendForAccount(String accountId, Long nextRun) {
        return mapper.clearSuspendForAccount(accountId, nextRun);
    }

    /** 单任务恢复：清挂起 + 重置 next_run + 清中断哨兵。 */
    public int clearSuspend(long id, Long nextRun) {
        return mapper.clearSuspend(id, nextRun);
    }

    /** 单任务、仅当当前状态为指定挂起态时清挂起（按入口类型限定恢复）。 */
    public int clearSuspendIfStatus(long id, Long nextRun, String expectedStatus) {
        return mapper.clearSuspendIfStatus(id, nextRun, expectedStatus);
    }

    // ── scheduled_task_pending（单作品隔离重试表）CRUD ────────────────────────────

    public int insertPending(long taskId, long workId, String reason, long now) {
        return mapper.insertPending(taskId, workId, reason, now);
    }

    public int deletePending(long taskId, long workId) {
        return mapper.deletePending(taskId, workId);
    }

    public int incPendingAttempts(long taskId, long workId, long now) {
        return mapper.incPendingAttempts(taskId, workId, now);
    }

    /** 取单行 {@code attempts} 标量；不存在返回 {@code null}。 */
    public Integer selectPendingAttempts(long taskId, long workId) {
        return mapper.selectPendingAttempts(taskId, workId);
    }

    public List<ScheduledTaskPending> listPending(long taskId) {
        return mapper.listPending(taskId);
    }

    public int deleteAllPending(long taskId) {
        return mapper.deleteAllPending(taskId);
    }
}
