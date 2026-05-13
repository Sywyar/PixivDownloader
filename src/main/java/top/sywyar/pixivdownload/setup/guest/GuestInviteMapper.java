package top.sywyar.pixivdownload.setup.guest;

import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface GuestInviteMapper {

    String SELECT_INVITE = "SELECT id, code, name, expire_time AS expireTime,"
            + " allow_sfw AS allowSfw, allow_r18 AS allowR18, allow_r18g AS allowR18g,"
            + " tag_unrestricted AS tagUnrestricted, author_unrestricted AS authorUnrestricted,"
            + " novel_tag_unrestricted AS novelTagUnrestricted,"
            + " novel_author_unrestricted AS novelAuthorUnrestricted,"
            + " created_time AS createdTime, paused, revoked,"
            + " first_used_time AS firstUsedTime, last_used_time AS lastUsedTime,"
            + " total_request_count AS totalRequestCount"
            + " FROM guest_invites";

    // ── DDL ────────────────────────────────────────────────────────────────────

    @Update("CREATE TABLE IF NOT EXISTS guest_invites ("
            + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + "code TEXT NOT NULL UNIQUE,"
            + "name TEXT NOT NULL,"
            + "expire_time INTEGER,"
            + "allow_sfw INTEGER NOT NULL DEFAULT 1,"
            + "allow_r18 INTEGER NOT NULL DEFAULT 0,"
            + "allow_r18g INTEGER NOT NULL DEFAULT 0,"
            + "tag_unrestricted INTEGER NOT NULL DEFAULT 1,"
            + "author_unrestricted INTEGER NOT NULL DEFAULT 1,"
            + "novel_tag_unrestricted INTEGER,"
            + "novel_author_unrestricted INTEGER,"
            + "created_time INTEGER NOT NULL,"
            + "paused INTEGER NOT NULL DEFAULT 0,"
            + "revoked INTEGER NOT NULL DEFAULT 0,"
            + "first_used_time INTEGER,"
            + "last_used_time INTEGER,"
            + "total_request_count INTEGER NOT NULL DEFAULT 0)")
    void createInvitesTable();

    @Update("CREATE INDEX IF NOT EXISTS idx_guest_invites_code ON guest_invites(code)")
    void createInvitesCodeIndex();

    /**
     * 旧库迁移：为没有 novel_tag_unrestricted / novel_author_unrestricted 列的库追加列。
     * 列保持可空，没有默认值；首次为 NULL 的行会在 {@code GuestInviteService.init} 中按
     * 漫画侧配置完成复制。SQLite 不支持 IF NOT EXISTS 的 ADD COLUMN，由 Service 层 try/catch 吃掉。
     */
    @Update("ALTER TABLE guest_invites ADD COLUMN novel_tag_unrestricted INTEGER")
    void addNovelTagUnrestrictedColumn();

    @Update("ALTER TABLE guest_invites ADD COLUMN novel_author_unrestricted INTEGER")
    void addNovelAuthorUnrestrictedColumn();

    @Update("CREATE TABLE IF NOT EXISTS guest_invite_tags ("
            + "invite_id INTEGER NOT NULL,"
            + "tag_id INTEGER NOT NULL,"
            + "PRIMARY KEY (invite_id, tag_id))")
    void createInviteTagsTable();

    @Update("CREATE TABLE IF NOT EXISTS guest_invite_authors ("
            + "invite_id INTEGER NOT NULL,"
            + "author_id INTEGER NOT NULL,"
            + "PRIMARY KEY (invite_id, author_id))")
    void createInviteAuthorsTable();

    @Update("CREATE TABLE IF NOT EXISTS guest_invite_novel_tags ("
            + "invite_id INTEGER NOT NULL,"
            + "tag_id INTEGER NOT NULL,"
            + "PRIMARY KEY (invite_id, tag_id))")
    void createInviteNovelTagsTable();

    @Update("CREATE TABLE IF NOT EXISTS guest_invite_novel_authors ("
            + "invite_id INTEGER NOT NULL,"
            + "author_id INTEGER NOT NULL,"
            + "PRIMARY KEY (invite_id, author_id))")
    void createInviteNovelAuthorsTable();

    @Update("CREATE TABLE IF NOT EXISTS guest_invite_access_stats ("
            + "invite_id INTEGER NOT NULL,"
            + "bucket_hour INTEGER NOT NULL,"
            + "request_count INTEGER NOT NULL DEFAULT 0,"
            + "PRIMARY KEY (invite_id, bucket_hour))")
    void createAccessStatsTable();

    @Update("CREATE INDEX IF NOT EXISTS idx_guest_invite_access_stats_bucket"
            + " ON guest_invite_access_stats(bucket_hour)")
    void createAccessStatsBucketIndex();

    // ── 一次性迁移：把旧"统一白名单"的内容复制到小说侧 ─────────────────────

    /**
     * 把 guest_invite_tags 中"该邀请 novel_tag_unrestricted IS NULL"的行复制到 guest_invite_novel_tags。
     * 使用 INSERT OR IGNORE 防止重复，仅复制未迁移过的邀请。
     */
    @Insert("INSERT OR IGNORE INTO guest_invite_novel_tags(invite_id, tag_id)"
            + " SELECT it.invite_id, it.tag_id FROM guest_invite_tags it"
            + " JOIN guest_invites gi ON gi.id = it.invite_id"
            + " WHERE gi.novel_tag_unrestricted IS NULL")
    int copyTagsToNovelSide();

    @Insert("INSERT OR IGNORE INTO guest_invite_novel_authors(invite_id, author_id)"
            + " SELECT ia.invite_id, ia.author_id FROM guest_invite_authors ia"
            + " JOIN guest_invites gi ON gi.id = ia.invite_id"
            + " WHERE gi.novel_author_unrestricted IS NULL")
    int copyAuthorsToNovelSide();

    @Update("UPDATE guest_invites SET novel_tag_unrestricted = tag_unrestricted"
            + " WHERE novel_tag_unrestricted IS NULL")
    int setNovelTagUnrestrictedFromLegacy();

    @Update("UPDATE guest_invites SET novel_author_unrestricted = author_unrestricted"
            + " WHERE novel_author_unrestricted IS NULL")
    int setNovelAuthorUnrestrictedFromLegacy();

    // ── invites ────────────────────────────────────────────────────────────────

    @Insert("INSERT INTO guest_invites"
            + " (code, name, expire_time, allow_sfw, allow_r18, allow_r18g,"
            + "  tag_unrestricted, author_unrestricted,"
            + "  novel_tag_unrestricted, novel_author_unrestricted,"
            + "  created_time, paused, revoked, total_request_count)"
            + " VALUES (#{code}, #{name}, #{expireTime}, #{allowSfw}, #{allowR18}, #{allowR18g},"
            + "  #{tagUnrestricted}, #{authorUnrestricted},"
            + "  #{novelTagUnrestricted}, #{novelAuthorUnrestricted},"
            + "  #{createdTime}, 0, 0, 0)")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    void insertInvite(GuestInviteRow row);

    @Select(SELECT_INVITE + " WHERE code = #{code}")
    GuestInviteRow findByCode(@Param("code") String code);

    @Select(SELECT_INVITE + " WHERE id = #{id}")
    GuestInviteRow findById(@Param("id") long id);

    @Select(SELECT_INVITE + " ORDER BY created_time DESC")
    List<GuestInviteRow> findAll();

    @Update("UPDATE guest_invites SET name = #{name}, expire_time = #{expireTime},"
            + " allow_sfw = #{allowSfw}, allow_r18 = #{allowR18}, allow_r18g = #{allowR18g},"
            + " tag_unrestricted = #{tagUnrestricted}, author_unrestricted = #{authorUnrestricted},"
            + " novel_tag_unrestricted = #{novelTagUnrestricted},"
            + " novel_author_unrestricted = #{novelAuthorUnrestricted}"
            + " WHERE id = #{id}")
    void updateInviteCore(GuestInviteRow row);

    @Update("UPDATE guest_invites SET paused = #{paused} WHERE id = #{id}")
    void updatePaused(@Param("id") long id, @Param("paused") boolean paused);

    @Update("UPDATE guest_invites SET revoked = 1 WHERE id = #{id}")
    void markRevoked(@Param("id") long id);

    @Delete("DELETE FROM guest_invites WHERE id = #{id}")
    void deleteInvite(@Param("id") long id);

    @Delete("DELETE FROM guest_invites WHERE revoked = 1"
            + " OR (expire_time IS NOT NULL AND expire_time < #{now})")
    int deleteExpiredOrRevoked(@Param("now") long now);

    @Select("SELECT id FROM guest_invites WHERE revoked = 1"
            + " OR (expire_time IS NOT NULL AND expire_time < #{now})")
    List<Long> findExpiredOrRevokedIds(@Param("now") long now);

    @Select("SELECT id FROM guest_invites WHERE expire_time IS NOT NULL AND expire_time < #{now}")
    List<Long> findExpiredIds(@Param("now") long now);

    @Update("UPDATE guest_invites SET total_request_count = total_request_count + 1,"
            + " last_used_time = #{now},"
            + " first_used_time = COALESCE(first_used_time, #{now})"
            + " WHERE id = #{id}")
    void incrementUsage(@Param("id") long id, @Param("now") long now);

    // ── invite_tags / invite_authors (插画 / 漫画侧) ─────────────────────────

    @Insert("INSERT OR IGNORE INTO guest_invite_tags(invite_id, tag_id) VALUES(#{inviteId}, #{tagId})")
    void insertInviteTag(@Param("inviteId") long inviteId, @Param("tagId") long tagId);

    @Delete("DELETE FROM guest_invite_tags WHERE invite_id = #{inviteId}")
    void deleteInviteTags(@Param("inviteId") long inviteId);

    @Select("SELECT tag_id FROM guest_invite_tags WHERE invite_id = #{inviteId}")
    List<Long> findInviteTagIds(@Param("inviteId") long inviteId);

    @Select("SELECT t.tag_id AS tagId, t.name AS name, t.translated_name AS translatedName"
            + " FROM guest_invite_tags it JOIN tags t ON t.tag_id = it.tag_id"
            + " WHERE it.invite_id = #{inviteId} ORDER BY t.tag_id")
    List<TagRow> findInviteTags(@Param("inviteId") long inviteId);

    @Insert("INSERT OR IGNORE INTO guest_invite_authors(invite_id, author_id) VALUES(#{inviteId}, #{authorId})")
    void insertInviteAuthor(@Param("inviteId") long inviteId, @Param("authorId") long authorId);

    @Delete("DELETE FROM guest_invite_authors WHERE invite_id = #{inviteId}")
    void deleteInviteAuthors(@Param("inviteId") long inviteId);

    @Select("SELECT author_id FROM guest_invite_authors WHERE invite_id = #{inviteId}")
    List<Long> findInviteAuthorIds(@Param("inviteId") long inviteId);

    @Select("SELECT a.author_id AS authorId, a.name AS name"
            + " FROM guest_invite_authors ia JOIN authors a ON a.author_id = ia.author_id"
            + " WHERE ia.invite_id = #{inviteId} ORDER BY a.name")
    List<AuthorRow> findInviteAuthors(@Param("inviteId") long inviteId);

    // ── invite_novel_tags / invite_novel_authors (小说侧) ────────────────────

    @Insert("INSERT OR IGNORE INTO guest_invite_novel_tags(invite_id, tag_id) VALUES(#{inviteId}, #{tagId})")
    void insertInviteNovelTag(@Param("inviteId") long inviteId, @Param("tagId") long tagId);

    @Delete("DELETE FROM guest_invite_novel_tags WHERE invite_id = #{inviteId}")
    void deleteInviteNovelTags(@Param("inviteId") long inviteId);

    @Select("SELECT tag_id FROM guest_invite_novel_tags WHERE invite_id = #{inviteId}")
    List<Long> findInviteNovelTagIds(@Param("inviteId") long inviteId);

    @Select("SELECT t.tag_id AS tagId, t.name AS name, t.translated_name AS translatedName"
            + " FROM guest_invite_novel_tags it JOIN tags t ON t.tag_id = it.tag_id"
            + " WHERE it.invite_id = #{inviteId} ORDER BY t.tag_id")
    List<TagRow> findInviteNovelTags(@Param("inviteId") long inviteId);

    @Insert("INSERT OR IGNORE INTO guest_invite_novel_authors(invite_id, author_id) VALUES(#{inviteId}, #{authorId})")
    void insertInviteNovelAuthor(@Param("inviteId") long inviteId, @Param("authorId") long authorId);

    @Delete("DELETE FROM guest_invite_novel_authors WHERE invite_id = #{inviteId}")
    void deleteInviteNovelAuthors(@Param("inviteId") long inviteId);

    @Select("SELECT author_id FROM guest_invite_novel_authors WHERE invite_id = #{inviteId}")
    List<Long> findInviteNovelAuthorIds(@Param("inviteId") long inviteId);

    @Select("SELECT a.author_id AS authorId, a.name AS name"
            + " FROM guest_invite_novel_authors ia JOIN authors a ON a.author_id = ia.author_id"
            + " WHERE ia.invite_id = #{inviteId} ORDER BY a.name")
    List<AuthorRow> findInviteNovelAuthors(@Param("inviteId") long inviteId);

    // ── 关联表批量清理（用于按 id 集合或维护任务删除） ───────────────────────

    @Delete("DELETE FROM guest_invite_tags WHERE invite_id = #{inviteId}")
    void purgeInviteTags(@Param("inviteId") long inviteId);

    @Delete("DELETE FROM guest_invite_authors WHERE invite_id = #{inviteId}")
    void purgeInviteAuthors(@Param("inviteId") long inviteId);

    @Delete("DELETE FROM guest_invite_novel_tags WHERE invite_id = #{inviteId}")
    void purgeInviteNovelTags(@Param("inviteId") long inviteId);

    @Delete("DELETE FROM guest_invite_novel_authors WHERE invite_id = #{inviteId}")
    void purgeInviteNovelAuthors(@Param("inviteId") long inviteId);

    @Delete("DELETE FROM guest_invite_access_stats WHERE invite_id = #{inviteId}")
    void purgeInviteAccessStats(@Param("inviteId") long inviteId);

    // ── 访问统计 ────────────────────────────────────────────────────────────

    @Insert("INSERT INTO guest_invite_access_stats (invite_id, bucket_hour, request_count)"
            + " VALUES (#{inviteId}, #{bucketHour}, 1)"
            + " ON CONFLICT(invite_id, bucket_hour)"
            + " DO UPDATE SET request_count = request_count + 1")
    void upsertAccessStat(@Param("inviteId") long inviteId, @Param("bucketHour") long bucketHour);

    @Select("SELECT bucket_hour AS bucketHour, request_count AS requestCount"
            + " FROM guest_invite_access_stats"
            + " WHERE invite_id = #{inviteId} AND bucket_hour >= #{fromBucket}"
            + " ORDER BY bucket_hour")
    List<AccessStatRow> findAccessStats(@Param("inviteId") long inviteId,
                                        @Param("fromBucket") long fromBucket);

    @Delete("DELETE FROM guest_invite_access_stats WHERE bucket_hour < #{minBucket}")
    int deleteAccessStatsOlderThan(@Param("minBucket") long minBucket);

    // ── Row 类型 ────────────────────────────────────────────────────────────

    record TagRow(long tagId, String name, String translatedName) {}

    record AuthorRow(long authorId, String name) {}

    record AccessStatRow(long bucketHour, long requestCount) {}
}
