package top.sywyar.pixivdownload.core.db;

import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import top.sywyar.pixivdownload.core.db.pathprefix.StoredPathCodec;
import top.sywyar.pixivdownload.core.db.schema.DatabaseInitializer;
import top.sywyar.pixivdownload.core.db.sqlite.SqliteDatabase;
import top.sywyar.pixivdownload.core.schedule.db.ScheduledTaskMapper;
import top.sywyar.pixivdownload.core.work.service.WorkTagCatalog;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.plugin.api.schema.SchemaContribution;
import top.sywyar.pixivdownload.plugin.registry.schema.DatabaseSchemaRegistry;

import javax.sql.DataSource;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named = "PIXIV_TEST_POSTGRES_URL", matches = ".+")
@DisplayName("官方插件在真实宿主结构上的双数据库行为")
class PluginDatabaseIntegrationTest {
    @TempDir Path directory;
    private PostgresTestDatabase postgres;
    private com.zaxxer.hikari.HikariDataSource sqlite;
    private URLClassLoader loader;
    private JdbcTemplate jdbc;
    private SqlSessionTemplate sessions;
    private DataSource source;
    private DatabaseInitializer initializer;
    private DatabaseSchemaRegistry registry;

    private void initialize(String engine) throws Exception {
        Path root = Path.of("").toAbsolutePath();
        if (!Files.isDirectory(root.resolve("pixivdownload-app"))) root = root.getParent();
        Path novel = root.resolve("pixivdownload-plugin-novel/target/classes");
        Path notification = root.resolve("pixivdownload-plugin-notification/target/classes");
        assertThat(novel.resolve("top/sywyar/pixivdownload/novel/db/NovelMapper.class")).isRegularFile();
        assertThat(notification.resolve("top/sywyar/pixivdownload/notificationbase/NotificationInboxSchema.class"))
                .isRegularFile();
        loader = new URLClassLoader(new URL[]{novel.toUri().toURL(), notification.toUri().toURL()},
                getClass().getClassLoader());
        if ("postgresql".equals(engine)) {
            postgres = new PostgresTestDatabase();
            source = postgres.pool();
        } else {
            var properties = new DatabasePoolProperties();
            properties.setJdbcUrl("jdbc:sqlite:" + directory.resolve("fixture.db"));
            sqlite = DatabaseTarget.resolve(properties, "unused").openPool(DatabasePoolProperties.MINIMUM_POOL_SIZE);
            source = sqlite;
        }
        jdbc = new JdbcTemplate(source);
        registry = DatabaseSchemaRegistry.forBuiltInPlugins();
        registry.register("notification", (SchemaContribution) loader
                .loadClass("top.sywyar.pixivdownload.notificationbase.NotificationInboxSchema")
                .getField("CONTRIBUTION").get(null));
        initializer = new DatabaseInitializer(jdbc, registry.contributions(), registry.mergedSchema(),
                TestI18nBeans.appMessages(), event -> {});
        initializer.initialize();
        createSessions();
    }

    private void createSessions() throws Exception {
        var configuration = new Configuration(new Environment("plugin-database",
                new SpringManagedTransactionFactory(), source));
        configuration.setDatabaseId(new DatabaseConfig(null, null, null).databaseIdProvider().getDatabaseId(source));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(loader.loadClass("top.sywyar.pixivdownload.novel.db.NovelMapper"));
        configuration.addMapper(loader.loadClass("top.sywyar.pixivdownload.notificationbase.NotificationInboxMapper"));
        configuration.addMapper(ScheduledTaskMapper.class);
        sessions = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(configuration));
    }

    @AfterEach
    void close() throws Exception {
        if (postgres != null) postgres.close();
        if (sqlite != null) sqlite.close();
        if (loader != null) loader.close();
    }

    private Object mapper() throws Exception {
        return sessions.getMapper(loader.loadClass("top.sywyar.pixivdownload.novel.db.NovelMapper"));
    }

    private Object novelDatabase(Object mapper) throws Exception {
        var codec = mock(StoredPathCodec.class);
        when(codec.encode(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        when(codec.resolve(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        return loader.loadClass("top.sywyar.pixivdownload.novel.db.NovelDatabase")
                .getConstructor(loader.loadClass("top.sywyar.pixivdownload.novel.db.NovelMapper"),
                        WorkTagCatalog.class, StoredPathCodec.class)
                .newInstance(mapper, mock(WorkTagCatalog.class), codec);
    }

    private Object call(Object receiver, String method, Object... arguments) {
        return org.springframework.test.util.ReflectionTestUtils.invokeMethod(receiver, method, arguments);
    }

    private void insert(Object database, long id, long time, Long order, String content) {
        call(database, "insertNovel", id, "章节 🌸", "{0}/novel", 1, "txt", time,
                null, false, null, null, 1L, null, 7L, order,
                null, 12, 1, 1, true, "ja", content, null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"sqlite", "postgresql"})
    @DisplayName("小说正文搜索、替换、空值排序、派生写入和软删除使用同一事务")
    void novelBehavior(String engine) throws Exception {
        initialize(engine);
        Object mapper = mapper();
        Object database = novelDatabase(mapper);
        call(database, "init");
        var transaction = new TransactionTemplate(new DatabaseConfig(null, null, null).transactionManager(source));
        transaction.executeWithoutResult(status -> {
            insert(database, 10, 1_700_000_000_110L, 2L, "冒险旅程 Apple 100% A_B C:\\花 🌸 École \"quoted\"");
            insert(database, 11, 1_700_000_000_111L, null, "猫と犬");
            insert(database, 12, 1_700_000_000_112L, 2L, "另一章");
            insert(database, 13, 1_700_000_000_113L, 1L, "已删除的冒险旅程");
            jdbc.update("UPDATE novels SET deleted = 1 WHERE novel_id = 13");
        });
        assertThat((Set<Long>) call(database, "searchNovelContentIds", "冒险旅程")).containsExactly(10L);
        assertThat((Set<Long>) call(database, "searchNovelContentIds", "apple")).containsExactly(10L);
        for (String keyword : List.of("%", "_", "C:\\花", "🌸", "école", "\"quoted\"")) {
            assertThat((Set<Long>) call(database, "searchNovelContentIds", keyword)).as(keyword).containsExactly(10L);
        }
        assertThat((Set<Long>) call(database, "searchNovelContentIds", "猫")).containsExactly(11L);
        assertThat((Set<Long>) call(database, "searchNovelContentIds", "missing")).isEmpty();
        var rows = (List<?>) call(mapper, "findBySeriesId", 7L);
        assertThat(rows.stream().map(row -> call(row, "novelId")).toList()).containsExactly(11L, 10L, 12L);
        assertThat(call(rows.get(0), "rawContent")).isEqualTo("猫と犬");
        assertThat(call(rows.get(0), "xRestrict")).isNull();
        assertThat(call(rows.get(0), "isAi")).isEqualTo(false);
        assertThat(call(rows.get(0), "isOriginal")).isEqualTo(true);
        call(mapper, "insertNovelImage", 10L, "image-a", "jpg");
        call(mapper, "insertNovelImage", 10L, "image-a", "png");
        assertThat(call(mapper, "findNovelImageExt", 10L, "image-a")).isEqualTo("png");
        call(mapper, "insertGlossaryEntryIfAbsent", 1L, "勇者", "en", "Hero", 1_700_000_000_000L);
        call(mapper, "insertGlossaryEntryIfAbsent", 1L, "勇者", "en", "Ignored", 1_700_000_000_001L);
        call(mapper, "upsertGlossaryEntry", 1L, "勇者", "en", "Champion", 1_700_000_000_002L);
        assertThat(jdbc.queryForObject("SELECT target FROM novel_glossary_entries", String.class)).isEqualTo("Champion");
        call(mapper, "upsertNarrationScript", 10L, "", 1L, 100, 1_700_000_000_000L, "{}");
        jdbc.update("UPDATE novels SET file_base_name='old', metadata_json='{}', upload_time=1700000000000,"
                + " file_name_max_length=40 WHERE novel_id=10");
        insert(database, 10, 1_700_000_000_110L, 2L, "更新后的正文");
        assertThat(jdbc.queryForObject("SELECT file_name_max_length FROM novels WHERE novel_id=10", Integer.class)).isEqualTo(180);
        assertThat(jdbc.queryForObject("SELECT metadata_json FROM novels WHERE novel_id=10", String.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT file_base_name FROM novels WHERE novel_id=10", String.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM novel_narration_scripts", Long.class)).isZero();
        insert(database, 14, 1_700_000_000_112L, null, "时间冲突替换");
        assertThat(call(mapper, "findById", 12L)).isNull();
        for (String table : List.of("novel_tags", "novel_collections", "novel_translations", "novel_narration_scripts")) {
            // 下方通过各自 Mapper 保存，避免绕过该领域的 SQL 与参数映射。
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class)).isZero();
        }
        call(mapper, "insertNovelTag", 10L, 2L);
        jdbc.update("INSERT INTO novel_collections(collection_id,novel_id,added_time) VALUES(1,10,1700000000000)");
        call(mapper, "insertOrReplaceTranslation", 10L, "en", "translated", null, null, 1_700_000_000_000L);
        call(mapper, "upsertNarrationScript", 10L, "", 1L, 100, 1_700_000_000_000L, "{}");
        assertConstraint(() -> transaction.executeWithoutResult(status -> {
            jdbc.update("UPDATE novels SET deleted = 1 WHERE novel_id = 10");
            jdbc.update("INSERT INTO tags(name) VALUES (NULL)");
        }), engine, "23502");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM novel_tags", Long.class)).isOne();
        jdbc.update("UPDATE novels SET deleted = 1 WHERE novel_id = 10");
        for (String table : List.of("novel_tags", "novel_collections", "novel_images",
                "novel_translations", "novel_narration_scripts")) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE novel_id = 10", Long.class))
                    .as(table).isZero();
        }
        assertThat((Set<Long>) call(database, "searchNovelContentIds", "更新后")).isEmpty();
        initializer.initialize();
        call(database, "init");
    }

    @ParameterizedTest
    @ValueSource(strings = {"sqlite", "postgresql"})
    @DisplayName("通知生产 CHECK 拒绝非法摘要、空正文与倒退时间并在重启时校验")
    void notificationConstraints(String engine) throws Exception {
        initialize(engine);
        initializer.initialize();
        jdbc.update("INSERT INTO notification_messages(id,category,severity,title,body,created_time)"
                + " VALUES('fixture','announcement','INFO','标题','正文',1700000000123)");
        assertConstraint(() -> jdbc.update("UPDATE notification_messages SET read_time=1"), engine, "23514");
        assertConstraint(() -> jdbc.update("UPDATE notification_messages SET body=' '"), engine, "23514");
        String sql = "INSERT INTO notification_remote_index_state"
                + "(id,sequence,manifest_sha256,generated_time,expires_time) VALUES(1,1,?,1700000000000,1800000000000)";
        assertConstraint(() -> jdbc.update(sql, "Z".repeat(64)), engine, "23514");
        jdbc.update(sql, "a".repeat(64));
        assertThat(jdbc.queryForObject("SELECT manifest_sha256 FROM notification_remote_index_state", String.class))
                .isEqualTo("a".repeat(64));
    }

    private void assertConstraint(Runnable action, String engine, String state) {
        Throwable failure = catchThrowable(action::run);
        assertThat(failure).isInstanceOf(org.springframework.dao.DataAccessException.class);
        Throwable cause = ((org.springframework.dao.DataAccessException) failure).getMostSpecificCause();
        assertThat(cause).isInstanceOf(java.sql.SQLException.class);
        var sql = (java.sql.SQLException) cause;
        if ("postgresql".equals(engine)) assertThat(sql.getSQLState()).isEqualTo(state);
        else assertThat(sql.getErrorCode()).isEqualTo(19);
    }
}
