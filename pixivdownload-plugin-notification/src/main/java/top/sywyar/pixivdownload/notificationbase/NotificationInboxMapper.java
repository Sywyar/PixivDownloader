package top.sywyar.pixivdownload.notificationbase;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface NotificationInboxMapper {

    String SELECT_MESSAGE = "SELECT id, category, severity, scenario_id AS scenarioId, title, body,"
            + " content_url AS contentUrl, action_url AS actionUrl,"
            + " created_time AS createdTime, read_time AS readTime"
            + " FROM notification_messages";

    @Insert("INSERT INTO notification_messages"
            + " (id, category, severity, scenario_id, title, body, content_url, action_url,"
            + " created_time, read_time)"
            + " VALUES (#{id}, #{category}, #{severity}, #{scenarioId}, #{title}, #{body},"
            + " #{contentUrl}, #{actionUrl}, #{createdTime}, #{readTime})")
    int insert(NotificationMessage message);

    @Select({
            "<script>",
            SELECT_MESSAGE,
            "<where>",
            "<if test='category != null'>category = #{category}</if>",
            "<if test='unreadOnly'>AND read_time IS NULL</if>",
            "</where>",
            "ORDER BY created_time DESC, id DESC LIMIT #{limit}",
            "</script>"
    })
    List<NotificationMessage> findLatest(@Param("category") String category,
                                         @Param("unreadOnly") boolean unreadOnly,
                                         @Param("limit") int limit);

    @Select(SELECT_MESSAGE + " WHERE id = #{id}")
    NotificationMessage findById(@Param("id") String id);

    @Select({
            "<script>",
            "SELECT COUNT(*) FROM notification_messages WHERE read_time IS NULL",
            "<if test='category != null'>AND category = #{category}</if>",
            "</script>"
    })
    long countUnread(@Param("category") String category);

    @Update("UPDATE notification_messages SET read_time = MAX(created_time, #{readTime})"
            + " WHERE id = #{id} AND read_time IS NULL")
    int markRead(@Param("id") String id, @Param("readTime") long readTime);

    @Update({
            "<script>",
            "UPDATE notification_messages SET read_time = MAX(created_time, #{readTime})",
            "WHERE read_time IS NULL",
            "<if test='category != null'>AND category = #{category}</if>",
            "</script>"
    })
    int markAllRead(@Param("category") String category, @Param("readTime") long readTime);
}
