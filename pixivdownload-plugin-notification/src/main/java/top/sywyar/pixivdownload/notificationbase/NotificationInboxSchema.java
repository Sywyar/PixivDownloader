package top.sywyar.pixivdownload.notificationbase;

import top.sywyar.pixivdownload.plugin.api.schema.ColumnSpec;
import top.sywyar.pixivdownload.plugin.api.schema.IndexOrigin;
import top.sywyar.pixivdownload.plugin.api.schema.IndexSpec;
import top.sywyar.pixivdownload.plugin.api.schema.SchemaContribution;
import top.sywyar.pixivdownload.plugin.api.schema.TableSpec;

import java.util.List;

/** 站内信插件自有持久化模型；宿主 schema registry 统一执行 DDL。 */
public final class NotificationInboxSchema {

    public static final SchemaContribution CONTRIBUTION = new SchemaContribution(
            List.of(new TableSpec(
                    "notification_messages",
                    List.of(
                            column("id", "TEXT", true, null, 1),
                            column("category", "TEXT", true, null, 0),
                            column("severity", "TEXT", true, null, 0),
                            column("scenario_id", "TEXT", false, null, 0),
                            column("title", "TEXT", true, null, 0),
                            column("body", "TEXT", true, null, 0),
                            column("content_url", "TEXT", false, null, 0),
                            column("content_html", "TEXT", false, null, 0),
                            column("action_url", "TEXT", false, null, 0),
                            column("created_time", "INTEGER", true, null, 0),
                            column("read_time", "INTEGER", false, null, 0),
                            column("deleted_time", "INTEGER", false, null, 0),
                            column("active", "INTEGER", true, "1", 0)),
                    List.of(
                            index("idx_notification_messages_created_time", "created_time"),
                            index("idx_notification_messages_unread_created", "read_time", "created_time")),
                    "category IN ('download','announcement','survey','system')"
                            + " AND severity IN ('INFO','WARNING','ERROR')"
                            + " AND length(trim(title)) > 0"
                            + " AND length(trim(body)) > 0"
                            + " AND (content_html IS NULL OR length(content_html) > 0)"
                            + " AND created_time >= 0"
                            + " AND active IN (0,1)"
                            + " AND (read_time IS NULL OR read_time >= created_time)"
                            + " AND (deleted_time IS NULL OR deleted_time >= created_time)"),
                    new TableSpec(
                            "notification_announcement_translations",
                            List.of(
                                    column("announcement_id", "TEXT", true, null, 1),
                                    column("locale", "TEXT", true, null, 2),
                                    column("title", "TEXT", true, null, 0),
                                    column("summary", "TEXT", true, null, 0),
                                    column("content_url", "TEXT", true, null, 0),
                                    column("content_html", "TEXT", true, null, 0)),
                            List.of(),
                            "length(trim(announcement_id)) > 0"
                                    + " AND length(trim(locale)) > 0"
                                    + " AND length(trim(title)) > 0"
                                    + " AND length(trim(summary)) > 0"
                                    + " AND length(trim(content_url)) > 0"
                                    + " AND length(content_html) > 0")),
            List.of(),
            List.of());

    private NotificationInboxSchema() {
    }

    private static ColumnSpec column(String name, String type, boolean notNull,
                                     String defaultValue, int primaryKeyPosition) {
        return new ColumnSpec(name, type, notNull, defaultValue, primaryKeyPosition);
    }

    private static IndexSpec index(String name, String... columns) {
        return new IndexSpec(name, IndexOrigin.CREATE_INDEX, false, List.of(columns));
    }
}
