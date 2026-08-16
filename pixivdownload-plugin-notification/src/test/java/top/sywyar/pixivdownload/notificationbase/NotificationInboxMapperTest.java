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
import java.util.List;

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
            assertThat(mapper.findById("older").hasHtmlContent()).isTrue();
            assertThat(mapper.findHtmlContent("older")).satisfies(content -> {
                assertThat(content.sourceUrl()).endsWith("/older.html");
                assertThat(content.html()).isEqualTo("<!doctype html><p>older</p>");
            });
            NotificationMessage legacy = new NotificationMessage(
                    "legacy", "announcement", "INFO", null,
                    "Legacy", "Legacy body",
                    "https://sywyar.github.io/PixivDownloader-Remote-Content/legacy.html",
                    null, null, 25, 30L);
            assertThat(mapper.insert(legacy)).isEqualTo(1);
            assertThat(mapper.blocksRemoteAnnouncementImport("legacy")).isFalse();
            NotificationMessage refreshed = new NotificationMessage(
                    "legacy", "announcement", "ERROR", null,
                    "Refreshed", "Refreshed body", legacy.contentUrl(),
                    "<!doctype html><p>legacy</p>", null, 25, null);
            assertThat(mapper.updateRemoteAnnouncement(refreshed)).isEqualTo(1);
            assertThat(mapper.findById("legacy")).satisfies(message -> {
                assertThat(message.severity()).isEqualTo("ERROR");
                assertThat(message.title()).isEqualTo("Refreshed");
                assertThat(message.body()).isEqualTo("Refreshed body");
                assertThat(message.createdTime()).isEqualTo(25);
                assertThat(message.readTime()).isEqualTo(30);
            });
            assertThat(mapper.updateRemoteAnnouncement(new NotificationMessage(
                    "legacy", "announcement", "WARNING", null,
                    "Conflict", "Conflict", legacy.contentUrl(),
                    "<!doctype html><p>conflict</p>", null, 26, null))).isZero();
            RemoteAnnouncementTranslation translation = new RemoteAnnouncementTranslation(
                    "en-US", "English legacy", "English summary", legacy.contentUrl(),
                    "0".repeat(64),
                    "<!doctype html><p>English legacy</p>");
            assertThat(mapper.upsertRemoteAnnouncementTranslation("legacy", translation)).isEqualTo(1);
            assertThat(mapper.findRemoteAnnouncementTranslations("legacy"))
                    .containsExactly(new RemoteAnnouncementTranslation(
                            "en-US", "English legacy", "English summary", legacy.contentUrl(),
                            "0".repeat(64), ""));
            assertThat(mapper.findRemoteAnnouncementHtml("legacy", "en-US"))
                    .isEqualTo(new NotificationHtmlContent(
                            legacy.contentUrl(), "<!doctype html><p>English legacy</p>"));
            assertThat(mapper.findById("legacy").readTime()).isEqualTo(30);
            assertThat(mapper.acceptRemoteAnnouncementIndex(
                    2, "a".repeat(64), 100, 200)).isEqualTo(1);
            assertThat(mapper.saveRemoteAnnouncementValidators(
                    "a".repeat(64), "\"announcement-v1\"",
                    "Wed, 12 Aug 2026 09:22:58 GMT")).isEqualTo(1);
            assertThat(mapper.findRemoteAnnouncementValidators()).isEqualTo(
                    new RemoteAnnouncementValidators(
                            "a".repeat(64), 200, "\"announcement-v1\"",
                            "Wed, 12 Aug 2026 09:22:58 GMT"));
            assertThat(mapper.acceptRemoteAnnouncementIndex(
                    1, "b".repeat(64), 100, 200)).isZero();
            assertThat(mapper.acceptRemoteAnnouncementIndex(
                    2, "b".repeat(64), 100, 200)).isZero();
            assertThat(mapper.acceptRemoteAnnouncementIndex(
                    2, "a".repeat(64), 100, 200)).isEqualTo(1);
            assertThat(mapper.findRemoteAnnouncementValidators().etag())
                    .isEqualTo("\"announcement-v1\"");
            assertThat(mapper.acceptRemoteAnnouncementIndex(
                    3, "c".repeat(64), 200, 300)).isEqualTo(1);
            assertThat(mapper.findRemoteAnnouncementValidators()).isEqualTo(
                    new RemoteAnnouncementValidators("c".repeat(64), 300, null, null));
            assertThat(mapper.findLatest(null, true, 10)).extracting(NotificationMessage::id)
                    .containsExactly("newer");
            assertThat(mapper.markAllRead("announcement", 40)).isEqualTo(1);
            assertThat(mapper.markAllRead("announcement", 50)).isZero();
            assertThat(mapper.countUnread(null)).isZero();
        }
    }

    @Test
    @DisplayName("下载通知与系统共用保留池且公告删除后不会因重复入库复活")
    void prunesSharedRetentionPoolAndKeepsAnnouncementTombstones() throws Exception {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("retention.db"));
        createSchema(dataSource);

        org.apache.ibatis.session.Configuration configuration = new org.apache.ibatis.session.Configuration(
                new Environment("test", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(NotificationInboxMapper.class);

        try (SqlSession session = new SqlSessionFactoryBuilder().build(configuration).openSession(true)) {
            NotificationInboxMapper mapper = session.getMapper(NotificationInboxMapper.class);
            mapper.insert(message("old-download", "download", 10));
            mapper.insert(message("kept-download", "download", 100));
            mapper.insert(message("kept-system", "system", 90));
            mapper.insert(message("overflow-system", "system", 80));
            mapper.insert(message("announcement", "announcement", 5));
            mapper.insert(message("survey", "survey", 5));

            assertThat(mapper.pruneRetentionPool(50, 2)).isEqualTo(2);
            assertThat(mapper.findLatest(null, false, 10)).extracting(NotificationMessage::id)
                    .containsExactlyInAnyOrder("kept-download", "kept-system", "announcement", "survey");

            assertThat(mapper.dismissAnnouncement("announcement", 110)).isEqualTo(1);
            assertThat(mapper.findById("announcement")).isNull();
            assertThat(mapper.findHtmlContent("announcement")).isNull();
            assertThat(mapper.blocksRemoteAnnouncementImport("announcement")).isTrue();
            assertThat(mapper.insert(message("announcement", "announcement", 120))).isZero();
            assertThat(mapper.updateRemoteAnnouncement(message("announcement", "announcement", 5))).isZero();
            assertThat(mapper.deleteNonAnnouncement("survey")).isEqualTo(1);
            assertThat(mapper.findById("survey")).isNull();

            NotificationMessage persistent = new NotificationMessage(
                    "persistent-survey:layout", "survey", "INFO", "layout",
                    "title.key", "body.key", null, null, "/survey/embed.html", 130, null);
            assertThat(mapper.insert(persistent)).isEqualTo(1);
            assertThat(mapper.setActivePersistentSurveys(List.of(persistent.id()))).isEqualTo(1);
            assertThat(mapper.dismissPersistentSurvey(persistent.id(), 140)).isEqualTo(1);
            assertThat(mapper.insert(persistent)).isZero();
            assertThat(mapper.setActivePersistentSurveys(List.of())).isEqualTo(1);
            assertThat(mapper.setActivePersistentSurveys(List.of(persistent.id()))).isEqualTo(1);
            assertThat(mapper.insert(persistent)).isZero();
            assertThat(mapper.findById(persistent.id())).isNull();
        }
    }

    private static NotificationMessage message(String id, String category, long createdTime) {
        return new NotificationMessage(id, category, "INFO", null,
                "Title " + id, "Body " + id,
                "https://sywyar.github.io/PixivDownloader-Remote-Content/" + id + ".html",
                "<!doctype html><p>" + id + "</p>", null, createdTime, null);
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
                        content_html TEXT,
                        action_url TEXT,
                        created_time INTEGER NOT NULL,
                        read_time INTEGER,
                        deleted_time INTEGER,
                        active INTEGER NOT NULL DEFAULT 1
                    )
                    """);
            statement.execute("""
                    CREATE TABLE notification_announcement_translations (
                        announcement_id TEXT NOT NULL,
                        locale TEXT NOT NULL,
                        title TEXT NOT NULL,
                        summary TEXT NOT NULL,
                        content_url TEXT NOT NULL,
                        content_sha256 TEXT NOT NULL,
                        content_html TEXT NOT NULL,
                        PRIMARY KEY (announcement_id, locale)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE notification_remote_index_state (
                        id INTEGER NOT NULL PRIMARY KEY,
                        sequence INTEGER NOT NULL,
                        manifest_sha256 TEXT NOT NULL,
                        generated_time INTEGER NOT NULL,
                        expires_time INTEGER NOT NULL,
                        etag TEXT,
                        last_modified TEXT
                    )
                    """);
        }
    }
}
