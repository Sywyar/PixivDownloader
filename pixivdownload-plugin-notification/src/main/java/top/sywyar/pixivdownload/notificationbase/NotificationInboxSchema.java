package top.sywyar.pixivdownload.notificationbase;

import top.sywyar.pixivdownload.plugin.api.schema.ColumnSpec;
import top.sywyar.pixivdownload.plugin.api.schema.IndexOrigin;
import top.sywyar.pixivdownload.plugin.api.schema.IndexSpec;
import top.sywyar.pixivdownload.plugin.api.schema.SchemaContribution;
import top.sywyar.pixivdownload.plugin.api.schema.TableSpec;

import java.util.List;
import top.sywyar.pixivdownload.plugin.api.storage.relational.Expression;
import static top.sywyar.pixivdownload.plugin.api.storage.relational.Expression.*;
import static top.sywyar.pixivdownload.plugin.api.storage.relational.Expression.Operator.*;

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
                    null).withCheck(messagesCheck()),
                    new TableSpec(
                            "notification_announcement_translations",
                            List.of(
                                    column("announcement_id", "TEXT", true, null, 1),
                                    column("locale", "TEXT", true, null, 2),
                                    column("title", "TEXT", true, null, 0),
                                    column("summary", "TEXT", true, null, 0),
                                    column("content_url", "TEXT", true, null, 0),
                                    column("content_sha256", "TEXT", false, null, 0),
                                    column("content_html", "TEXT", true, null, 0)),
                            List.of(),
                            null).withCheck(translationsCheck()),
                    new TableSpec(
                            "notification_remote_index_state",
                            List.of(
                                    column("id", "INTEGER", true, null, 1),
                                    column("sequence", "INTEGER", true, null, 0),
                                    column("manifest_sha256", "TEXT", true, null, 0),
                                    column("generated_time", "INTEGER", true, null, 0),
                                    column("expires_time", "INTEGER", true, null, 0),
                                    column("etag", "TEXT", false, null, 0),
                                    column("last_modified", "TEXT", false, null, 0)),
                            List.of(),
                            null).withCheck(remoteCheck())),
            List.of(),
            List.of());

    private NotificationInboxSchema() {
    }

    private static Expression messagesCheck() {
        String t = "notification_messages";
        return call(AND,
                call(IN, Expression.column(t, "category"), value("download"), value("announcement"), value("survey"), value("system")),
                call(IN, Expression.column(t, "severity"), value("INFO"), value("WARNING"), value("ERROR")),
                nonblank(t, "title"), nonblank(t, "body"),
                call(OR, call(IS_NULL, Expression.column(t, "content_html")), positiveLength(t, "content_html")),
                call(GREATER_EQUAL, Expression.column(t, "created_time"), value(0)),
                call(IN, Expression.column(t, "active"), value(0), value(1)),
                call(OR, call(IS_NULL, Expression.column(t, "read_time")), call(GREATER_EQUAL, Expression.column(t, "read_time"), Expression.column(t, "created_time"))),
                call(OR, call(IS_NULL, Expression.column(t, "deleted_time")), call(GREATER_EQUAL, Expression.column(t, "deleted_time"), Expression.column(t, "created_time"))));
    }

    private static Expression translationsCheck() {
        String t = "notification_announcement_translations";
        return call(AND, nonblank(t, "announcement_id"), nonblank(t, "locale"), nonblank(t, "title"),
                nonblank(t, "summary"), nonblank(t, "content_url"),
                call(EQUAL, call(LENGTH, Expression.column(t, "content_sha256")), value(64)),
                call(LOWER_HEX, Expression.column(t, "content_sha256")), positiveLength(t, "content_html"));
    }

    private static Expression remoteCheck() {
        String t = "notification_remote_index_state";
        return call(AND, call(EQUAL, Expression.column(t, "id"), value(1)), call(GREATER, Expression.column(t, "sequence"), value(0)),
                call(EQUAL, call(LENGTH, Expression.column(t, "manifest_sha256")), value(64)),
                call(LOWER_HEX, Expression.column(t, "manifest_sha256")),
                call(GREATER_EQUAL, Expression.column(t, "generated_time"), value(0)),
                call(GREATER, Expression.column(t, "expires_time"), Expression.column(t, "generated_time")));
    }

    private static Expression nonblank(String table, String name) {
        return call(GREATER, call(LENGTH, call(TRIM, Expression.column(table, name))), value(0));
    }

    private static Expression positiveLength(String table, String name) {
        return call(GREATER, call(LENGTH, Expression.column(table, name)), value(0));
    }

    private static ColumnSpec column(String name, String type, boolean notNull,
                                     String defaultValue, int primaryKeyPosition) {
        return new ColumnSpec(name, type, notNull, defaultValue, primaryKeyPosition);
    }

    private static IndexSpec index(String name, String... columns) {
        return new IndexSpec(name, IndexOrigin.CREATE_INDEX, false, List.of(columns));
    }
}
