package top.sywyar.pixivdownload.core.db;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import top.sywyar.pixivdownload.author.AuthorMapper;
import top.sywyar.pixivdownload.collection.CollectionInsert;
import top.sywyar.pixivdownload.collection.CollectionMapper;
import top.sywyar.pixivdownload.core.appconfig.DownloadConfig;
import top.sywyar.pixivdownload.core.asset.ArtworkMediaManifest;
import top.sywyar.pixivdownload.core.asset.artwork.ArtworkMediaStoreImpl;
import top.sywyar.pixivdownload.core.db.pathprefix.*;
import top.sywyar.pixivdownload.core.db.schema.DatabaseInitializer;
import top.sywyar.pixivdownload.core.hash.ImageHashMapper;
import top.sywyar.pixivdownload.core.stats.db.StatsQueryStoreImpl;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.plugin.registry.schema.DatabaseSchemaRegistry;
import top.sywyar.pixivdownload.series.MangaSeriesMapper;
import top.sywyar.pixivdownload.setup.guest.persistence.GuestInviteMapper;
import top.sywyar.pixivdownload.setup.guest.persistence.GuestInviteRow;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "PIXIV_TEST_POSTGRES_URL", matches = ".+")
@DisplayName("真实 PostgreSQL 宿主存储行为")
class PostgresHostStorageTest {
    @TempDir Path directory;
    private PostgresTestDatabase database;
    private JdbcTemplate jdbc;
    private DatabaseInitializer initializer;
    private DatabaseSchemaRegistry registry;

    @BeforeEach
    void initialize() throws Exception {
        database = new PostgresTestDatabase();
        jdbc = new JdbcTemplate(database.pool());
        registry = DatabaseSchemaRegistry.forBuiltInPlugins();
        initializer = top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.initializer(jdbc, registry.contributions(), registry.mergedSchema(),
                TestI18nBeans.appMessages(), event -> {});
        initializer.initialize();
    }

    @AfterEach
    void close() throws Exception {
        if (database != null) database.close();
    }

    @Test
    @DisplayName("文件名池、作品、标签、哈希及统计保留生成键、三态布尔和毫秒时间")
    void artworkAndInternedValuesRoundTrip() {
        var mapper = new PixivMapper(top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.main(database.pool()));
        mapper.ensureDefaultFileNameTemplate("fixture-default");
        mapper.insertFileNameTemplateIfAbsent("fixture-custom");
        assertThat(mapper.findFileNameTemplateById(1)).isEqualTo("fixture-default");
        assertThat(mapper.findFileNameTemplateId("fixture-custom")).isGreaterThan(1);
        mapper.ensureDefaultFileNameTemplate("fixture-default");
        mapper.insertFileAuthorNameIfAbsent("作者 🌸");
        long authorName = mapper.findFileAuthorNameId("作者 🌸");
        mapper.insertOrIgnore(5_000_000_001L, "中日文 🌸", "{0}/花", 2, "png", 1_700_000_000_123L,
                null, null, null, null, 1, authorName, null, null);
        mapper.insertOrIgnore(5_000_000_001L, "ignored", "ignored", 1, "jpg", 1_700_000_000_124L,
                2, true, null, null, 1, null, null, null);
        assertThat(mapper.findById(5_000_000_001L)).satisfies(row -> {
            assertThat(row.title()).isEqualTo("中日文 🌸");
            assertThat(row.isAi()).isNull();
            assertThat(row.time()).isEqualTo(1_700_000_000_123L);
        });
        mapper.updateMetadataIfMissing(5_000_000_001L, "kept", 1, true, 5_000_000_002L, "正\0文\r\n\t🌸");
        assertThat(mapper.findById(5_000_000_001L).description()).isEqualTo("正文\r\n\t🌸");
        assertThat(mapper.findById(5_000_000_001L).isAi()).isTrue();
        mapper.refreshMetadataAfterDownload(5_000_000_001L, null, null, false, null, null, 1, null, null, null);
        assertThat(mapper.findById(5_000_000_001L).isAi()).isFalse();
        mapper.upsertTag("花", null);
        mapper.upsertTag("花", "Flower");
        mapper.upsertTag("花", "Ignored");
        long tag = mapper.findTagIdByName("花");
        mapper.insertArtworkTag(5_000_000_001L, tag);
        mapper.insertArtworkTag(5_000_000_001L, tag);
        assertThat(mapper.findTagsByArtworkId(5_000_000_001L)).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT translated_name FROM tags", String.class)).isEqualTo("Flower");
        var hashes = new ImageHashMapper(top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.main(database.pool()));
        hashes.upsert(5_000_000_001L, 0, "png", Long.MIN_VALUE, null, 1_700_000_000_125L);
        hashes.markPageNoHash(5_000_000_001L, 1, 1_700_000_000_126L);
        assertThat(hashes.findAll()).hasSize(1);
        assertThat(hashes.countArtworksMissingHashes()).isZero();
        mapper.initStatistics();
        mapper.recordCompletedDownload(2, "2023-11-14");
        var stats = new StatsQueryStoreImpl(top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.main(database.pool()));
        assertThat(stats.overview().totalImages()).isEqualTo(2);
        assertThat(stats.monthlyArtworkCounts()).hasSize(1);
        mapper.markDeletedById(5_000_000_001L);
        assertThat(mapper.countAll()).isZero();
        assertThat(mapper.countDeletedById(5_000_000_001L)).isOne();
    }

    @Test
    @DisplayName("作者、系列、收藏与邀请实际读写并保持重复插入和条件更新")
    void catalogsCollectionsAndInvites() {
        var authors = new AuthorMapper(top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.main(database.pool()));
        assertThat(authors.insertIfAbsent(42, "作者", 1_700_000_000_123L)).isOne();
        assertThat(authors.insertIfAbsent(42, "ignored", 1_700_000_000_124L)).isZero();
        assertThat(authors.findById(42).name()).isEqualTo("作者");
        var series = new MangaSeriesMapper(DatabaseProviderTestFixtures.main(database.pool()));
        assertThat(series.insertIfAbsent(43, "系列", null, 1_700_000_000_123L)).isOne();
        series.updateInfo(43, "系列二", 42L, 1_700_000_000_124L);
        assertThat(series.findAll()).hasSize(1);
        var collections = new CollectionMapper(DatabaseProviderTestFixtures.main(database.pool()));
        var collection = new CollectionInsert();
        collection.setName("收藏 🌸");
        collection.setCreatedTime(1_700_000_000_125L);
        collections.insert(collection);
        assertThat(collection.getId()).isPositive();
        assertThat(collections.addArtwork(collection.getId(), 44, 1_700_000_000_126L)).isOne();
        assertThat(collections.addArtwork(collection.getId(), 44, 1_700_000_000_127L)).isZero();
        assertThat(collections.findById(collection.getId()).name()).isEqualTo("收藏 🌸");
        var invites = new GuestInviteMapper(DatabaseProviderTestFixtures.main(database.pool()));
        var invite = new GuestInviteRow();
        invite.setCode("fixture-invite");
        invite.setName("来访 🌸");
        invite.setAllowSfw(true);
        invite.setCreatedTime(1_700_000_000_128L);
        invites.insertInvite(invite);
        assertThat(invite.getId()).isPositive();
        assertThat(invites.findById(invite.getId()).isAllowSfw()).isTrue();
        assertThat(invites.findById(invite.getId()).getNovelTagUnrestricted()).isNull();
        invites.updatePaused(invite.getId(), true);
        assertThat(invites.findById(invite.getId()).isPaused()).isTrue();
        invites.upsertAccessStat(invite.getId(), 8);
        invites.upsertAccessStat(invite.getId(), 8);
        assertThat(invites.findAccessStats(invite.getId(), 0).get(0).requestCount()).isEqualTo(2);
        invites.insertInviteTag(invite.getId(), 10);
        assertThat(invites.copyTagsToNovelSide()).isOne();
        assertThat(invites.copyTagsToNovelSide()).isZero();
    }

    @Test
    @DisplayName("路径互换与反斜杠 token 改写在真实事务中一致完成")
    void pathSwapAndRollback() throws Exception {
        var mapper = new PathPrefixMapper(top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.main(database.pool()));
        var codec = new PathPrefixCodec(mapper, new DownloadConfig(), TestI18nBeans.appMessages());
        codec.init();
        Path a = Files.createDirectory(directory.resolve("a"));
        Path b = Files.createDirectory(directory.resolve("b"));
        long first = codec.forceCreatePrefixId(a.toString());
        long second = codec.forceCreatePrefixId(b.toString());
        var transaction = new TransactionTemplate(database.transactionManager());
        var service = new PathPrefixMigrationService(codec, mapper, registry.pathPrefixColumns(),
                new DownloadConfig(), TestI18nBeans.appMessages(), database.transactionManager(), top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.main(database.pool()));
        assertThat(service.apply(List.of(new PathPrefixMigrationService.PathPrefixUpdate(first, b.toString()),
                new PathPrefixMigrationService.PathPrefixUpdate(second, a.toString())), List.of()).success()).isTrue();
        assertThat(mapper.findPathById(first)).isEqualTo(b.toString());
        assertThat(mapper.findPathById(second)).isEqualTo(a.toString());
        jdbc.update("INSERT INTO artworks(artwork_id,title,folder,count,extensions,time) VALUES(1,'fixture',?,1,'jpg',?)",
                "{0}\\花", 1_700_000_000_123L);
        var named = top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.main(database.pool());
        assertThat(registry.pathPrefixColumns().hasSymbolicRootRows(named)).isTrue();
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            PathPrefixColumns.retargetColumn(named, "artworks", "folder", "{0}", "{" + first + "}");
            mapper.updatePath(second, b.toString());
        })).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        assertThat(jdbc.queryForObject("SELECT folder FROM artworks", String.class)).isEqualTo("{0}\\花");
        assertThat(PathPrefixColumns.retargetColumn(named, "artworks", "folder", "{0}", "{" + first + "}")).isOne();
        assertThat(registry.pathPrefixColumns().hasSymbolicRootRows(named)).isFalse();
    }

    @Test
    @DisplayName("画廊搜索、标签聚合及维护统计保留主库查询语义")
    void gallerySearchAndMaintenance() throws Exception {
        jdbc.update("INSERT INTO artworks(artwork_id,title,folder,count,extensions,time,r18)"
                + " VALUES(1,?, '{0}/a',1,'PNG',1700000000123,0)", "Apple C:\\花");
        jdbc.update("INSERT INTO tags(name,translated_name) VALUES('Flower','花')");
        long tag = jdbc.queryForObject("SELECT tag_id FROM tags", Long.class);
        jdbc.update("INSERT INTO artwork_tags(artwork_id,tag_id) VALUES(1,?)", tag);
        jdbc.update("INSERT INTO novels(novel_id,title,folder,count,extensions,time,r18,series_id)"
                + " VALUES(2,'Novel','{0}/novel',1,'txt',1700000000124,0,1)");
        jdbc.update("INSERT INTO novel_tags(novel_id,tag_id) VALUES(2,?)", tag);
        var gallery = new top.sywyar.pixivdownload.core.metadata.artwork.GalleryRepository(top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.main(database.pool()));
        for (String search : List.of("apple", "C:\\花")) {
            var query = top.sywyar.pixivdownload.core.metadata.artwork.GalleryQuery.builder()
                    .page(0).size(10).sort("date").order("desc").searchType("title")
                    .search(search).r18("no").formats(List.of("png")).build();
            assertThat(gallery.findArtworkIds(query).ids()).containsExactly(1L);
        }
        assertThat(gallery.findTagsWithCounts("flower", 10)).hasSize(1);
        assertThat(gallery.findTagByExactName("FLOWER", null)).isNotNull();
        var novels = new top.sywyar.pixivdownload.core.metadata.novel.NovelGalleryRepository(top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.main(database.pool()));
        var restriction = new top.sywyar.pixivdownload.core.metadata.GuestRestriction(
                java.util.Set.of(0), true, List.of(), true, List.of());
        assertThat(novels.findVisibleNovelIds(restriction)).containsExactly(2L);
        assertThat(novels.findVisibleNovelTagCounts(restriction, "flower", 10)).hasSize(1);
        assertThat(novels.findTagByExactName("FLOWER", null)).isNotNull();
        assertThat(novels.findNovelIdsBySeries(1, 0, 10)).containsExactly(2L);
        new top.sywyar.pixivdownload.maintenance.tasks.DatabaseOptimizeTask(database.pool(),
                DatabaseProviderTestFixtures.target(database.pool()))
                .execute(new top.sywyar.pixivdownload.plugin.api.maintenance.MaintenanceContext("manual", 1));
        assertThat(jdbc.queryForObject("""
                SELECT reltuples FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = current_schema() AND c.relname = 'artworks'
                """, Double.class)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("元数据失败回滚保存点，下载事实与后续 SQL 仍能提交")
    void metadataFailureDoesNotAbortDownload() {
        var transactions = database.transactionManager();
        var attributes = new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource();
        var advice = new org.springframework.transaction.interceptor.TransactionInterceptor(transactions, attributes);
        var factory = new org.springframework.aop.framework.ProxyFactory(
                new top.sywyar.pixivdownload.core.metadata.WorkMetadataStore(top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.main(database.pool()), initializer));
        factory.addAdvice(advice);
        var store = (top.sywyar.pixivdownload.core.metadata.WorkMetadataStore) factory.getProxy();
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var capture = new top.sywyar.pixivdownload.core.metadata.sidecar.WorkMetaCaptureService(
                new top.sywyar.pixivdownload.core.metadata.sidecar.WorkMetaCurator(json), store, json, () -> true);
        jdbc.execute("ALTER TABLE artworks ADD CONSTRAINT fixture_metadata CHECK (is_original <> 0)");
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.update("INSERT INTO artworks(artwork_id,title,folder,count,extensions,time)"
                    + " VALUES(91,'download','{0}/file',1,'png',1700000000123)");
            capture.captureForwardedArtwork(91, "{\"illustTitle\":\"原快照\",\"isOriginal\":true}");
            capture.captureForwardedArtwork(91, "{\"illustTitle\":\"被拒快照\",\"isOriginal\":false}");
            jdbc.update("INSERT INTO tags(name) VALUES ('after metadata failure')");
        });
        assertThat(jdbc.queryForObject("SELECT metadata_json FROM artworks", String.class))
                .contains("原快照").doesNotContain("被拒快照");
        assertThat(jdbc.queryForObject("SELECT is_original FROM artworks", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tags", Long.class)).isOne();
    }

    @Test
    @DisplayName("逐页媒体并发 UPSERT 保留各页且失败事务不留半批记录")
    void mediaPagesAndFailedTransaction() throws Exception {
        var media = new ArtworkMediaStoreImpl(top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.main(database.pool()), initializer);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var first = workers.submit(() -> { media.save(99, 0, new ArtworkMediaManifest("png", List.of("png", "webp"), true)); return null; });
            var second = workers.submit(() -> { media.save(99, 1, new ArtworkMediaManifest("jpg", List.of("webp"), false)); return null; });
            first.get();
            second.get();
        } finally {
            workers.shutdownNow();
        }
        assertThat(media.find(99, 0).orElseThrow().originalRetained()).isTrue();
        assertThat(media.find(99, 1).orElseThrow().originalExtension()).isEqualTo("jpg");
        var transaction = new TransactionTemplate(database.transactionManager());
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO tags(name) VALUES ('transaction tag')");
            jdbc.update("INSERT INTO tags(name) VALUES ('transaction tag')");
        })).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tags", Long.class)).isZero();
        jdbc.update("INSERT INTO tags(name) VALUES ('connection reusable')");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tags", Long.class)).isOne();
    }

    @Test
    @DisplayName("文件发布遇到数据库失败时恢复旧文件，重试提交后保留新文件与记录")
    void filePublicationRollsBackAndRetries() throws Exception {
        var config = new DownloadConfig();
        config.setRootFolder(directory.toString());
        var paths = new PathPrefixCodec(new PathPrefixMapper(top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.main(database.pool())), config,
                TestI18nBeans.appMessages());
        paths.init();
        var journal = new top.sywyar.pixivdownload.core.asset.FileOperationJournal(
                top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.main(database.pool()), database.transactionManager(), paths, initializer);
        var files = new top.sywyar.pixivdownload.core.asset.StagedFileDeletion(
                TestI18nBeans.appMessages(), journal);
        Path target = Files.writeString(directory.resolve("正文.txt"), "旧正文");
        Path staged = Files.writeString(directory.resolve("new.txt"), "新正文 🌸");
        String key = top.sywyar.pixivdownload.config.RuntimeFiles.DATA_DIR_PROPERTY;
        String previous = System.getProperty(key);
        System.setProperty(key, directory.resolve("runtime").toString());
        try {
            assertThatThrownBy(() -> files.publishFiles(java.util.Map.of(target, staged), () -> {
                assertThat(journal.operations()).hasSize(1);
                assertThat(journal.committed(journal.operations().get(0))).isFalse();
                jdbc.update("INSERT INTO tags(name) VALUES ('publication')");
                jdbc.update("INSERT INTO tags(name) VALUES ('publication')");
            })).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
            assertThat(Files.readString(target)).isEqualTo("旧正文");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM tags", Long.class)).isZero();
            assertThat(journal.operations()).isEmpty();
            files.publishFiles(java.util.Map.of(target, staged), () ->
                    jdbc.update("INSERT INTO tags(name) VALUES ('publication')"));
            assertThat(Files.readString(target)).isEqualTo("新正文 🌸");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM tags", Long.class)).isOne();
            assertThat(journal.operations()).isEmpty();
            files.recoverPending();
            assertThat(Files.readString(target)).isEqualTo("新正文 🌸");
        } finally {
            if (previous == null) System.clearProperty(key); else System.setProperty(key, previous);
        }
    }
}
