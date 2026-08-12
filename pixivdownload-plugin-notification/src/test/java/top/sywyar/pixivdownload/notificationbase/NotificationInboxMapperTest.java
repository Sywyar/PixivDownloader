package top.sywyar.pixivdownload.notificationbase;

import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("站内信 SQLite Mapper")
class NotificationInboxMapperTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("按分类和未读状态查询、计数及批量已读真实可执行")
    void persistsQueriesAndMarksRead() throws Exception {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("notifications.db"));
        createSchema(dataSource);

        org.apache.ibatis.session.Configuration configuration = new org.apache.ibatis.session.Configuration(
                new Environment("test", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(NotificationInboxMapper.class);
        SqlSessionFactory sessions = new SqlSessionFactoryBuilder().build(configuration);

        try (SqlSession session = sessions.openSession(true)) {
            NotificationInboxMapper mapper = session.getMapper(NotificationInboxMapper.class);
            mapper.insert(message("older", "download", 10));
            mapper.insert(message("newer", "announcement", 20));

            assertThat(mapper.findLatest(null, false, 10)).extracting(NotificationMessage::id)
                    .containsExactly("newer", "older");
            assertThat(mapper.findLatest("download", false, 10)).extracting(NotificationMessage::id)
                    .containsExactly("older");
            assertThat(mapper.countUnread(null)).isEqualTo(2);
            assertThat(mapper.countUnread("download")).isEqualTo(1);
            assertThat(mapper.markRead("older", 30)).isEqualTo(1);
            assertThat(mapper.markRead("older", 40)).isZero();
            assertThat(mapper.findById("older").readTime()).isEqualTo(30);
            assertThat(mapper.insert(new NotificationMessage(
                    "older", "announcement", "ERROR", null,
                    "Rewritten", "Rewritten", null, null, 100, null))).isZero();
            assertThat(mapper.findById("older")).satisfies(message -> {
                assertThat(message.title()).isEqualTo("Title older");
                assertThat(message.category()).isEqualTo("download");
                assertThat(message.readTime()).isEqualTo(30);
            });
            assertThat(mapper.findById("older").contentUrl()).isEqualTo(
                    "https://sywyar.github.io/PixivDownloader-Remote-Content/older.html");
            assertThat(mapper.findLatest(null, true, 10)).extracting(NotificationMessage::id)
                    .containsExactly("newer");
            assertThat(mapper.markAllRead("announcement", 40)).isEqualTo(1);
            assertThat(mapper.markAllRead("announcement", 50)).isZero();
            assertThat(mapper.countUnread(null)).isZero();
        }
    }

    private static NotificationMessage message(String id, String category, long createdTime) {
        return new NotificationMessage(id, category, "INFO", null,
                "Title " + id, "Body " + id,
                "https://sywyar.github.io/PixivDownloader-Remote-Content/" + id + ".html",
                null, createdTime, null);
    }

    private static void createSchema(SQLiteDataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE notification_messages (
                        id TEXT NOT NULL PRIMARY KEY,
                        category TEXT NOT NULL,
                        severity TEXT NOT NULL,
                        scenario_id TEXT,
                        title TEXT NOT NULL,
                        body TEXT NOT NULL,
                        content_url TEXT,
                        action_url TEXT,
                        created_time INTEGER NOT NULL,
                        read_time INTEGER
                    )
                    """);
        }
    }
}
