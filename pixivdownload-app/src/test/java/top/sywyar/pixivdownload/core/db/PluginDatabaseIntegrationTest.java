package top.sywyar.pixivdownload.core.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import top.sywyar.pixivdownload.ai.AiChatClient;
import top.sywyar.pixivdownload.ai.model.AiChatResult;
import top.sywyar.pixivdownload.core.db.pathprefix.StoredPathCodec;
import top.sywyar.pixivdownload.core.db.schema.DatabaseInitializer;

import top.sywyar.pixivdownload.core.database.DatabaseAvailability;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
    private Object novelMapper;
    private Object notificationMapper;
    private org.springframework.transaction.PlatformTransactionManager transactions;
    private final java.util.List<MainDatabase.OwnerStorage> handles = new java.util.ArrayList<>();
    private DataSource source;
    private DatabaseInitializer initializer;
    private DatabaseSchemaRegistry registry;
    private MainDatabase main;
    private String previousDataDirectory;
    private boolean isolatedFileOperations;

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
            sqlite = top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.resolve(properties, "unused").openPool(DatabasePoolProperties.MINIMUM_POOL_SIZE);
            source = sqlite;
        }
        jdbc = new JdbcTemplate(source);
        registry = DatabaseSchemaRegistry.forBuiltInPlugins();
        registry.register("notification", (SchemaContribution) loader
                .loadClass("top.sywyar.pixivdownload.notificationbase.NotificationInboxSchema")
                .getField("CONTRIBUTION").get(null));
        initializer = top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.initializer(jdbc, registry.contributions(), registry.mergedSchema(),
                TestI18nBeans.appMessages(), event -> {});
        initializer.initialize();
        createSessions();
    }

    private void createSessions() throws Exception {
        handles.forEach(MainDatabase.OwnerStorage::close);
        handles.clear();
        var target = DatabaseProviderTestFixtures.target(source);
        transactions = new DatabaseConfig(null, null).transactionManager(source, target);
        main = new MainDatabase(source, target, registry, transactions);
        var novels = main.forOwner("novel", List.of("pixiv-novels", "pixiv-tags"));
        var notifications = main.forOwner("notification", List.of());
        handles.add(novels);
        handles.add(notifications);
        novelMapper = transactional(loader.loadClass("top.sywyar.pixivdownload.novel.db.RelationalNovelMapper")
                .getConstructor(top.sywyar.pixivdownload.plugin.api.storage.PluginMainDatabase.class).newInstance(novels));
        notificationMapper = loader.loadClass("top.sywyar.pixivdownload.notificationbase.RelationalNotificationInboxMapper")
                .getConstructor(top.sywyar.pixivdownload.plugin.api.storage.PluginMainDatabase.class).newInstance(notifications);
    }

    private Object transactional(Object bean) {
        var proxy = new org.springframework.aop.framework.ProxyFactory(bean);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(transactions,
                new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        return proxy.getProxy(loader);
    }

    @ParameterizedTest
    @ValueSource(strings = {"sqlite", "postgresql"})
    @DisplayName("提供者接管既有小说触发器和全文索引，保留正文并继续清理关联")
    void adoptsExistingNovelAuxiliaryObjects(String engine) throws Exception {
        initialize(engine);
        String deletes = "DELETE FROM novel_tags WHERE novel_id = NEW.novel_id; "
                + "DELETE FROM novel_collections WHERE novel_id = NEW.novel_id; "
                + "DELETE FROM novel_images WHERE novel_id = NEW.novel_id; "
                + "DELETE FROM novel_translations WHERE novel_id = NEW.novel_id; "
                + "DELETE FROM novel_narration_scripts WHERE novel_id = NEW.novel_id; ";
        // 已发布对象的原始定义是升级夹具，不依赖当前提供者的 DDL 生成器。
        if (engine.equals("sqlite")) {
            jdbc.execute("CREATE VIRTUAL TABLE IF NOT EXISTS novels_fts USING fts5(content, tokenize='trigram')");
            jdbc.execute("CREATE TRIGGER IF NOT EXISTS novel_soft_delete_cleanup AFTER UPDATE OF deleted ON novels"
                    + " WHEN NEW.deleted = 1 BEGIN " + deletes + "END");
        } else {
            jdbc.execute("CREATE OR REPLACE FUNCTION novel_soft_delete_cleanup() RETURNS trigger LANGUAGE plpgsql"
                    + " SET search_path FROM CURRENT AS $body$ BEGIN " + deletes + "RETURN NEW; END $body$");
            jdbc.execute("CREATE OR REPLACE TRIGGER novel_soft_delete_cleanup AFTER UPDATE OF deleted ON novels"
                    + " FOR EACH ROW WHEN (NEW.deleted = 1) EXECUTE FUNCTION novel_soft_delete_cleanup()");
        }
        jdbc.update("INSERT INTO novels(novel_id,title,folder,count,extensions,time,raw_content)"
                + " VALUES(77,'existing','{0}/novel',1,'txt',1700000000777,'保留既有正文')");
        jdbc.update("INSERT INTO novel_images(novel_id,image_id,ext) VALUES(77,'retained','png')");
        Object database = novelDatabase(mapper());
        call(database, "init");
        assertThat((Set<Long>) call(database, "searchNovelContentIds", "既有正文")).containsExactly(77L);
        jdbc.update("UPDATE novels SET deleted=1 WHERE novel_id=77");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM novel_images WHERE novel_id=77", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT raw_content FROM novels WHERE novel_id=77", String.class)).isEqualTo("保留既有正文");
        assertThat((Set<Long>) call(database, "searchNovelContentIds", "既有正文")).isEmpty();
    }

    @Test
    @DisplayName("全部宿主业务表迁移后保留历史、文件引用和私有库，搜索与后续条件写入正常")
    void migratesEveryManagedBusinessTableAndContinuesUsingProductionMappers() throws Exception {
        initialize("sqlite");
        try (var connection = source.getConnection()) {
            org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
                    new org.springframework.core.io.support.EncodedResource(
                            new org.springframework.core.io.ClassPathResource("db/migration-business.sql"),
                            java.nio.charset.StandardCharsets.UTF_8));
        }
        Object oldNovel = novelDatabase(mapper());
        call(oldNovel, "init");
        assertThat((Set<Long>) call(oldNovel, "searchNovelContentIds", "冒险旅程")).containsExactly(5000000001L);
        java.util.Map<String, Long> sourceCounts = new java.util.TreeMap<>();
        for (String table : registry.mergedSchema().tables().keySet()) {
            long count = jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
            assertThat(count).as("夹具必须覆盖受管表 %s", table).isPositive();
            sourceCounts.put(table, count);
        }
        Path privateDb = directory.resolve("owner/plugin.db");
        Files.createDirectories(privateDb.getParent());
        try (var connection = top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.openConnection(privateDb); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE private_history(value TEXT)");
            statement.execute("INSERT INTO private_history VALUES('owner 私有记录')");
        }
        byte[] privateBefore = Files.readAllBytes(privateDb);
        Path media = directory.resolve("原始文件名 🌸.png");
        Files.write(media, new byte[]{0, 1, 2, (byte) 255});
        byte[] mediaBefore = Files.readAllBytes(media);
        sqlite.close();
        postgres = new PostgresTestDatabase();
        var result = DatabaseProviderTestFixtures.migrate(top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.sqlite(directory.resolve("fixture.db")), directory.resolve("backup.db"),
                top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.resolve(postgres.properties(), "unused"), registry, true,
                Set.of("novels_fts", "novel_soft_delete_cleanup"));
        assertThat(result.verification().tables().keySet()).containsExactlyInAnyOrderElementsOf(sourceCounts.keySet());
        result.verification().tables().forEach((table, value) -> assertThat(value.rows()).as(table).isEqualTo(sourceCounts.get(table)));
        assertThat(result.verification().referenceWarnings()).isEmpty();
        assertThat(Files.readAllBytes(privateDb)).isEqualTo(privateBefore);
        assertThat(Files.readAllBytes(media)).isEqualTo(mediaBefore);
        source = postgres.pool();
        jdbc = new JdbcTemplate(source);
        assertThat(jdbc.queryForObject("SELECT collection_unrestricted FROM guest_invites WHERE id=104", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT collection_restricts_works FROM guest_invites WHERE id=104", Integer.class)).isOne();
        assertThat(jdbc.queryForList("SELECT collection_id FROM guest_invite_collections WHERE invite_id=104", Long.class))
                .containsExactly(103L);
        initializer = top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.initializer(jdbc, registry.contributions(), registry.mergedSchema(),
                TestI18nBeans.appMessages(), event -> {});
        initializer.initialize();
        createSessions();
        Object novel = novelDatabase(mapper());
        call(novel, "init");
        for (String term : List.of("冒险旅程", "école", "%", "_")) {
            assertThat((Set<Long>) call(novel, "searchNovelContentIds", term)).containsExactly(5000000001L);
        }
        var schedule = new ScheduledTaskMapper(top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.main(source));
        assertThat(schedule.updateEnabled(108, 7, false)).isEqualTo(8L);
        assertThat(schedule.updateEnabled(108, 7, true)).isNull();
        assertThat(jdbc.queryForObject("SELECT checkpoint_json FROM scheduled_tasks", String.class))
                .isEqualTo("{\"after\":\"5000000001\"}");
        Object notifications = notificationMapper;
        assertThat(call(notifications, "blocksRemoteAnnouncementImport", "deleted-fixture")).isEqualTo(true);
        assertThat(call(notifications, "acceptRemoteAnnouncementIndex", 122L, "b".repeat(64),
                1700000000123L, 1701000000123L)).isEqualTo(0);
        assertThat(call(notifications, "acceptRemoteAnnouncementIndex", 124L, "b".repeat(64),
                1700000000123L, 1701000000123L)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT etag FROM notification_remote_index_state", String.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT read_time FROM notification_messages WHERE id='announcement-fixture'", Long.class))
                .isEqualTo(1700000000124L);
        assertThat(jdbc.queryForObject("SELECT metadata_json FROM artworks WHERE artwork_id=5000000001", String.class))
                .isEqualTo("{\"title\":\"作品 🌸\"}");
        assertThat(jdbc.queryForObject("SELECT file_path FROM external_work_files", String.class))
                .isEqualTo("{77}/插画/原始文件名 🌸.png");
        var transaction = new TransactionTemplate(postgres.transactionManager());
        transaction.executeWithoutResult(status -> jdbc.update("UPDATE novels SET deleted=1 WHERE novel_id=5000000001"));
        assertThat((Set<Long>) call(novel, "searchNovelContentIds", "冒险旅程")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM novel_translations", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM novel_narration_scripts", Long.class)).isZero();
        jdbc.update("INSERT INTO tags(name) VALUES('迁移后')");
        assertThat(jdbc.queryForObject("SELECT tag_id FROM tags WHERE name='迁移后'", Long.class)).isGreaterThan(101L);
        insert(novel, 5000000003L, 1700000000125L, null, "迁移后新增正文");
        assertThat((Set<Long>) call(novel, "searchNovelContentIds", "新增正文")).containsExactly(5000000003L);
        try (var connection = top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.openExisting(result.backup()); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT deleted FROM novels WHERE novel_id=5000000001")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getInt(1)).isZero();
        }
    }

    @AfterEach
    void close() throws Exception {
        if (isolatedFileOperations) {
            String key = top.sywyar.pixivdownload.config.RuntimeFiles.DATA_DIR_PROPERTY;
            if (previousDataDirectory == null) System.clearProperty(key);
            else System.setProperty(key, previousDataDirectory);
        }
        handles.forEach(MainDatabase.OwnerStorage::close);
        if (postgres != null) postgres.close();
        if (sqlite != null) sqlite.close();
        if (loader != null) loader.close();
        top.sywyar.pixivdownload.testsupport.TestDirectoryCleanup.delete(directory);
    }

    private Object mapper() throws Exception {
        return novelMapper;
    }

    private Object referenceService(Object mapper,
            top.sywyar.pixivdownload.core.asset.FileTransactionService files) throws Exception {
        var runtimePaths = mock(top.sywyar.pixivdownload.plugin.api.storage.RuntimePathProvider.class);
        when(runtimePaths.dataDirectory()).thenReturn(Files.createDirectories(directory.resolve("novel")));
        DatabaseAvailability available = () -> true;
        String prefix = "top.sywyar.pixivdownload.novel.narration.";
        Class<?> pathsType = loader.loadClass(prefix + "NarrationReferenceVoicePaths");
        Object paths = pathsType.getConstructors()[0].newInstance(runtimePaths, available);
        Object store = loader.loadClass(prefix + "NarrationReferenceVoiceStore")
                .getConstructors()[0].newInstance(paths, available, files);
        Class<?> audioType = loader.loadClass(prefix + "audio.NarrationAudioService");
        return loader.loadClass(prefix + "NarrationReferenceVoiceService").getConstructors()[0]
                .newInstance(mapper, mock(audioType), store, paths, available);
    }

    private top.sywyar.pixivdownload.core.asset.StagedFileDeletion fileTransactions() {
        String key = top.sywyar.pixivdownload.config.RuntimeFiles.DATA_DIR_PROPERTY;
        previousDataDirectory = System.getProperty(key);
        isolatedFileOperations = true;
        System.setProperty(key, directory.resolve("file-journal").toString());
        var config = new top.sywyar.pixivdownload.core.appconfig.DownloadConfig();
        config.setRootFolder(directory.resolve("works").toString());
        var paths = new top.sywyar.pixivdownload.core.db.pathprefix.PathPrefixCodec(
                new top.sywyar.pixivdownload.core.db.pathprefix.PathPrefixMapper(main),
                config, TestI18nBeans.appMessages());
        paths.init();
        var journal = new top.sywyar.pixivdownload.core.asset.FileOperationJournal(
                main, transactions, paths, initializer);
        return new top.sywyar.pixivdownload.core.asset.StagedFileDeletion(TestI18nBeans.appMessages(), journal);
    }

    private long createReferenceCast() throws Exception {
        Object cast = loader.loadClass("top.sywyar.pixivdownload.novel.db.NovelNarrationCastInsert")
                .getConstructor().newInstance();
        call(cast, "setName", "Reference voice");
        call(cast, "setCreatedTime", 1700000000000L);
        call(cast, "setUpdatedTime", 1700000000000L);
        call(novelMapper, "insertNarrationCast", cast);
        long id = (Long) call(cast, "getId");
        call(novelMapper, "insertNarrationVoiceIfAbsent", id, 1, "Speaker", "unknown", "unknown",
                "Voice", false, 1700000000000L);
        return id;
    }

    @ParameterizedTest
    @ValueSource(strings = {"sqlite", "postgresql"})
    @DisplayName("参考音替换与删除的记录失败恢复旧字节、元数据和缓存时间，重试后完整提交")
    void referenceFilesAndRecordsCommitTogether(String engine) throws Exception {
        initialize(engine);
        long castId = createReferenceCast();
        var failureAt = new java.util.concurrent.atomic.AtomicReference<String>();
        var failure = new IllegalStateException("controlled record failure");
        Object mapper = java.lang.reflect.Proxy.newProxyInstance(loader,
                new Class<?>[]{loader.loadClass("top.sywyar.pixivdownload.novel.db.NovelMapper")},
                (proxy, method, arguments) -> {
                    if ("missing".equals(failureAt.get()) && method.getName().equals("updateNarrationVoiceReference")) {
                        return 0;
                    }
                    try {
                        Object result = method.invoke(novelMapper, arguments);
                        if (method.getName().equals(failureAt.get())) throw failure;
                        return result;
                    } catch (java.lang.reflect.InvocationTargetException error) {
                        throw error.getCause();
                    }
                });
        Object service = referenceService(mapper, fileTransactions());
        byte[] original = {1, 2, 3};
        call(service, "saveUpload", castId, 1, original, "wav", "old text");
        Path castDirectory = directory.resolve("novel/narration-voice").resolve(Long.toString(castId));
        Path originalFile = castDirectory.resolve("1.wav");
        Path other = Files.write(castDirectory.resolve("2.mp3"), new byte[]{8});
        Object originalRef = call(novelMapper, "findNarrationVoiceRef", castId, 1);
        Object originalTime = call(call(novelMapper, "findNarrationCastById", castId), "updatedTime");

        for (String ext : List.of("wav", "mp3")) {
            for (String point : List.of("updateNarrationVoiceReference", "touchNarrationCast", "missing")) {
                failureAt.set(point);
                if (point.equals("missing")) {
                    assertThat(call(service, "saveUpload", castId, 1, new byte[]{4, 5}, ext, "new text")).isNull();
                } else {
                    assertThatThrownBy(() -> call(service, "saveUpload", castId, 1, new byte[]{4, 5}, ext, "new text"))
                            .isSameAs(failure);
                }
                assertThat(Files.readAllBytes(originalFile)).isEqualTo(original);
                assertThat(call(novelMapper, "findNarrationVoiceRef", castId, 1)).isEqualTo(originalRef);
                assertThat(call(call(novelMapper, "findNarrationCastById", castId), "updatedTime")).isEqualTo(originalTime);
                try (var remaining = Files.list(castDirectory)) {
                    assertThat(remaining.toList()).containsExactlyInAnyOrder(originalFile, other);
                }
            }
        }
        failureAt.set(null);
        Object updated = call(service, "saveUpload", castId, 1, new byte[]{4, 5}, "mp3", "new text");
        assertThat(call(updated, "ext")).isEqualTo("mp3");
        assertThat(originalFile).doesNotExist();
        Path replacement = castDirectory.resolve("1.mp3");
        assertThat(Files.readAllBytes(replacement)).containsExactly(4, 5);
        Object updatedTime = call(call(novelMapper, "findNarrationCastById", castId), "updatedTime");
        for (String point : List.of("clearNarrationVoiceReference", "touchNarrationCast")) {
            failureAt.set(point);
            assertThatThrownBy(() -> call(service, "delete", castId, 1)).isSameAs(failure);
            assertThat(Files.readAllBytes(replacement)).containsExactly(4, 5);
            assertThat(call(novelMapper, "findNarrationVoiceRef", castId, 1)).isEqualTo(updated);
            assertThat(call(call(novelMapper, "findNarrationCastById", castId), "updatedTime")).isEqualTo(updatedTime);
        }
        failureAt.set(null);
        call(service, "delete", castId, 1);
        assertThat(replacement).doesNotExist();
        assertThat(call(call(novelMapper, "findNarrationVoiceRef", castId, 1), "present")).isEqualTo(false);
        assertThat(Files.readAllBytes(other)).containsExactly(8);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM file_operation_entries", Long.class)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"sqlite", "postgresql"})
    @DisplayName("参考音读取等待同角色提交，返回同一版本的音频与转录")
    void referenceReadWaitsForPublication(String engine) throws Exception {
        initialize(engine);
        long castId = createReferenceCast();
        var files = fileTransactions();
        var pause = new java.util.concurrent.atomic.AtomicBoolean();
        var publishing = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        Object service = referenceService(novelMapper, (replacements, deletions, records) ->
                files.changeFiles(replacements, deletions, () -> {
                    if (pause.get()) {
                        publishing.countDown();
                        try {
                            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
                        } catch (InterruptedException error) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(error);
                        }
                    }
                    records.run();
                }));
        call(service, "saveUpload", castId, 1, new byte[]{1}, "wav", "old");
        pause.set(true);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var writer = workers.submit(() -> call(service, "saveUpload", castId, 1, new byte[]{2}, "wav", "new"));
            assertThat(publishing.await(10, TimeUnit.SECONDS)).isTrue();
            var reader = workers.submit(() -> call(service, "resolve", castId, 1));
            assertThatThrownBy(() -> reader.get(100, TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);
            release.countDown();
            writer.get(10, TimeUnit.SECONDS);
            var voice = (top.sywyar.pixivdownload.tts.narration.engine.NarrationReferenceVoice)
                    reader.get(10, TimeUnit.SECONDS);
            assertThat(voice.audio()).containsExactly(2);
            assertThat(voice.text()).isEqualTo("new");
        } finally {
            release.countDown();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private Object novelDatabase(Object mapper) throws Exception {
        var codec = mock(StoredPathCodec.class);
        when(codec.encode(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        when(codec.resolve(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        return transactional(loader.loadClass("top.sywyar.pixivdownload.novel.db.NovelDatabase")
                .getConstructor(loader.loadClass("top.sywyar.pixivdownload.novel.db.NovelMapper"),
                        WorkTagCatalog.class, StoredPathCodec.class, DatabaseAvailability.class)
                .newInstance(mapper, mock(WorkTagCatalog.class), codec, (DatabaseAvailability) () -> true));
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
        var target = postgres != null ? top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.resolve(postgres.properties(), "unused")
                : top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.sqlite(directory.resolve("fixture.db"));
        var transaction = new TransactionTemplate(new DatabaseConfig(null, null).transactionManager(source, target));
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


    @ParameterizedTest
    @ValueSource(strings = {"sqlite", "postgresql"})
    @DisplayName("小说生成键、关联投影、手工角色覆盖与参考音在真实数据库中保持一致")
    void novelGeneratedKeysAndReferences(String engine) throws Exception {
        initialize(engine);
        Object mapper = mapper();
        Object database = novelDatabase(mapper);
        call(database, "init");
        insert(database, 51, 1_700_000_000_051L, 1L, "原始正文");
        Object glossary = loader.loadClass("top.sywyar.pixivdownload.novel.db.NovelGlossaryInsert")
                .getConstructor().newInstance();
        call(glossary, "setName", "术语");
        call(glossary, "setSeriesId", 7L);
        call(glossary, "setCreatedTime", 1_700_000_000_000L);
        call(glossary, "setUpdatedTime", 1_700_000_000_001L);
        call(mapper, "insertGlossary", glossary);
        long glossaryId = (Long) call(glossary, "getId");
        assertThat(glossaryId).isPositive();
        call(mapper, "upsertGlossaryEntry", glossaryId, "勇者", "en", "Hero", 1_700_000_000_002L);
        assertThat(call(call(mapper, "findGlossaryBySeriesId", 7L), "entryCount")).isEqualTo(1);
        assertThat((List<?>) call(mapper, "findAllGlossaries")).hasSize(1);
        Object cast = loader.loadClass("top.sywyar.pixivdownload.novel.db.NovelNarrationCastInsert")
                .getConstructor().newInstance();
        call(cast, "setName", "角色");
        call(cast, "setNovelId", 51L);
        call(cast, "setCreatedTime", 1_700_000_000_000L);
        call(cast, "setUpdatedTime", 1_700_000_000_001L);
        call(mapper, "insertNarrationCast", cast);
        long castId = (Long) call(cast, "getId");
        call(mapper, "insertNarrationVoiceIfAbsent", castId, 0, "旁白", "female", "adult", "old", false, 1_700_000_000_002L);
        call(mapper, "updateNarrationVoiceReference", castId, 0, "wav", "参考正文", "upload", 1_700_000_000_003L);
        Object reference = call(mapper, "findNarrationVoiceRef", castId, 0);
        call(mapper, "upsertNarrationVoice", castId, 0, "新旁白", "male", "adult", "new", true, 1_700_000_000_004L);
        assertThat(call(mapper, "findNarrationVoiceRef", castId, 0)).isEqualTo(reference);
        assertThat(call(call(mapper, "findNarrationCastByNovelId", 51L), "voiceCount")).isEqualTo(1);
        Object voice = ((List<?>) call(mapper, "findNarrationVoices", castId)).get(0);
        assertThat(call(voice, "name")).isEqualTo("新旁白");
        assertThat(call(voice, "editedByUser")).isEqualTo(true);
        assertThat(jdbc.queryForObject("SELECT created_time FROM novel_narration_voices", Long.class))
                .isEqualTo(1_700_000_000_002L);
        for (boolean edited : List.of(false, true)) {
            assertThat(call(mapper, "updateNarrationVoiceInstruction", castId, 0, "更新音色", edited)).isEqualTo(1);
            Object updated = ((List<?>) call(mapper, "findNarrationVoices", castId)).get(0);
            assertThat(call(updated, "controlInstruction")).isEqualTo("更新音色");
            assertThat(call(updated, "editedByUser")).isEqualTo(edited);
        }
        assertThat(call(mapper, "clearNarrationVoiceReference", castId, 0)).isEqualTo(1);
        Object cleared = call(mapper, "findNarrationVoiceRef", castId, 0);
        for (String field : List.of("ext", "text", "source", "time")) {
            assertThat(call(cleared, field)).as(field).isNull();
        }
        call(mapper, "insertOrReplaceTranslation", 51L, "en", "body", "title", "description", 1_700_000_000_005L);
        assertThat((List<String>) call(mapper, "findSeriesTranslatedLangs", 7L)).containsExactly("en");
        assertThat((List<?>) call(mapper, "findTranslationLangsByIds", List.of(51L))).hasSize(1);
        call(mapper, "insertNovelImage", 51L, "image-1", "png");
        assertThat((List<?>) call(mapper, "findNovelImageIdsByIds", List.of(51L))).hasSize(1);
        assertThat((List<?>) call(mapper, "findWorkDetailsByIds", List.of(51L))).hasSize(1);
        assertThat((List<?>) call(mapper, "findWordCountsByIds", List.of(51L))).hasSize(1);
    }

    @ParameterizedTest
    @CsvSource({
            "sqlite, translation.NovelGlossaryService, novel_glossaries",
            "postgresql, translation.NovelGlossaryService, novel_glossaries",
            "sqlite, narration.NovelNarrationCastService, novel_narration_casts",
            "postgresql, narration.NovelNarrationCastService, novel_narration_casts"
    })
    @DisplayName("并发创建复用同一默认映射表或花名册，无绑定的共享表仍独立")
    void novelDefaultsAreReusedDuringConcurrentCreation(String engine, String serviceName, String table) throws Exception {
        initialize(engine);
        var constructor = loader.loadClass("top.sywyar.pixivdownload.novel." + serviceName).getConstructors()[0];
        Object[] dependencies = java.util.Arrays.stream(constructor.getParameterTypes())
                .map(type -> type.isInstance(novelMapper) ? novelMapper : mock(type)).toArray();
        Object service = constructor.newInstance(dependencies);
        var executor = Executors.newFixedThreadPool(8);
        try {
            for (String binding : List.of("series_id", "novel_id")) {
                Long seriesId = binding.equals("series_id") ? 71L : null;
                Long novelId = binding.equals("novel_id") ? 81L : null;
                var ready = new CountDownLatch(8);
                var start = new CountDownLatch(1);
                List<Future<Object>> results = new java.util.ArrayList<>();
                for (int i = 0; i < 8; i++) {
                    results.add(executor.submit(() -> {
                        ready.countDown();
                        assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                        return call(service, "create", "Default", seriesId, novelId);
                    }));
                }
                try {
                    assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                } finally {
                    start.countDown();
                }
                var ids = new java.util.HashSet<Object>();
                for (var result : results) ids.add(call(result.get(10, TimeUnit.SECONDS), "id"));
                assertThat(ids).hasSize(1);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + binding + " = ?",
                        Long.class, seriesId == null ? novelId : seriesId)).isOne();
                assertThat(call(call(service, "create", "Ignored rename", seriesId, novelId), "name"))
                        .isEqualTo("Default");
            }
            Object first = call(service, "create", "Shared", null, null);
            Object second = call(service, "create", "Shared", null, null);
            assertThat(call(first, "id")).isNotEqualTo(call(second, "id"));
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"sqlite", "postgresql"})
    @DisplayName("朗读分析返回后保留期间的用户编辑，并为新角色分配未占用编号")
    void narrationAnalysisPreservesRosterChangesDuringAiRequest(String engine) throws Exception {
        initialize(engine);
        AiChatClient ai = mock(AiChatClient.class);
        Object service = narrationCastService(ai);
        long castId = (long) call(call(service, "create", "Cast", null, null), "id");
        call(novelMapper, "insertNarrationVoiceIfAbsent", castId, 1, "I", "unknown", "unknown", "Original", false, 1L);
        when(ai.chat(anyString(), any(), any())).thenAnswer(invocation -> {
            call(service, "updateVoiceInstruction", castId, 1, "User voice");
            call(novelMapper, "insertNarrationVoiceIfAbsent", castId, 2, "Bob", "unknown", "unknown", "Bob voice", false, 2L);
            return new AiChatResult("""
                    {"lines":[{"i":0,"speaker":2}],
                     "newCharacters":[{"id":2,"name":"Carol","instruction":"Carol voice"}],
                     "updatedCharacters":[{"id":1,"name":"Alice","instruction":"AI voice"}]}
                    """, "stop", null, null, null);
        });
        Object sentence = loader.loadClass("top.sywyar.pixivdownload.novel.narration.analysis.NarrationSentence")
                .getConstructor(String.class, int.class).newInstance("Carol speaks.", 0);
        Object result = call(service, "analyzeChapter", 7L, List.of(sentence), 0, castId);
        var names = jdbc.queryForList("SELECT name FROM novel_narration_voices WHERE cast_id=? ORDER BY character_id",
                String.class, castId);
        var lines = (List<?>) call(call(result, "script"), "lines");
        org.assertj.core.api.SoftAssertions.assertSoftly(soft -> {
            soft.assertThat(names).containsExactly("Narrator", "I", "Bob", "Carol");
            soft.assertThat(call(lines.get(0), "speakerId")).isEqualTo(3);
            soft.assertThat((List<?>) call(result, "conflicts")).isEmpty();
            soft.assertThat(jdbc.queryForObject("SELECT control_instruction FROM novel_narration_voices"
                    + " WHERE cast_id=? AND character_id=1", String.class, castId)).isEqualTo("User voice");
            soft.assertThat(call(novelMapper, "updateNarrationVoiceName", castId, 1, "Late rename")).isEqualTo(0);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"sqlite", "postgresql"})
    @DisplayName("同册章节可以并行分析，落库后每个新角色保持独立编号和台词归属")
    void concurrentNarrationAnalysisPreservesDistinctSpeakers(String engine) throws Exception {
        initialize(engine);
        AiChatClient ai = mock(AiChatClient.class);
        Object service = narrationCastService(ai);
        long castId = (long) call(call(service, "create", "Cast", null, null), "id");
        var analyzing = new CountDownLatch(4);
        var nextName = new java.util.concurrent.atomic.AtomicInteger();
        when(ai.chat(anyString(), any(), any())).thenAnswer(invocation -> {
            int number = nextName.incrementAndGet();
            analyzing.countDown();
            assertThat(analyzing.await(10, TimeUnit.SECONDS)).isTrue();
            return new AiChatResult("""
                    {"lines":[{"i":0,"speaker":1}],
                     "newCharacters":[{"id":1,"name":"Speaker %d","instruction":"Voice %d"}]}
                    """.formatted(number, number), "stop", null, null, null);
        });
        Object sentence = loader.loadClass("top.sywyar.pixivdownload.novel.narration.analysis.NarrationSentence")
                .getConstructor(String.class, int.class).newInstance("A speaker talks.", 0);
        var executor = Executors.newFixedThreadPool(4);
        try {
            List<Future<Object>> results = new java.util.ArrayList<>();
            for (int i = 0; i < 4; i++) {
                results.add(executor.submit(() -> call(service, "analyzeChapter", 7L, List.of(sentence), 0, castId)));
            }
            var speakers = new java.util.HashSet<Object>();
            for (var future : results) {
                Object result = future.get(10, TimeUnit.SECONDS);
                var lines = (List<?>) call(call(result, "script"), "lines");
                speakers.add(call(lines.get(0), "speakerName"));
            }
            assertThat(speakers).containsExactlyInAnyOrder("Speaker 1", "Speaker 2", "Speaker 3", "Speaker 4");
            assertThat(jdbc.queryForList("SELECT name FROM novel_narration_voices WHERE cast_id=? AND character_id>0",
                    String.class, castId)).containsExactlyInAnyOrder("Speaker 1", "Speaker 2", "Speaker 3", "Speaker 4");
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private Object narrationCastService(AiChatClient ai) throws Exception {
        String prefix = "top.sywyar.pixivdownload.novel.narration.";
        Object script = loader.loadClass(prefix + "analysis.NarrationScriptService")
                .getConstructor(AiChatClient.class).newInstance(ai);
        var constructor = loader.loadClass(prefix + "NovelNarrationCastService").getConstructors()[0];
        Object[] dependencies = java.util.Arrays.stream(constructor.getParameterTypes()).map(type -> {
            if (type.isInstance(novelMapper)) return novelMapper;
            if (type.isInstance(script)) return script;
            if (type.isInstance(transactions)) return transactions;
            return mock(type);
        }).toArray();
        return transactional(constructor.newInstance(dependencies));
    }

    @Test
    @DisplayName("SQLite 辅助索引真实 SQL 失败只回滚保存点，小说下载与后续字段仍能提交")
    void missingTextIndexDoesNotRollBackDownload() throws Exception {
        initialize("sqlite");
        Object database = novelDatabase(mapper());
        call(database, "init");
        jdbc.execute("DROP TABLE novels_fts");
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            insert(database, 61, 1_700_000_000_061L, null, "索引重建后仍在");
            call(novelMapper, "updateFileBaseName", 61L, "saved-name");
        });
        assertThat(jdbc.queryForObject("SELECT raw_content FROM novels WHERE novel_id=61", String.class))
                .isEqualTo("索引重建后仍在");
        assertThat(jdbc.queryForObject("SELECT file_base_name FROM novels WHERE novel_id=61", String.class))
                .isEqualTo("saved-name");
        call(database, "init");
        assertThat((Set<Long>) call(database, "searchNovelContentIds", "重建后")).containsExactly(61L);
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
