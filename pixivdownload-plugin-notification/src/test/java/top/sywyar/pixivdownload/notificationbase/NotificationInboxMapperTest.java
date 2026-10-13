package top.sywyar.pixivdownload.notificationbase;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.sql.DriverManager;
import javax.sql.DataSource;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

@DisplayName("站内信数据库 Mapper")
class NotificationInboxMapperTest {

    @TempDir
    Path tempDir;
    private Connection postgres;
    private String schema;
    private top.sywyar.pixivdownload.plugin.api.database.DatabaseConnectionTarget target;

    private DataSource dataSource(String engine) throws Exception {
        if ("sqlite".equals(engine)) {
            var result = new SQLiteDataSource();
            result.setUrl("jdbc:sqlite:" + tempDir.resolve("notifications.db"));
            target = new top.sywyar.pixivdownload.databasesqlite.SqliteDatabasePlugin().resolve(
                    new top.sywyar.pixivdownload.plugin.api.database.DatabaseConnectionSettings("", "", "", tempDir.resolve("notifications.db")));
            return result;
        }
        String url = System.getenv("PIXIV_TEST_POSTGRES_URL");
        org.junit.jupiter.api.Assumptions.assumeTrue(url != null && !url.isBlank(),
                "需要隔离 PostgreSQL 测试服务");
        postgres = DriverManager.getConnection(url, System.getenv("PIXIV_TEST_POSTGRES_USER"),
                System.getenv("PIXIV_TEST_POSTGRES_PASSWORD"));
        schema = "notification_test_" + java.util.UUID.randomUUID().toString().replace("-", "");
        try (var statement = postgres.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            statement.execute("SET search_path TO " + schema);
        }
        target = new top.sywyar.pixivdownload.databasepostgresql.PostgresDatabasePlugin().resolve(
                new top.sywyar.pixivdownload.plugin.api.database.DatabaseConnectionSettings(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                        System.getenv("PIXIV_TEST_POSTGRES_USER"), System.getenv("PIXIV_TEST_POSTGRES_PASSWORD"), tempDir));
        return new org.springframework.jdbc.datasource.SingleConnectionDataSource(postgres, true);
    }

    @AfterEach
    void close() throws Exception {
        if (postgres == null) return;
        try (var connection = postgres; var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"sqlite", "postgresql"})
    @DisplayName("按分类和未读状态查询、计数及批量已读真实可执行")
    void persistsQueriesAndMarksRead(String engine) throws Exception {
        DataSource dataSource = dataSource(engine);
        createSchema(dataSource);

        {
            NotificationInboxMapper mapper = mapper(dataSource);
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

    @ParameterizedTest
    @ValueSource(strings = {"sqlite", "postgresql"})
    @DisplayName("下载通知与系统共用保留池且公告删除后不会因重复入库复活")
    void prunesSharedRetentionPoolAndKeepsAnnouncementTombstones(String engine) throws Exception {
        DataSource dataSource = dataSource(engine);
        createSchema(dataSource);

        {
            NotificationInboxMapper mapper = mapper(dataSource);
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

    @ParameterizedTest @ValueSource(strings = {"sqlite", "postgresql"})
    @DisplayName("中性 CHECK 在真实提供者上保留所有通知、翻译与远程序号约束")
    void enforcesNeutralChecks(String engine) throws Exception {
        var source = dataSource(engine);
        createSchema(source);
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(source);
        jdbc.update("INSERT INTO notification_messages(id,category,severity,title,body,created_time) VALUES('fixture','system','INFO','Title','Body',100)");
        for (String assignment : List.of("category='unknown'", "severity='debug'", "title='  '", "body=' '",
                "content_html=''", "created_time=-1", "active=2", "read_time=99", "deleted_time=99")) {
            assertThatThrownBy(() -> jdbc.update("UPDATE notification_messages SET " + assignment))
                    .as(assignment).isInstanceOf(org.springframework.dao.DataAccessException.class)
                    .rootCause().satisfies(cause -> assertThat(cause.getMessage()).containsIgnoringCase("check constraint"));
        }
        jdbc.update("INSERT INTO notification_announcement_translations VALUES('a','en','Title','Summary','https://example.test',NULL,'html')");
        jdbc.update("UPDATE notification_announcement_translations SET content_sha256=?", "0123456789abcdef".repeat(4));
        for (String field : List.of("announcement_id", "locale", "title", "summary", "content_url", "content_html"))
            assertThatThrownBy(() -> jdbc.update("UPDATE notification_announcement_translations SET " + field + "=''"))
                    .as(field).isInstanceOf(org.springframework.dao.DataAccessException.class)
                    .rootCause().satisfies(cause -> assertThat(cause.getMessage()).containsIgnoringCase("check constraint"));
        for (String digest : List.of("A".repeat(64), "g".repeat(64), "a".repeat(63), "a".repeat(65), "a".repeat(63) + "🌸"))
            assertThatThrownBy(() -> jdbc.update("UPDATE notification_announcement_translations SET content_sha256=?", digest))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class)
                    .rootCause().satisfies(cause -> assertThat(cause.getMessage()).containsIgnoringCase("check constraint"));
        jdbc.update("INSERT INTO notification_remote_index_state(id,sequence,manifest_sha256,generated_time,expires_time) VALUES(1,1,?,0,1)", "a".repeat(64));
        for (String assignment : List.of("id=2", "sequence=0", "manifest_sha256='bad'", "generated_time=-1", "expires_time=0"))
            assertThatThrownBy(() -> jdbc.update("UPDATE notification_remote_index_state SET " + assignment))
                    .as(assignment).isInstanceOf(org.springframework.dao.DataAccessException.class)
                    .rootCause().satisfies(cause -> assertThat(cause.getMessage()).containsIgnoringCase("check constraint"));
        // 初始化和只读目录核验必须接受同一中性声明。
        createSchema(source);
    }

    private static NotificationMessage message(String id, String category, long createdTime) {
        return new NotificationMessage(id, category, "INFO", null,
                "Title " + id, "Body " + id,
                "https://sywyar.github.io/PixivDownloader-Remote-Content/" + id + ".html",
                "<!doctype html><p>" + id + "</p>", null, createdTime, null);
    }

    private static final top.sywyar.pixivdownload.plugin.api.database.migration.MigrationSchema DECLARATION =
            top.sywyar.pixivdownload.plugin.api.storage.relational.PrivateStorageSchema.merge(List.of(NotificationInboxSchema.CONTRIBUTION));

    private void createSchema(DataSource source) throws Exception {
        try (var connection = source.getConnection()) {
            connection.setAutoCommit(false);
            target.prepareTransaction(connection, false);
            target.applySchema(connection, DECLARATION, true);
            connection.commit();
            connection.setAutoCommit(true);
        }
    }

    private NotificationInboxMapper mapper(DataSource source) {
        return new RelationalNotificationInboxMapper(new top.sywyar.pixivdownload.plugin.api.storage.PluginMainDatabase() {
            @Override public boolean available() { return true; }
            @Override public <T> T read(top.sywyar.pixivdownload.plugin.api.storage.relational.DatabaseSession.Work<T> work) {
                return execute(work, true);
            }
            @Override public <T> T transaction(top.sywyar.pixivdownload.plugin.api.storage.relational.DatabaseSession.Work<T> work) {
                return execute(work, false);
            }
            private <T> T execute(top.sywyar.pixivdownload.plugin.api.storage.relational.DatabaseSession.Work<T> work, boolean readOnly) {
                try (var connection = source.getConnection()) {
                    connection.setAutoCommit(false);
                    try {
                        target.prepareTransaction(connection, readOnly);
                        try (var session = target.storageSession(connection, DECLARATION)) {
                            T result = work.run(session);
                            if (session.rollbackOnly()) throw new java.sql.SQLException("STORAGE_TRANSACTION_FAILED");
                            connection.commit();
                            return result;
                        }
                    } catch (Exception | Error failure) {
                        connection.rollback();
                        throw failure;
                    } finally { connection.setAutoCommit(true); }
                } catch (java.sql.SQLException failure) { throw new IllegalStateException(failure); }
            }
        });
    }
}
