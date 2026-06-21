package top.sywyar.pixivdownload.core.schedule;

import java.util.List;

/**
 * {@code scheduled_tasks} / {@code scheduled_task_pending} 两张<b>核心 owned</b> 表的语义数据访问门面。
 *
 * <p><b>归属与边界：</b>这是<b>核心 owned 的语义 Store / API</b>（住 {@code core.schedule}），不是某个插件
 * 私有的数据库实现类。计划任务调度引擎（{@code ScheduleExecutor} / {@code ScheduleService} /
 * {@code ScheduleRunner} 等）随 schedule 能力收编进下载工作台插件、为
 * {@link top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean}；它们<b>只依赖本接口</b>表达的业务意图，
 * <b>不</b>触达底层 MyBatis mapper、池化 {@code DataSource}、{@code JdbcTemplate}、裸 {@code Connection} 或可
 * 自由拼接的 SQL。底层实现（mapper、schema 初始化、数据库方言适配）收口在核心实现层 {@code core.schedule.db}，
 * 对插件不可见——插件不应关心底层是 SQLite、PostgreSQL、MySQL 还是其它存储。
 *
 * <p>本接口<b>只</b>表达调度引擎实际需要的读写意图，<b>不</b>暴露 mapper accessor、裸连接或自由 SQL 入口。
 *
 * <p><b>cookie 红线：</b>{@code cookie_snapshot} 是一份完整的登录凭证，<b>绝不</b>出现在 {@link #findAll()} /
 * {@link #findById(long)} 这类行投影里（恒为 {@code null}）；调度器需要凭证时单独调
 * {@link #findCookieSnapshot(long)} 取裸标量，结果只在服务内部使用、绝不写日志 / 回显。
 */
public interface ScheduledTaskStore {

    // ── scheduled_tasks 读取（无 cookie） ──────────────────────────────────────────

    List<ScheduledTask> findAll();

    ScheduledTask findById(long id);

    int countAll();

    /** 到期或被进程强杀中断、且未被挂起的任务。 */
    List<ScheduledTask> findDue(long now);

    List<ScheduledTask> findByAccountId(String accountId);

    /** cookie 专用裸标量取值（仅供调度器内部，绝不写日志 / 回显）。 */
    String findCookieSnapshot(long id);

    // ── scheduled_tasks 写入 ──────────────────────────────────────────────────────

    void insert(ScheduledTaskInsert task);

    int updateDefinition(long id, String name, ScheduledTaskType type, String paramsJson,
                         String triggerKind, Integer intervalMinutes, String cronExpr,
                         Long nextRunTime);

    int updateEnabled(long id, boolean enabled);

    int updateCookie(long id, String cookieSnapshot, String cookieMode);

    /** 解除授权 / 失效自动降级：清 Cookie 转受限并清除由 Cookie 派生的账号绑定。 */
    int clearCookieAndAccount(long id, String cookieMode);

    /** 设置 / 清除任务级单独代理（host:port，非凭证；{@code null} = 回退全局代理设置）。 */
    int updateProxy(long id, String proxySnapshot);

    /** 本轮跑完落库结果（保留运行中被手动设置的 PAUSED）。 */
    int updateRunResult(long id, Long lastRunTime, String lastStatus, String lastMessage,
                        Long nextRunTime);

    int updateRunStarted(long id, Long runStartedTime);

    int updateWatermark(long id, Long watermarkId);

    int delete(long id);

    // ── 状态 / 挂起 / 账号冻结 ─────────────────────────────────────────────────────

    /** 仅置状态（手动暂停等），不动其它列。 */
    int setStatus(long id, String status);

    /** 写入授权解析出的非敏感 Pixiv userId（PHPSESSID 下划线前缀）。 */
    int updateAccountId(long id, String accountId);

    /** 账号级过度访问冻结：把同账号所有非挂起态任务一并标 OVERUSE_PAUSED。返回受影响行数。 */
    int freezeAccount(String accountId, String status, String message);

    /** 记录管理员已显式放行的最新警告 modifiedAt（同账号）。 */
    int updateAckWarning(String accountId, Long ackTime);

    /** 账号级（过度访问）恢复：同账号仅 OVERUSE_PAUSED 任务清挂起 + 重置 next_run。 */
    int clearSuspendForAccount(String accountId, Long nextRun);

    /** 单任务恢复：清挂起 + 重置 next_run + 清中断哨兵。 */
    int clearSuspend(long id, Long nextRun);

    /** 单任务、仅当当前状态为指定挂起态时清挂起（按入口类型限定恢复）。 */
    int clearSuspendIfStatus(long id, Long nextRun, String expectedStatus);

    // ── scheduled_task_pending（单作品隔离重试表）CRUD ────────────────────────────

    int insertPending(long taskId, long workId, String reason, long now);

    int deletePending(long taskId, long workId);

    int incPendingAttempts(long taskId, long workId, long now);

    /** 取单行 {@code attempts} 标量；不存在返回 {@code null}。 */
    Integer selectPendingAttempts(long taskId, long workId);

    List<ScheduledTaskPending> listPending(long taskId);

    int deleteAllPending(long taskId);
}
