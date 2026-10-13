package top.sywyar.pixivdownload.setup.guest.persistence;

import org.springframework.stereotype.Repository;
import top.sywyar.pixivdownload.core.db.MainDatabase;
import top.sywyar.pixivdownload.plugin.api.storage.relational.*;
import java.util.*;
import static top.sywyar.pixivdownload.plugin.api.storage.relational.Expression.*;
import static top.sywyar.pixivdownload.plugin.api.storage.relational.Expression.Operator.*;

@Repository
public class GuestInviteMapper {
    private static final String TABLE = "guest_invites";
    private static final String ACCESS = "guest_invite_access_stats";
    private final MainDatabase database;
    public GuestInviteMapper(MainDatabase database) { this.database = database; }
    private static Expression equal(String table, String name, Object value) { return call(EQUAL, column(table, name), value(value)); }
    private static Expression identity(long id) { return equal(TABLE, "id", id); }
    private static Map<String, Expression> fields(String table, String... names) {
        var fields = new LinkedHashMap<String, Expression>();
        for (var name : names) fields.put(name, column(table, name));
        return fields;
    }
    private int copyLegacy(String table, String target, String field, String flag) {
        var query = new Query(new Query.Table(table), List.of(new Query.Join(Query.JoinKind.INNER, new Query.Table(TABLE),
                call(EQUAL, column(TABLE, "id"), column(table, "invite_id")))),
                fields(table, "invite_id", field), call(IS_NULL, column(TABLE, flag)),
                List.of(), null, List.of(), false, Integer.MAX_VALUE, 0);
        return database.transaction(s -> s.insertFrom(target, query, DatabaseSession.Conflict.ignore()));
    }
    public int copyTagsToNovelSide() {
        return copyLegacy("guest_invite_tags", "guest_invite_novel_tags", "tag_id", "novel_tag_unrestricted");
    }
    public int copyAuthorsToNovelSide() {
        return copyLegacy("guest_invite_authors", "guest_invite_novel_authors", "author_id", "novel_author_unrestricted");
    }
    private int migrateFlag(String destination, String source) {
        return database.transaction(s -> s.update(TABLE, Map.of(destination, column(TABLE, source)), call(IS_NULL, column(TABLE, destination))));
    }
    public int setNovelTagUnrestrictedFromLegacy() { return migrateFlag("novel_tag_unrestricted", "tag_unrestricted"); }
    public int setNovelAuthorUnrestrictedFromLegacy() { return migrateFlag("novel_author_unrestricted", "author_unrestricted"); }
    private static Long flag(Boolean value) { return value == null ? null : value ? 1L : 0L; }
    private static Map<String, Object> permissions(GuestInviteRow row) {
        var values = new LinkedHashMap<String, Object>();
        values.put("name", row.getName()); values.put("expire_time", row.getExpireTime());
        values.put("allow_sfw", flag(row.isAllowSfw())); values.put("allow_r18", flag(row.isAllowR18())); values.put("allow_r18g", flag(row.isAllowR18g()));
        values.put("tag_unrestricted", flag(row.isTagUnrestricted())); values.put("author_unrestricted", flag(row.isAuthorUnrestricted()));
        values.put("novel_tag_unrestricted", flag(row.getNovelTagUnrestricted())); values.put("novel_author_unrestricted", flag(row.getNovelAuthorUnrestricted()));
        values.put("collection_unrestricted", flag(row.isCollectionUnrestricted())); values.put("collection_restricts_works", flag(row.isCollectionRestrictsWorks()));
        return values;
    }
    public void insertInvite(GuestInviteRow row) {
        var values = permissions(row);
        values.put("code", row.getCode()); values.put("created_time", row.getCreatedTime());
        values.put("paused", 0L); values.put("revoked", 0L); values.put("total_request_count", 0L);
        row.setId(database.transaction(s -> s.insertGenerated(TABLE, values, "id")));
    }
    private List<GuestInviteRow> find(Expression where, int limit) {
        var fields = fields(TABLE, "id", "code", "name", "expire_time", "allow_sfw", "allow_r18", "allow_r18g",
                "tag_unrestricted", "author_unrestricted", "novel_tag_unrestricted", "novel_author_unrestricted",
                "created_time", "paused", "revoked", "first_used_time", "last_used_time", "total_request_count",
                "collection_unrestricted", "collection_restricts_works");
        return database.read(s -> s.query(new Query(TABLE, fields, where, List.of(),
                List.of(new Query.Order(column(TABLE, "created_time"), true, false)), limit, 0))).stream().map(row ->
                new GuestInviteRow((Long) row.get("id"), (String) row.get("code"), (String) row.get("name"), (Long) row.get("expire_time"),
                        bool(row, "allow_sfw"), bool(row, "allow_r18"), bool(row, "allow_r18g"), bool(row, "tag_unrestricted"),
                        bool(row, "author_unrestricted"), nullableBool(row, "novel_tag_unrestricted"), nullableBool(row, "novel_author_unrestricted"),
                        number(row, "created_time"), bool(row, "paused"), bool(row, "revoked"), (Long) row.get("first_used_time"),
                        (Long) row.get("last_used_time"), number(row, "total_request_count"),
                        bool(row, "collection_unrestricted"), bool(row, "collection_restricts_works"))).toList();
    }
    private static long number(Map<String, Object> row, String key) { return row.get(key) == null ? 0L : (Long) row.get(key); }
    private static Boolean nullableBool(Map<String, Object> row, String key) { return row.get(key) == null ? null : (Long) row.get(key) != 0; }
    private static boolean bool(Map<String, Object> row, String key) { return Boolean.TRUE.equals(nullableBool(row, key)); }
    public GuestInviteRow findByCode(String code) { return find(equal(TABLE, "code", code), 1).stream().findFirst().orElse(null); }
    public GuestInviteRow findById(long id) { return find(identity(id), 1).stream().findFirst().orElse(null); }
    public List<GuestInviteRow> findAll() { return find(null, Integer.MAX_VALUE); }
    public void updateInviteCore(GuestInviteRow row) {
        var changes = new LinkedHashMap<String, Expression>();
        permissions(row).forEach((name, value) -> changes.put(name, value(value)));
        database.transaction(s -> s.update(TABLE, changes, identity(row.getId())));
    }
    public void updatePaused(long id, boolean paused) { database.transaction(s -> s.update(TABLE, Map.of("paused", value(flag(paused))), identity(id))); }
    public void markRevoked(long id) { database.transaction(s -> s.update(TABLE, Map.of("revoked", value(1)), identity(id))); }
    public void deleteInvite(long id) { database.transaction(s -> s.delete(TABLE, identity(id))); }
    private static Expression expired(long now) { return call(LESS, column(TABLE, "expire_time"), value(now)); }
    private static Expression expiredOrRevoked(long now) { return call(OR, equal(TABLE, "revoked", 1), expired(now)); }
    public int deleteExpiredOrRevoked(long now) { return database.transaction(s -> s.delete(TABLE, expiredOrRevoked(now))); }
    private List<Long> ids(Expression where) {
        return database.read(s -> s.query(new Query(TABLE, fields(TABLE, "id"), where, List.of(), List.of(), Integer.MAX_VALUE, 0)))
                .stream().map(row -> (Long) row.get("id")).toList();
    }
    public List<Long> findExpiredOrRevokedIds(long now) { return ids(expiredOrRevoked(now)); }
    public List<Long> findExpiredIds(long now) { return ids(expired(now)); }
    public void incrementUsage(long id, long now) {
        database.transaction(s -> s.update(TABLE, Map.of("total_request_count", call(ADD, column(TABLE, "total_request_count"), value(1)),
                "last_used_time", value(now), "first_used_time", call(COALESCE, column(TABLE, "first_used_time"), value(now))), identity(id)));
    }
    private void insertLink(String table, String field, long inviteId, long targetId, boolean ignore) {
        database.transaction(s -> s.insert(table, Map.of("invite_id", inviteId, field, targetId),
                ignore ? DatabaseSession.Conflict.ignore() : DatabaseSession.Conflict.error()));
    }
    private void deleteLinks(String table, long id) { database.transaction(s -> s.delete(table, equal(table, "invite_id", id))); }
    private List<Long> linkIds(String table, String field, long id) {
        return database.read(s -> s.query(new Query(table, fields(table, field), equal(table, "invite_id", id), List.of(),
                List.of(new Query.Order(column(table, field), false, true)), Integer.MAX_VALUE, 0)))
                .stream().map(row -> (Long) row.get(field)).toList();
    }
    private List<TagRow> tags(String table, long inviteId) {
        var query = new Query(new Query.Table(table), List.of(new Query.Join(Query.JoinKind.INNER, new Query.Table("tags"),
                call(EQUAL, column(table, "tag_id"), column("tags", "tag_id")))), fields("tags", "tag_id", "name", "translated_name"),
                equal(table, "invite_id", inviteId), List.of(), null,
                List.of(new Query.Order(column("tags", "tag_id"), false, true)), false, Integer.MAX_VALUE, 0);
        return database.read(s -> s.query(query)).stream().map(row ->
                new TagRow((Long) row.get("tag_id"), (String) row.get("name"), (String) row.get("translated_name"))).toList();
    }
    private List<AuthorRow> authors(String table, long inviteId) {
        var query = new Query(new Query.Table(table), List.of(new Query.Join(Query.JoinKind.INNER, new Query.Table("authors"),
                call(EQUAL, column(table, "author_id"), column("authors", "author_id")))), fields("authors", "author_id", "name"),
                equal(table, "invite_id", inviteId), List.of(), null,
                List.of(new Query.Order(column("authors", "name"), false, true)), false, Integer.MAX_VALUE, 0);
        return database.read(s -> s.query(query)).stream().map(row -> new AuthorRow((Long) row.get("author_id"), (String) row.get("name"))).toList();
    }
    public void insertInviteTag(long inviteId, long tagId) { insertLink("guest_invite_tags", "tag_id", inviteId, tagId, true); }
    public void deleteInviteTags(long inviteId) { deleteLinks("guest_invite_tags", inviteId); }
    public List<Long> findInviteTagIds(long inviteId) { return linkIds("guest_invite_tags", "tag_id", inviteId); }
    public List<TagRow> findInviteTags(long inviteId) { return tags("guest_invite_tags", inviteId); }
    public void insertInviteAuthor(long inviteId, long authorId) { insertLink("guest_invite_authors", "author_id", inviteId, authorId, true); }
    public void deleteInviteAuthors(long inviteId) { deleteLinks("guest_invite_authors", inviteId); }
    public List<Long> findInviteAuthorIds(long inviteId) { return linkIds("guest_invite_authors", "author_id", inviteId); }
    public List<AuthorRow> findInviteAuthors(long inviteId) { return authors("guest_invite_authors", inviteId); }
    public void insertInviteNovelTag(long inviteId, long tagId) { insertLink("guest_invite_novel_tags", "tag_id", inviteId, tagId, true); }
    public void deleteInviteNovelTags(long inviteId) { deleteLinks("guest_invite_novel_tags", inviteId); }
    public List<Long> findInviteNovelTagIds(long inviteId) { return linkIds("guest_invite_novel_tags", "tag_id", inviteId); }
    public List<TagRow> findInviteNovelTags(long inviteId) { return tags("guest_invite_novel_tags", inviteId); }
    public void insertInviteNovelAuthor(long inviteId, long authorId) { insertLink("guest_invite_novel_authors", "author_id", inviteId, authorId, true); }
    public void deleteInviteNovelAuthors(long inviteId) { deleteLinks("guest_invite_novel_authors", inviteId); }
    public List<Long> findInviteNovelAuthorIds(long inviteId) { return linkIds("guest_invite_novel_authors", "author_id", inviteId); }
    public List<AuthorRow> findInviteNovelAuthors(long inviteId) { return authors("guest_invite_novel_authors", inviteId); }
    public void purgeInviteTags(long inviteId) { deleteInviteTags(inviteId); }
    public void purgeInviteAuthors(long inviteId) { deleteInviteAuthors(inviteId); }
    public void purgeInviteNovelTags(long inviteId) { deleteInviteNovelTags(inviteId); }
    public void purgeInviteNovelAuthors(long inviteId) { deleteInviteNovelAuthors(inviteId); }
    public void purgeInviteAccessStats(long inviteId) { deleteLinks(ACCESS, inviteId); }

    public void upsertAccessStat(long inviteId, long bucketHour) {
        database.transaction(s -> s.insert(ACCESS, Map.of("invite_id", inviteId, "bucket_hour", bucketHour, "request_count", 1L),
                new DatabaseSession.Conflict(DatabaseSession.Mode.UPDATE, List.of("invite_id", "bucket_hour"),
                        Map.of("request_count", call(ADD, column(ACCESS, "request_count"), value(1))))));
    }
    public List<AccessStatRow> findAccessStats(long inviteId, long fromBucket) {
        return database.read(s -> s.query(new Query(ACCESS, fields(ACCESS, "bucket_hour", "request_count"),
                call(AND, equal(ACCESS, "invite_id", inviteId), call(GREATER_EQUAL, column(ACCESS, "bucket_hour"), value(fromBucket))),
                List.of(), List.of(new Query.Order(column(ACCESS, "bucket_hour"), false, true)), Integer.MAX_VALUE, 0))).stream()
                .map(row -> new AccessStatRow((Long) row.get("bucket_hour"), (Long) row.get("request_count"))).toList();
    }
    public int deleteAccessStatsOlderThan(long minBucket) { return database.transaction(s -> s.delete(ACCESS, call(LESS, column(ACCESS, "bucket_hour"), value(minBucket)))); }
    public void insertInviteCollection(long inviteId, long collectionId) { insertLink("guest_invite_collections", "collection_id", inviteId, collectionId, false); }
    public void deleteInviteCollections(long inviteId) { deleteLinks("guest_invite_collections", inviteId); }
    public List<Long> findInviteCollectionIds(long inviteId) { return linkIds("guest_invite_collections", "collection_id", inviteId); }
    public record TagRow(long tagId, String name, String translatedName) {}
    public record AuthorRow(long authorId, String name) {}
    public record AccessStatRow(long bucketHour, long requestCount) {}
}
