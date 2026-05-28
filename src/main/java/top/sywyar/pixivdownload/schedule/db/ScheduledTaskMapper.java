package top.sywyar.pixivdownload.schedule.db;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import top.sywyar.pixivdownload.schedule.ScheduledTask;
import top.sywyar.pixivdownload.schedule.ScheduledTaskType;

import java.util.List;

/**
 * {@code scheduled_tasks} 表的数据访问。
 *
 * <p><b>cookie 红线：</b>{@code cookie_snapshot} 是完整登录凭证，<b>绝不</b>出现在
 * {@link #SELECT_TASK} 这类行投影里（列表 / 详情 / 导出都走它，恒为 {@code null}）。
 * 调度器需要凭证时单独调 {@link #findCookieSnapshot(long)} 取裸标量，结果只在服务内部使用、
 * 绝不写日志 / 回显。
 */
@Mapper
public interface ScheduledTaskMapper {

    /** 不含 cookie_snapshot 的行投影；cookieSnapshot 组件在映射时恒为 null。 */
    String SELECT_TASK = "SELECT id, name, enabled, type, params_json AS paramsJson,"
            + " trigger_kind AS triggerKind, interval_minutes AS intervalMinutes,"
            + " cron_expr AS cronExpr, cookie_mode AS cookieMode,"
            + " next_run_time AS nextRunTime, last_run_time AS lastRunTime,"
            + " last_status AS lastStatus, last_message AS lastMessage,"
            + " watermark_id AS watermarkId, run_started_time AS runStartedTime,"
            + " account_id AS accountId, ack_warning_time AS ackWarningTime,"
            + " pending_retry_armed AS pendingRetryArmed,"
            + " created_time AS createdTime"
            + " FROM scheduled_tasks";

    // ── DDL ────────────────────────────────────────────────────────────────────

    @Update("CREATE TABLE IF NOT EXISTS scheduled_tasks ("
            + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + "name TEXT NOT NULL,"
            + "enabled INTEGER NOT NULL DEFAULT 1,"
            + "type TEXT NOT NULL,"
            + "params_json TEXT NOT NULL,"
            + "trigger_kind TEXT NOT NULL,"
            + "interval_minutes INTEGER,"
            + "cron_expr TEXT,"
            + "cookie_mode TEXT NOT NULL,"
            + "cookie_snapshot TEXT,"
            + "next_run_time INTEGER,"
            + "last_run_time INTEGER,"
            + "last_status TEXT,"
            + "last_message TEXT,"
            + "watermark_id INTEGER,"
            + "run_started_time INTEGER,"
            + "account_id TEXT,"
            + "ack_warning_time INTEGER,"
            + "pending_retry_armed INTEGER DEFAULT 0,"
            + "created_time INTEGER NOT NULL)")
    void createScheduledTasksTable();

    @Update("CREATE INDEX IF NOT EXISTS idx_scheduled_tasks_next_run ON scheduled_tasks(next_run_time)")
    void createScheduledTasksNextRunIndex();

    @Update("CREATE INDEX IF NOT EXISTS idx_scheduled_tasks_account ON scheduled_tasks(account_id)")
    void createScheduledTasksAccountIndex();

    /**
     * 隔离表（待重试）：每个因可恢复失败被跳过的单作品一行，{@code task_id} 区分多任务。
     * 解耦 watermark 与单作品重试——watermark 在有零星可恢复失败时照常推进，失败作品在此单独追踪。
     * 404 / 403-gone（真已删除）与被 filter 过滤的不进表。新表属当前未发布周期内引入，无需 ALTER 迁移兜底。
     */
    @Update("CREATE TABLE IF NOT EXISTS scheduled_task_pending ("
            + "task_id INTEGER NOT NULL,"
            + "work_id INTEGER NOT NULL,"
            + "reason TEXT,"
            + "attempts INTEGER DEFAULT 0,"
            + "first_seen_time INTEGER,"
            + "last_attempt_time INTEGER,"
            + "PRIMARY KEY(task_id, work_id))")
    void createScheduledTaskPendingTable();

    // ── 写入 ────────────────────────────────────────────────────────────────────

    @Insert("INSERT INTO scheduled_tasks(name, enabled, type, params_json, trigger_kind,"
            + " interval_minutes, cron_expr, cookie_mode, cookie_snapshot,"
            + " next_run_time, last_run_time, last_status, last_message,"
            + " watermark_id, run_started_time, account_id, ack_warning_time,"
            + " pending_retry_armed, created_time)"
            + " VALUES(#{name}, #{enabled}, #{type}, #{paramsJson}, #{triggerKind},"
            + " #{intervalMinutes}, #{cronExpr}, #{cookieMode}, #{cookieSnapshot},"
            + " #{nextRunTime}, #{lastRunTime}, #{lastStatus}, #{lastMessage},"
            + " #{watermarkId}, #{runStartedTime}, #{accountId}, #{ackWarningTime},"
            + " #{pendingRetryArmed}, #{createdTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    void insert(ScheduledTaskInsert task);

    @Update("UPDATE scheduled_tasks SET name = #{name}, type = #{type}, params_json = #{paramsJson},"
            + " trigger_kind = #{triggerKind}, interval_minutes = #{intervalMinutes},"
            + " cron_expr = #{cronExpr}, next_run_time = #{nextRunTime}, watermark_id = NULL"
            + " WHERE id = #{id}")
    int updateDefinition(@Param("id") long id,
                         @Param("name") String name,
                         @Param("type") ScheduledTaskType type,
                         @Param("paramsJson") String paramsJson,
                         @Param("triggerKind") String triggerKind,
                         @Param("intervalMinutes") Integer intervalMinutes,
                         @Param("cronExpr") String cronExpr,
                         @Param("nextRunTime") Long nextRunTime);

    @Update("UPDATE scheduled_tasks SET enabled = #{enabled} WHERE id = #{id}")
    int updateEnabled(@Param("id") long id, @Param("enabled") boolean enabled);

    @Update("UPDATE scheduled_tasks SET cookie_snapshot = #{cookieSnapshot}, cookie_mode = #{cookieMode}"
            + " WHERE id = #{id}")
    int updateCookie(@Param("id") long id,
                     @Param("cookieSnapshot") String cookieSnapshot,
                     @Param("cookieMode") String cookieMode);

    /**
     * 本轮跑完落库结果。<b>CASE 保留运行中被手动设置的 {@code PAUSED}</b>：
     * 用户在任务运行过程中点了「暂停」时，{@link #setStatus} 已把 {@code last_status} 写成 {@code PAUSED}；
     * 这里若直接覆盖会让暂停被吞掉、下一轮 tick 又把任务挑回来跑。SQLite 中 SET 子句的所有表达式按行旧值求值，
     * 三个 CASE 都对 <i>本轮跑之前</i> 的 {@code last_status} 做判断，原子无 TOCTOU。
     */
    @Update("UPDATE scheduled_tasks SET last_run_time = #{lastRunTime},"
            + " last_status = CASE WHEN last_status = 'PAUSED' THEN 'PAUSED' ELSE #{lastStatus} END,"
            + " last_message = CASE WHEN last_status = 'PAUSED' THEN last_message ELSE #{lastMessage} END,"
            + " next_run_time = CASE WHEN last_status = 'PAUSED' THEN next_run_time ELSE #{nextRunTime} END,"
            + " run_started_time = NULL WHERE id = #{id}")
    int updateRunResult(@Param("id") long id,
                        @Param("lastRunTime") Long lastRunTime,
                        @Param("lastStatus") String lastStatus,
                        @Param("lastMessage") String lastMessage,
                        @Param("nextRunTime") Long nextRunTime);

    /** 进入执行时落库本轮开始时刻；进程被强杀（未走到 {@link #updateRunResult}）即残留为「上次运行被中断」信号。 */
    @Update("UPDATE scheduled_tasks SET run_started_time = #{runStartedTime} WHERE id = #{id}")
    int updateRunStarted(@Param("id") long id, @Param("runStartedTime") Long runStartedTime);

    /** 一轮完整跑完后更新水位线（本轮发现到的最新作品 ID）；异常 / 鉴权失效不更新。 */
    @Update("UPDATE scheduled_tasks SET watermark_id = #{watermarkId} WHERE id = #{id}")
    int updateWatermark(@Param("id") long id, @Param("watermarkId") Long watermarkId);

    @Delete("DELETE FROM scheduled_tasks WHERE id = #{id}")
    int delete(@Param("id") long id);

    // ── 读取（无 cookie） ────────────────────────────────────────────────────────

    @Select(SELECT_TASK + " ORDER BY id DESC")
    List<ScheduledTask> findAll();

    @Select(SELECT_TASK + " WHERE id = #{id}")
    ScheduledTask findById(@Param("id") long id);

    /**
     * 到期 <b>或被进程强杀中断</b>、且未被挂起的任务。挂起态（{@code OVERUSE_PAUSED} 账号级过度访问、
     * {@code AUTH_EXPIRED} cookie 失效、{@code PAUSED} 管理员手动）由状态门挡住，不再每周期重撞——
     * 等待管理员显式恢复。
     *
     * <p><b>{@code run_started_time IS NOT NULL} 分支 = 崩溃即时重跑：</b>正常运行结束 {@code updateRunResult}
     * 会清 {@code run_started_time}；若进程在一轮运行中途被强杀则残留。重启后哪怕该任务的 {@code next_run_time}
     * 还在未来（典型如「立即运行」触发、尚未到周期就被中断的那一轮），也要立刻重跑补齐，而不是傻等到下次周期。
     * 正在运行的任务虽也匹配此分支，但有内存 Claim 占位，{@code tryMarkQueued} 会失败、tick 跳过，不会重复触发。
     */
    @Select(SELECT_TASK + " WHERE enabled = 1"
            + " AND (last_status IS NULL OR last_status NOT IN ('OVERUSE_PAUSED','AUTH_EXPIRED','PAUSED'))"
            + " AND ((next_run_time IS NOT NULL AND next_run_time <= #{now})"
            + "      OR run_started_time IS NOT NULL)"
            + " ORDER BY next_run_time")
    List<ScheduledTask> findDue(@Param("now") long now);

    @Select("SELECT COUNT(*) FROM scheduled_tasks")
    int countAll();

    @Select(SELECT_TASK + " WHERE account_id = #{accountId} ORDER BY id DESC")
    List<ScheduledTask> findByAccountId(@Param("accountId") String accountId);

    // ── 状态 / 挂起 / 账号冻结 / 重试武装 ─────────────────────────────────────────

    /** 仅置状态（手动暂停等），不动其它列。 */
    @Update("UPDATE scheduled_tasks SET last_status = #{status} WHERE id = #{id}")
    int setStatus(@Param("id") long id, @Param("status") String status);

    /** 写入授权解析出的非敏感 Pixiv userId（PHPSESSID 下划线前缀）。 */
    @Update("UPDATE scheduled_tasks SET account_id = #{accountId} WHERE id = #{id}")
    int updateAccountId(@Param("id") long id, @Param("accountId") String accountId);

    /**
     * 账号级过度访问冻结：把同账号所有<b>非挂起态</b>任务一并标 {@code OVERUSE_PAUSED} + 提示文案，
     * 挡住「还没轮到跑」的兄弟任务（「此刻正在下载」的兄弟靠其自身轮内 N 检查点停下）。
     */
    @Update("UPDATE scheduled_tasks SET last_status = #{status}, last_message = #{message},"
            + " run_started_time = NULL"
            + " WHERE account_id = #{accountId}"
            + " AND (last_status IS NULL OR last_status NOT IN ('OVERUSE_PAUSED','AUTH_EXPIRED','PAUSED'))")
    int freezeAccount(@Param("accountId") String accountId,
                      @Param("status") String status,
                      @Param("message") String message);

    /** 记录管理员已显式放行的最新警告 modifiedAt（同账号），此后不超过它的警告不再触发暂停。 */
    @Update("UPDATE scheduled_tasks SET ack_warning_time = #{ackTime} WHERE account_id = #{accountId}")
    int updateAckWarning(@Param("accountId") String accountId, @Param("ackTime") Long ackTime);

    /** 账号级恢复：同账号所有挂起任务清挂起 + 重置 next_run + 清中断哨兵。 */
    @Update("UPDATE scheduled_tasks SET last_status = NULL, last_message = NULL,"
            + " next_run_time = #{nextRun}, run_started_time = NULL"
            + " WHERE account_id = #{accountId}"
            + " AND last_status IN ('OVERUSE_PAUSED','AUTH_EXPIRED','PAUSED')")
    int clearSuspendForAccount(@Param("accountId") String accountId, @Param("nextRun") Long nextRun);

    /**
     * 单任务恢复：清挂起 + 重置 next_run + 清中断哨兵。
     * 同清 {@code run_started_time}：若任务在「暂停途中进程被强杀」的窗口里留下了残留哨兵，
     * 恢复时一并清除，避免恢复后仍被误显示为「上次运行被中断」红灯。
     */
    @Update("UPDATE scheduled_tasks SET last_status = NULL, last_message = NULL,"
            + " next_run_time = #{nextRun}, run_started_time = NULL WHERE id = #{id}")
    int clearSuspend(@Param("id") long id, @Param("nextRun") Long nextRun);

    /** 管理员处理完异常后武装：下一轮运行开始先把隔离表入队重试。 */
    @Update("UPDATE scheduled_tasks SET pending_retry_armed = 1 WHERE id = #{id}")
    int armRetry(@Param("id") long id);

    /** 重试隔离表后清除武装位。 */
    @Update("UPDATE scheduled_tasks SET pending_retry_armed = 0 WHERE id = #{id}")
    int clearRetryArmed(@Param("id") long id);

    // ── 隔离表 CRUD ──────────────────────────────────────────────────────────────

    /** 记录可恢复失败的单作品；冲突时保留 {@code first_seen_time}、不重置 {@code attempts}，仅刷新原因与时刻。 */
    @Insert("INSERT INTO scheduled_task_pending(task_id, work_id, reason, attempts,"
            + " first_seen_time, last_attempt_time)"
            + " VALUES(#{taskId}, #{workId}, #{reason}, 0, #{now}, #{now})"
            + " ON CONFLICT(task_id, work_id) DO UPDATE SET reason = excluded.reason,"
            + " last_attempt_time = excluded.last_attempt_time")
    int insertPending(@Param("taskId") long taskId, @Param("workId") long workId,
                      @Param("reason") String reason, @Param("now") long now);

    @Delete("DELETE FROM scheduled_task_pending WHERE task_id = #{taskId} AND work_id = #{workId}")
    int deletePending(@Param("taskId") long taskId, @Param("workId") long workId);

    @Update("UPDATE scheduled_task_pending SET attempts = attempts + 1, last_attempt_time = #{now}"
            + " WHERE task_id = #{taskId} AND work_id = #{workId}")
    int incPendingAttempts(@Param("taskId") long taskId, @Param("workId") long workId,
                           @Param("now") long now);

    /** 取单行 {@code attempts} 标量；不存在返回 {@code null}。用于在 {@link #incPendingAttempts} 后判断是否刚跨过上限。 */
    @Select("SELECT attempts FROM scheduled_task_pending WHERE task_id = #{taskId} AND work_id = #{workId}")
    Integer selectPendingAttempts(@Param("taskId") long taskId, @Param("workId") long workId);

    @Select("SELECT task_id AS taskId, work_id AS workId, reason, attempts,"
            + " first_seen_time AS firstSeenTime, last_attempt_time AS lastAttemptTime"
            + " FROM scheduled_task_pending WHERE task_id = #{taskId} ORDER BY first_seen_time")
    List<ScheduledTaskPending> listPending(@Param("taskId") long taskId);

    @Delete("DELETE FROM scheduled_task_pending WHERE task_id = #{taskId}")
    int deleteAllPending(@Param("taskId") long taskId);

    // ── cookie 专用裸标量取值（仅供调度器内部） ─────────────────────────────────────

    @Select("SELECT cookie_snapshot FROM scheduled_tasks WHERE id = #{id}")
    String findCookieSnapshot(@Param("id") long id);
}
