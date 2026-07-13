package top.sywyar.pixivdownload.core.schedule.db;

import top.sywyar.pixivdownload.core.db.schema.contribution.CoreSchemaContribution;
import top.sywyar.pixivdownload.plugin.api.schema.SchemaContribution;
import top.sywyar.pixivdownload.plugin.api.schema.TableSpec;

import java.util.List;

import static top.sywyar.pixivdownload.core.db.schema.SchemaSpecs.autoIncrementPrimaryKey;
import static top.sywyar.pixivdownload.core.db.schema.SchemaSpecs.column;
import static top.sywyar.pixivdownload.core.db.schema.SchemaSpecs.explicitIndex;

/**
 * 计划任务域 schema 的 contribution 声明。
 *
 * <p>{@code scheduled_tasks} / {@code scheduled_task_pending_work} /
 * {@code scheduled_task_credentials} 是<b>核心 owned</b> 长期事实表。
 * {@code scheduled_task_pending} 仅作为已发布旧库的迁移输入保留，新写入不再以它为事实源。
 * 全部表都按「卸载投影」原则归核心（ownerPluginId = core）；调度引擎虽在下载工作台插件，
 * 长期数据的语义访问门面仍是核心 owned 的 {@code core.schedule.ScheduledTaskStore}。
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
                                column("source_owner_plugin_id", "TEXT", false, null, 0),
                                column("definition_schema", "TEXT", false, null, 0),
                                column("definition_version", "INTEGER", false, null, 0),
                                column("presentation_json", "TEXT", false, null, 0),
                                column("checkpoint_schema", "TEXT", false, null, 0),
                                column("checkpoint_version", "INTEGER", false, null, 0),
                                column("checkpoint_json", "TEXT", false, null, 0),
                                column("storage_version", "INTEGER", true, "0", 0),
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
                                column("run_state", "TEXT", false, null, 0),
                                column("run_claim_token", "TEXT", false, null, 0),
                                column("last_outcome", "TEXT", true, "'NEVER'", 0),
                                column("outcome_code", "TEXT", false, null, 0),
                                column("outcome_message", "TEXT", false, null, 0),
                                column("suspend_reason", "TEXT", false, null, 0),
                                column("suspend_code", "TEXT", false, null, 0),
                                column("suspend_detail_json", "TEXT", false, null, 0),
                                column("state_version", "INTEGER", true, "0", 0),
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
                ),
                new TableSpec(
                        "scheduled_task_pending_work",
                        List.of(
                                column("task_id", "INTEGER", true, null, 1),
                                column("work_type", "TEXT", true, null, 2),
                                column("work_id", "TEXT", true, null, 3),
                                column("payload_schema", "TEXT", true, null, 0),
                                column("payload_version", "INTEGER", true, null, 0),
                                column("payload_json", "TEXT", true, null, 0),
                                column("presentation_json", "TEXT", false, null, 0),
                                column("relations_json", "TEXT", true, "'[]'", 0),
                                column("reason_code", "TEXT", false, null, 0),
                                column("reason_detail_json", "TEXT", false, null, 0),
                                column("attempts", "INTEGER", true, "0", 0),
                                column("first_seen_time", "INTEGER", false, null, 0),
                                column("last_attempt_time", "INTEGER", false, null, 0)
                        ),
                        List.of()
                ),
                new TableSpec(
                        "scheduled_task_credentials",
                        List.of(
                                column("task_id", "INTEGER", true, null, 1),
                                column("policy_owner_plugin_id", "TEXT", true, null, 0),
                                column("policy_id", "TEXT", true, null, 0),
                                column("account_key", "TEXT", false, null, 0),
                                column("secret", "TEXT", false, null, 0),
                                column("secret_reference", "TEXT", false, null, 0),
                                column("policy_state_json", "TEXT", true, "'{}'", 0),
                                column("updated_time", "INTEGER", true, null, 0)
                        ),
                        List.of()
                )
        );

        return new SchemaContribution(CoreSchemaContribution.OWNER_PLUGIN_ID,
                tables, List.of(), List.of(), List.of());
    }
}
