package top.sywyar.pixivdownload.schedule.db;

import top.sywyar.pixivdownload.core.db.CoreSchemaContribution;
import top.sywyar.pixivdownload.plugin.api.schema.SchemaContribution;
import top.sywyar.pixivdownload.plugin.api.schema.TableSpec;

import java.util.List;

import static top.sywyar.pixivdownload.core.db.SchemaSpecs.autoIncrementPrimaryKey;
import static top.sywyar.pixivdownload.core.db.SchemaSpecs.column;
import static top.sywyar.pixivdownload.core.db.SchemaSpecs.explicitIndex;

/**
 * 计划任务域 schema 的 contribution 声明（任务表与单作品隔离重试表）。
 * 暂无独立 schedule 插件，ownerPluginId 为 core；未来若立插件且通过卸载投影测试再移交。
 */
public final class ScheduleSchemaContribution {

    public static final SchemaContribution CONTRIBUTION = createContribution();

    private ScheduleSchemaContribution() {}

    private static SchemaContribution createContribution() {
        List<TableSpec> tables = List.of(
                new TableSpec(
                        "scheduled_tasks",
                        List.of(
                                autoIncrementPrimaryKey("id"),
                                column("name", "TEXT", true, null, 0),
                                column("enabled", "INTEGER", true, "1", 0),
                                column("type", "TEXT", true, null, 0),
                                column("params_json", "TEXT", true, null, 0),
                                column("trigger_kind", "TEXT", true, null, 0),
                                column("interval_minutes", "INTEGER", false, null, 0),
                                column("cron_expr", "TEXT", false, null, 0),
                                column("cookie_mode", "TEXT", true, null, 0),
                                column("cookie_snapshot", "TEXT", false, null, 0),
                                column("proxy_snapshot", "TEXT", false, null, 0),
                                column("next_run_time", "INTEGER", false, null, 0),
                                column("last_run_time", "INTEGER", false, null, 0),
                                column("last_status", "TEXT", false, null, 0),
                                column("last_message", "TEXT", false, null, 0),
                                column("watermark_id", "INTEGER", false, null, 0),
                                column("run_started_time", "INTEGER", false, null, 0),
                                column("account_id", "TEXT", false, null, 0),
                                column("ack_warning_time", "INTEGER", false, null, 0),
                                column("pending_retry_armed", "INTEGER", false, "0", 0),
                                column("created_time", "INTEGER", true, null, 0)
                        ),
                        List.of(
                                explicitIndex("idx_scheduled_tasks_next_run", false, "next_run_time"),
                                explicitIndex("idx_scheduled_tasks_account", false, "account_id")
                        )
                ),
                new TableSpec(
                        "scheduled_task_pending",
                        List.of(
                                column("task_id", "INTEGER", true, null, 1),
                                column("work_id", "INTEGER", true, null, 2),
                                column("reason", "TEXT", false, null, 0),
                                column("attempts", "INTEGER", false, "0", 0),
                                column("first_seen_time", "INTEGER", false, null, 0),
                                column("last_attempt_time", "INTEGER", false, null, 0)
                        ),
                        List.of()
                )
        );

        return new SchemaContribution(CoreSchemaContribution.OWNER_PLUGIN_ID,
                tables, List.of(), List.of(), List.of());
    }
}
