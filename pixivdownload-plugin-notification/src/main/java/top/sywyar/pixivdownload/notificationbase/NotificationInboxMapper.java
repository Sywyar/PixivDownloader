package top.sywyar.pixivdownload.notificationbase;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface NotificationInboxMapper {

    String SELECT_MESSAGE = "SELECT id, category, severity, scenario_id AS scenarioId, title, body,"
            + " content_url AS contentUrl,"
            + " CASE WHEN content_html IS NULL THEN NULL ELSE '' END AS contentHtml,"
            + " action_url AS actionUrl,"
            + " created_time AS createdTime, read_time AS readTime"
            + " FROM notification_messages";

    @Insert("INSERT INTO notification_messages"
            + " (id, category, severity, scenario_id, title, body, content_url, content_html,"
            + " action_url, created_time, read_time)"
            + " VALUES (#{id}, #{category}, #{severity}, #{scenarioId}, #{title}, #{body},"
            + " #{contentUrl}, #{contentHtml}, #{actionUrl}, #{createdTime}, #{readTime})"
            + " ON CONFLICT(id) DO NOTHING")
    int insert(NotificationMessage message);

    @Select({
            "<script>",
            SELECT_MESSAGE,
            "<where>",
            "deleted_time IS NULL AND active = 1",
            "<if test='category != null'>AND category = #{category}</if>",
            "<if test='unreadOnly'>AND read_time IS NULL</if>",
            "</where>",
            "ORDER BY created_time DESC, id DESC LIMIT #{limit}",
            "</script>"
    })
    List<NotificationMessage> findLatest(@Param("category") String category,
                                         @Param("unreadOnly") boolean unreadOnly,
                                         @Param("limit") int limit);

    @Select(SELECT_MESSAGE + " WHERE id = #{id} AND deleted_time IS NULL AND active = 1")
    NotificationMessage findById(@Param("id") String id);

    @Select("SELECT content_url AS sourceUrl, content_html AS html"
            + " FROM notification_messages"
            + " WHERE id = #{id} AND deleted_time IS NULL AND active = 1 AND content_html IS NOT NULL")
    NotificationHtmlContent findHtmlContent(@Param("id") String id);

    @Select("SELECT EXISTS(SELECT 1 FROM notification_messages"
            + " WHERE id = #{id} AND (category <> 'announcement'"
            + " OR deleted_time IS NOT NULL OR active <> 1))")
    boolean blocksRemoteAnnouncementImport(@Param("id") String id);

    @Update("UPDATE notification_messages SET content_url = #{contentUrl}, content_html = #{contentHtml}"
            + " WHERE id = #{id} AND category = 'announcement'"
            + " AND deleted_time IS NULL AND content_html IS NULL")
    int restoreRemoteAnnouncementHtml(@Param("id") String id,
                                      @Param("contentUrl") String contentUrl,
                                      @Param("contentHtml") String contentHtml);

    @Select("SELECT t.locale, t.title, t.summary, t.content_url AS contentUrl,"
            + " CASE WHEN t.content_html IS NULL THEN NULL ELSE '' END AS contentHtml"
            + " FROM notification_announcement_translations t"
            + " JOIN notification_messages m ON m.id = t.announcement_id"
            + " WHERE t.announcement_id = #{announcementId} AND m.category = 'announcement'"
            + " AND m.deleted_time IS NULL AND m.active = 1 ORDER BY t.locale")
    List<RemoteAnnouncementTranslation> findRemoteAnnouncementTranslations(
            @Param("announcementId") String announcementId);

    @Select("SELECT t.content_url AS sourceUrl, t.content_html AS html"
            + " FROM notification_announcement_translations t"
            + " JOIN notification_messages m ON m.id = t.announcement_id"
            + " WHERE t.announcement_id = #{announcementId} AND t.locale = #{locale}"
            + " AND m.category = 'announcement' AND m.deleted_time IS NULL AND m.active = 1")
    NotificationHtmlContent findRemoteAnnouncementHtml(@Param("announcementId") String announcementId,
                                                       @Param("locale") String locale);

    @Insert("INSERT INTO notification_announcement_translations"
            + " (announcement_id, locale, title, summary, content_url, content_html)"
            + " VALUES (#{announcementId}, #{translation.locale}, #{translation.title},"
            + " #{translation.summary}, #{translation.contentUrl}, #{translation.contentHtml})"
            + " ON CONFLICT(announcement_id, locale) DO UPDATE SET"
            + " title = excluded.title, summary = excluded.summary,"
            + " content_url = excluded.content_url, content_html = excluded.content_html")
    int upsertRemoteAnnouncementTranslation(
            @Param("announcementId") String announcementId,
            @Param("translation") RemoteAnnouncementTranslation translation);

    @Delete({
            "<script>",
            "DELETE FROM notification_announcement_translations WHERE announcement_id = #{announcementId}",
            "<if test='locales != null and !locales.isEmpty()'>",
            "AND locale NOT IN",
            "<foreach collection='locales' item='locale' open='(' separator=',' close=')'>#{locale}</foreach>",
            "</if>",
            "</script>"
    })
    int deleteStaleRemoteAnnouncementTranslations(@Param("announcementId") String announcementId,
                                                  @Param("locales") List<String> locales);

    @Delete("DELETE FROM notification_announcement_translations WHERE announcement_id = #{announcementId}")
    int deleteRemoteAnnouncementTranslations(@Param("announcementId") String announcementId);

    @Select({
            "<script>",
            "SELECT COUNT(*) FROM notification_messages"
                    + " WHERE deleted_time IS NULL AND active = 1 AND read_time IS NULL",
            "<if test='category != null'>AND category = #{category}</if>",
            "</script>"
    })
    long countUnread(@Param("category") String category);

    @Update("UPDATE notification_messages SET read_time = MAX(created_time, #{readTime})"
            + " WHERE id = #{id} AND deleted_time IS NULL AND active = 1 AND read_time IS NULL")
    int markRead(@Param("id") String id, @Param("readTime") long readTime);

    @Update({
            "<script>",
            "UPDATE notification_messages SET read_time = MAX(created_time, #{readTime})",
            "WHERE deleted_time IS NULL AND active = 1 AND read_time IS NULL",
            "<if test='category != null'>AND category = #{category}</if>",
            "</script>"
    })
    int markAllRead(@Param("category") String category, @Param("readTime") long readTime);

    @Update("UPDATE notification_messages"
            + " SET deleted_time = MAX(created_time, #{deletedTime}),"
            + " content_url = NULL, content_html = NULL, action_url = NULL"
            + " WHERE id = #{id} AND category = 'announcement' AND deleted_time IS NULL AND active = 1")
    int dismissAnnouncement(@Param("id") String id, @Param("deletedTime") long deletedTime);

    @Update("UPDATE notification_messages"
            + " SET deleted_time = MAX(created_time, #{deletedTime}),"
            + " content_url = NULL, content_html = NULL, action_url = NULL"
            + " WHERE id = #{id} AND category = 'survey'"
            + " AND id LIKE 'persistent-survey:%' AND deleted_time IS NULL AND active = 1")
    int dismissPersistentSurvey(@Param("id") String id, @Param("deletedTime") long deletedTime);

    @Update({
            "<script>",
            "UPDATE notification_messages SET active =",
            "<choose>",
            "<when test='activeIds != null and !activeIds.isEmpty()'>",
            "CASE WHEN id IN",
            "<foreach collection='activeIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>",
            "THEN 1 ELSE 0 END",
            "</when>",
            "<otherwise>0</otherwise>",
            "</choose>",
            "WHERE id LIKE 'persistent-survey:%'",
            "</script>"
    })
    int setActivePersistentSurveys(@Param("activeIds") List<String> activeIds);

    @Delete("DELETE FROM notification_messages"
            + " WHERE id = #{id} AND category <> 'announcement' AND deleted_time IS NULL AND active = 1")
    int deleteNonAnnouncement(@Param("id") String id);

    @Delete("DELETE FROM notification_messages"
            + " WHERE deleted_time IS NULL AND category IN ('download','system')"
            + " AND (created_time < #{cutoffTime} OR id NOT IN ("
            + " SELECT id FROM notification_messages"
            + " WHERE deleted_time IS NULL AND category IN ('download','system')"
            + " AND created_time >= #{cutoffTime}"
            + " ORDER BY created_time DESC, id DESC LIMIT #{maxMessages}))")
    int pruneRetentionPool(@Param("cutoffTime") long cutoffTime,
                           @Param("maxMessages") int maxMessages);
}
