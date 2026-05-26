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
            + " last_status AS lastStatus, last_message AS lastMessage, created_time AS createdTime"
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
            + "created_time INTEGER NOT NULL)")
    void createScheduledTasksTable();

    @Update("CREATE INDEX IF NOT EXISTS idx_scheduled_tasks_next_run ON scheduled_tasks(next_run_time)")
    void createScheduledTasksNextRunIndex();

    // ── 写入 ────────────────────────────────────────────────────────────────────

    @Insert("INSERT INTO scheduled_tasks(name, enabled, type, params_json, trigger_kind,"
            + " interval_minutes, cron_expr, cookie_mode, cookie_snapshot,"
            + " next_run_time, last_run_time, last_status, last_message, created_time)"
            + " VALUES(#{name}, #{enabled}, #{type}, #{paramsJson}, #{triggerKind},"
            + " #{intervalMinutes}, #{cronExpr}, #{cookieMode}, #{cookieSnapshot},"
            + " #{nextRunTime}, #{lastRunTime}, #{lastStatus}, #{lastMessage}, #{createdTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    void insert(ScheduledTaskInsert task);

    @Update("UPDATE scheduled_tasks SET name = #{name}, type = #{type}, params_json = #{paramsJson},"
            + " trigger_kind = #{triggerKind}, interval_minutes = #{intervalMinutes},"
            + " cron_expr = #{cronExpr}, next_run_time = #{nextRunTime}"
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

    @Update("UPDATE scheduled_tasks SET last_run_time = #{lastRunTime}, last_status = #{lastStatus},"
            + " last_message = #{lastMessage}, next_run_time = #{nextRunTime} WHERE id = #{id}")
    int updateRunResult(@Param("id") long id,
                        @Param("lastRunTime") Long lastRunTime,
                        @Param("lastStatus") String lastStatus,
                        @Param("lastMessage") String lastMessage,
                        @Param("nextRunTime") Long nextRunTime);

    @Delete("DELETE FROM scheduled_tasks WHERE id = #{id}")
    int delete(@Param("id") long id);

    // ── 读取（无 cookie） ────────────────────────────────────────────────────────

    @Select(SELECT_TASK + " ORDER BY id DESC")
    List<ScheduledTask> findAll();

    @Select(SELECT_TASK + " WHERE id = #{id}")
    ScheduledTask findById(@Param("id") long id);

    @Select(SELECT_TASK + " WHERE enabled = 1 AND next_run_time IS NOT NULL"
            + " AND next_run_time <= #{now} ORDER BY next_run_time")
    List<ScheduledTask> findDue(@Param("now") long now);

    @Select("SELECT COUNT(*) FROM scheduled_tasks")
    int countAll();

    // ── cookie 专用裸标量取值（仅供调度器内部） ─────────────────────────────────────

    @Select("SELECT cookie_snapshot FROM scheduled_tasks WHERE id = #{id}")
    String findCookieSnapshot(@Param("id") long id);
}
