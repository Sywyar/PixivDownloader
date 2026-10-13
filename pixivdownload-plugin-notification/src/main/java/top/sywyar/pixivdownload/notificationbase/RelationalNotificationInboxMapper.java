package top.sywyar.pixivdownload.notificationbase;

import top.sywyar.pixivdownload.plugin.api.storage.PluginMainDatabase;
import top.sywyar.pixivdownload.plugin.api.storage.relational.*;
import java.util.*;
import static top.sywyar.pixivdownload.plugin.api.storage.relational.Expression.*;
import static top.sywyar.pixivdownload.plugin.api.storage.relational.Expression.Operator.*;

/** 站内信的持久化意图；提供者执行查询与原子写入。 */
public final class RelationalNotificationInboxMapper implements NotificationInboxMapper {
    private static final String M = "notification_messages", T = "notification_announcement_translations",
            S = "notification_remote_index_state";
    private final PluginMainDatabase database;
    public RelationalNotificationInboxMapper(PluginMainDatabase database) { this.database = database; }
    private static Expression eq(String table, String name, Object v) { return call(EQUAL, column(table, name), value(v)); }
    private static Expression visible() { return call(AND, call(IS_NULL, column(M, "deleted_time")), eq(M, "active", 1)); }
    private static Expression announcement(String id) { return call(AND, visible(), eq(M, "id", id), eq(M, "category", "announcement")); }
    private static Expression unread(String category) {
        var condition = call(AND, visible(), call(IS_NULL, column(M, "read_time")));
        return category == null ? condition : call(AND, condition, eq(M, "category", category));
    }
    private static Expression member(Expression column, List<String> values) {
        if (values == null || values.isEmpty()) return call(EQUAL, value(1), value(0));
        var args = new ArrayList<Expression>(); args.add(column);
        values.forEach(v -> args.add(value(v))); return new Call(IN, args);
    }
    private static Map<String, Object> row(Object... pairs) {
        var row = new LinkedHashMap<String, Object>();
        for (int i = 0; i < pairs.length; i += 2) row.put((String) pairs[i], pairs[i + 1]);
        return row;
    }
    private static Map<String, Expression> fields(String table, String... names) {
        var result = new LinkedHashMap<String, Expression>();
        for (String name : names) result.put(name, column(table, name));
        return result;
    }
    private static Query query(String table, Map<String, Expression> fields, Expression where) {
        return new Query(table, fields, where, List.of(), List.of(), Integer.MAX_VALUE, 0);
    }
    private static Map<String, Object> first(List<Map<String, Object>> rows) { return rows.isEmpty() ? null : rows.get(0); }
    private static Map<String, Expression> messageFields() {
        var result = fields(M, "id", "category", "severity", "scenario_id", "title", "body", "content_url",
                "action_url", "created_time", "read_time");
        result.put("content_html", new Conditional(call(IS_NULL, column(M, "content_html")), value(null), value("")));
        return result;
    }
    private static NotificationMessage message(Map<String, Object> r) {
        return r == null ? null : new NotificationMessage((String) r.get("id"), (String) r.get("category"),
                (String) r.get("severity"), (String) r.get("scenario_id"), (String) r.get("title"), (String) r.get("body"),
                (String) r.get("content_url"), (String) r.get("content_html"), (String) r.get("action_url"),
                (Long) r.get("created_time"), (Long) r.get("read_time"));
    }
    private static NotificationHtmlContent html(Map<String, Object> r) {
        return r == null ? null : new NotificationHtmlContent((String) r.get("content_url"), (String) r.get("content_html"));
    }
    public int insert(NotificationMessage m) {
        return database.transaction(s -> s.insert(M, row("id", m.id(), "category", m.category(), "severity", m.severity(),
                "scenario_id", m.scenarioId(), "title", m.title(), "body", m.body(), "content_url", m.contentUrl(),
                "content_html", m.contentHtml(), "action_url", m.actionUrl(), "created_time", m.createdTime(),
                "read_time", m.readTime()), DatabaseSession.Conflict.ignore("id")));
    }
    public List<NotificationMessage> findLatest(String category, boolean unreadOnly, int limit) {
        Expression where = unreadOnly ? unread(category) : visible();
        if (!unreadOnly && category != null) where = call(AND, where, eq(M, "category", category));
        var q = new Query(M, messageFields(), where, List.of(),
                List.of(new Query.Order(column(M, "created_time"), true, false),
                        new Query.Order(column(M, "id"), true, false)), limit, 0);
        return database.read(s -> s.query(q)).stream().map(RelationalNotificationInboxMapper::message).toList();
    }
    public NotificationMessage findById(String id) {
        return message(first(database.read(s -> s.query(query(M, messageFields(), call(AND, visible(), eq(M, "id", id)))))));
    }
    public NotificationHtmlContent findHtmlContent(String id) {
        return html(first(database.read(s -> s.query(query(M, fields(M, "content_url", "content_html"),
                call(AND, visible(), eq(M, "id", id), call(NOT, call(IS_NULL, column(M, "content_html")))))))));
    }
    public boolean blocksRemoteAnnouncementImport(String id) {
        return database.read(s -> (Long) s.query(Query.count(M, call(AND, eq(M, "id", id),
                call(OR, call(NOT_EQUAL, column(M, "category"), value("announcement")),
                        call(NOT, call(IS_NULL, column(M, "deleted_time"))),
                        call(NOT_EQUAL, column(M, "active"), value(1)))))).get(0).get("count")) > 0;
    }
    public int updateRemoteAnnouncement(NotificationMessage m) {
        return database.transaction(s -> s.update(M, Map.of("severity", value(m.severity()), "title", value(m.title()),
                "body", value(m.body()), "content_url", value(m.contentUrl()), "content_html", value(m.contentHtml())),
                call(AND, announcement(m.id()), eq(M, "created_time", m.createdTime()))));
    }
    private static Query translations(String id, String locale, boolean content) {
        var fields = content ? fields(T, "content_url", "content_html")
                : fields(T, "locale", "title", "summary", "content_url", "content_sha256");
        if (!content) fields.put("content_html",
                new Conditional(call(IS_NULL, column(T, "content_html")), value(null), value("")));
        Expression where = announcement(id);
        if (locale != null) where = call(AND, where, eq(T, "locale", locale));
        return new Query(new Query.Table(T), List.of(new Query.Join(Query.JoinKind.INNER, new Query.Table(M),
                call(EQUAL, column(M, "id"), column(T, "announcement_id")))),
                fields, where, List.of(), null, List.of(new Query.Order(column(T, "locale"), false, true)),
                false, Integer.MAX_VALUE, 0);
    }
    public List<RemoteAnnouncementTranslation> findRemoteAnnouncementTranslations(String id) {
        return database.read(s -> s.query(translations(id, null, false))).stream().map(r ->
                new RemoteAnnouncementTranslation((String) r.get("locale"), (String) r.get("title"),
                        (String) r.get("summary"), (String) r.get("content_url"),
                        (String) r.get("content_sha256"), (String) r.get("content_html"))).toList();
    }
    public NotificationHtmlContent findRemoteAnnouncementHtml(String id, String locale) {
        return html(first(database.read(s -> s.query(translations(id, locale, true)))));
    }
    public int upsertRemoteAnnouncementTranslation(String id, RemoteAnnouncementTranslation t) {
        var changes = new LinkedHashMap<String, Expression>();
        for (String field : List.of("title", "summary", "content_url", "content_sha256", "content_html"))
            changes.put(field, new Incoming(field));
        return database.transaction(s -> s.insert(T, row("announcement_id", id, "locale", t.locale(), "title", t.title(),
                "summary", t.summary(), "content_url", t.contentUrl(), "content_sha256", t.contentSha256(), "content_html", t.contentHtml()),
                new DatabaseSession.Conflict(DatabaseSession.Mode.UPDATE, List.of("announcement_id", "locale"), changes)));
    }
    public int deleteStaleRemoteAnnouncementTranslations(String id, List<String> locales) {
        Expression where = eq(T, "announcement_id", id);
        if (locales != null && !locales.isEmpty()) where = call(AND, where, call(NOT, member(column(T, "locale"), locales)));
        var condition = where; return database.transaction(s -> s.delete(T, condition));
    }
    public int deleteRemoteAnnouncementTranslations(String id) { return database.transaction(s -> s.delete(T, eq(T, "announcement_id", id))); }
    public int acceptRemoteAnnouncementIndex(long sequence, String digest, long generated, long expires) {
        var sameDigest = eq(S, "manifest_sha256", digest);
        var accepted = call(AND, eq(S, "id", 1), call(OR, call(LESS, column(S, "sequence"), value(sequence)),
                call(AND, eq(S, "sequence", sequence), sameDigest)));
        var changes = Map.<String, Expression>of("sequence", value(sequence), "manifest_sha256", value(digest),
                "generated_time", value(generated), "expires_time", value(expires),
                "etag", new Conditional(sameDigest, column(S, "etag"), value(null)),
                "last_modified", new Conditional(sameDigest, column(S, "last_modified"), value(null)));
        return database.transaction(s -> {
            int updated = s.update(S, changes, accepted);
            if (updated != 0) return updated;
            int inserted = s.insert(S, row("id", 1L, "sequence", sequence, "manifest_sha256", digest,
                    "generated_time", generated, "expires_time", expires), DatabaseSession.Conflict.ignore("id"));
            // 另一事务可能刚完成首次插入；再次按同一序列条件更新，绝不覆盖较新索引。
            return inserted != 0 ? inserted : s.update(S, changes, accepted);
        });
    }
    public RemoteAnnouncementValidators findRemoteAnnouncementValidators() {
        var r = first(database.read(s -> s.query(query(S, fields(S, "manifest_sha256", "expires_time", "etag", "last_modified"), eq(S, "id", 1)))));
        return r == null ? null : new RemoteAnnouncementValidators((String) r.get("manifest_sha256"),
                (Long) r.get("expires_time"), (String) r.get("etag"), (String) r.get("last_modified"));
    }
    public int saveRemoteAnnouncementValidators(String digest, String etag, String lastModified) {
        return database.transaction(s -> s.update(S, Map.of("etag", value(etag), "last_modified", value(lastModified)),
                call(AND, eq(S, "id", 1), eq(S, "manifest_sha256", digest))));
    }
    public long countUnread(String category) { return (Long) database.read(s -> s.query(Query.count(M, unread(category)))).get(0).get("count"); }
    private static Expression timestamp(long time) {
        return new Conditional(call(GREATER, column(M, "created_time"), value(time)), column(M, "created_time"), value(time));
    }
    public int markRead(String id, long time) {
        return database.transaction(s -> s.update(M, Map.of("read_time", timestamp(time)), call(AND, unread(null), eq(M, "id", id))));
    }
    public int markAllRead(String category, long time) {
        return database.transaction(s -> s.update(M, Map.of("read_time", timestamp(time)), unread(category)));
    }
    private int dismiss(String id, long time, Expression where) {
        return database.transaction(s -> s.update(M, Map.of("deleted_time", timestamp(time), "content_url", value(null),
                "content_html", value(null), "action_url", value(null)), call(AND, visible(), eq(M, "id", id), where)));
    }
    public int dismissAnnouncement(String id, long time) { return dismiss(id, time, eq(M, "category", "announcement")); }
    private static Expression persistentSurvey() { return call(LIKE, column(M, "id"), value("persistent-survey:%")); }
    public int dismissPersistentSurvey(String id, long time) {
        return dismiss(id, time, call(AND, eq(M, "category", "survey"), persistentSurvey()));
    }
    public int setActivePersistentSurveys(List<String> ids) {
        return database.transaction(s -> s.update(M,
                Map.of("active", new Conditional(member(column(M, "id"), ids), value(1), value(0))), persistentSurvey()));
    }
    public int deleteNonAnnouncement(String id) {
        return database.transaction(s -> s.delete(M, call(AND, visible(), eq(M, "id", id),
                call(NOT_EQUAL, column(M, "category"), value("announcement")))));
    }
    public int pruneRetentionPool(long cutoff, int maxMessages) {
        var pool = call(AND, call(IS_NULL, column(M, "deleted_time")), member(column(M, "category"), List.of("download", "system")));
        var kept = new Query(M, fields(M, "id"), call(AND, pool, call(GREATER_EQUAL, column(M, "created_time"), value(cutoff))),
                List.of(), List.of(new Query.Order(column(M, "created_time"), true, false),
                        new Query.Order(column(M, "id"), true, false)), maxMessages, 0);
        var exists = new Exists(new Query(new Query.Derived(kept, "kept"), List.of(), Map.of("id", column("kept", "id")),
                call(EQUAL, column("kept", "id"), column(M, "id")), List.of(), null, List.of(), false, 1, 0));
        return database.transaction(s -> s.delete(M, call(AND, pool,
                call(OR, call(LESS, column(M, "created_time"), value(cutoff)), call(NOT, exists)))));
    }
}
