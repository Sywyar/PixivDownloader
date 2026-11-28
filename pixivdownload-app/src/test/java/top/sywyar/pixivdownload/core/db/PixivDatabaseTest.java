package top.sywyar.pixivdownload.core.db;

import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import top.sywyar.pixivdownload.core.db.pathprefix.PathPrefixCodec;
import top.sywyar.pixivdownload.core.db.pathprefix.PathPrefixMapper;
import top.sywyar.pixivdownload.core.db.schema.DatabaseInitializer;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.core.artwork.download.ArtworkDownloadCompletion;
import top.sywyar.pixivdownload.core.artwork.download.PartialArtwork;
import top.sywyar.pixivdownload.core.asset.ArtworkMediaManifest;
import top.sywyar.pixivdownload.core.asset.FileOperationTestSupport;
import top.sywyar.pixivdownload.core.asset.StagedFileDeletion;
import top.sywyar.pixivdownload.core.asset.artwork.ArtworkMediaStoreImpl;
import top.sywyar.pixivdownload.core.download.ArtworkDownloadHistoryAdapter;

import static org.assertj.core.api.Assertions.*;
import top.sywyar.pixivdownload.plugin.registry.schema.DatabaseSchemaRegistry;

@DisplayName("PixivDatabase 集成测试")
class PixivDatabaseTest {
    @org.junit.jupiter.api.AfterEach
    void closeFileOperationTestDatabases() { top.sywyar.pixivdownload.core.asset.FileOperationTestSupport.close(); }


    private PixivDatabase pixivDatabase;
    private SingleConnectionDataSource dataSource;
    private PathPrefixCodec codec;

    @BeforeEach
    void setUp() {
        dataSource = new SingleConnectionDataSource();
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setUrl("jdbc:sqlite::memory:");
        dataSource.setSuppressClose(true);

        PixivMapper mapper = new PixivMapper(top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.main(dataSource));
        PathPrefixMapper pathPrefixMapper = new PathPrefixMapper(top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.main(dataSource));

        // 建表 / 补列 / 索引统一由 DatabaseInitializer 执行（含 deleteArtwork 清理的 artwork_collections）
        top.sywyar.pixivdownload.plugin.registry.schema.DatabaseSchemaRegistry registry =
                top.sywyar.pixivdownload.plugin.registry.schema.DatabaseSchemaRegistry.forBuiltInPlugins();
        DatabaseInitializer initializer = top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.initializer(
                new org.springframework.jdbc.core.JdbcTemplate(dataSource),
                registry.contributions(), registry.mergedSchema(),
                TestI18nBeans.appMessages(), event -> {});
        initializer.initialize();

        // 绝对路径 root → 符号根 {0} 不启用，编码行为与历史一致
        top.sywyar.pixivdownload.core.appconfig.DownloadConfig downloadConfig =
                new top.sywyar.pixivdownload.core.appconfig.DownloadConfig();
        downloadConfig.setRootFolder(
                java.nio.file.Path.of("pixiv-download").toAbsolutePath().normalize().toString() + "/{artwork_id}");
        codec = new PathPrefixCodec(pathPrefixMapper, downloadConfig, TestI18nBeans.appMessages());
        codec.init();

        pixivDatabase = new PixivDatabase(mapper, TestI18nBeans.appMessages(), codec, initializer);
        pixivDatabase.init();
        ProxyFactory proxy = new ProxyFactory(pixivDatabase);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(dataSource),
                new AnnotationTransactionAttributeSource()));
        pixivDatabase = (PixivDatabase) proxy.getProxy();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void moveOfDeletedArtworkPreservesDeletionState(boolean movedBeforeDeletion) throws Exception {
        var moves = new top.sywyar.pixivdownload.core.download.ArtworkMoveService(pixivDatabase);
        insertArtwork(42L, "title", "/source", 1, "jpg", 10L, 0);
        if (movedBeforeDeletion) assertThat(moves.recordMove(42, "/original", 100L, null)).isTrue();
        pixivDatabase.markArtworkDeleted(42);
        var deleted = pixivDatabase.getArtwork(42);
        long moved = pixivDatabase.getStats()[2];
        assertThat(moves.recordMove(42, "/destination", 200L, null)).isFalse();
        assertThat(deleted.deleted()).isTrue();
        assertThat(pixivDatabase.getArtwork(42)).isEqualTo(deleted);
        assertThat(pixivDatabase.getStats()[2]).isEqualTo(moved);
    }

    @Test
    void firstMoveAndStatisticsRollBackTogetherAndRetryCountsOnce() {
        var moves = new top.sywyar.pixivdownload.core.download.ArtworkMoveService(pixivDatabase);
        assertThat(moves.recordMove(42, "/missing", 100L, null)).isFalse();
        insertArtwork(42L, "title", "/source", 1, "jpg", 10L, 0);
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        jdbc.execute("CREATE TRIGGER reject_move BEFORE UPDATE OF total_moved ON statistics "
                + "BEGIN SELECT RAISE(ABORT, 'fixture stats failure'); END");
        assertThatThrownBy(() -> moves.recordMove(42, "/destination", 100L, null)).isInstanceOf(RuntimeException.class);
        assertThat(pixivDatabase.getArtwork(42).moved()).isFalse();
        assertThat(pixivDatabase.getArtwork(42).moveFolder()).isNull();
        assertThat(pixivDatabase.getStats()[2]).isZero();
        jdbc.execute("DROP TRIGGER reject_move");
        assertThat(moves.recordMove(42, "/destination", 100L, null)).isTrue();
        assertThat(moves.recordMove(42, "/next", 200L, null)).isTrue();
        assertThat(pixivDatabase.getArtwork(42).moved()).isTrue();
        assertThat(pixivDatabase.getArtwork(42).moveFolder()).isEqualTo("/next");
        assertThat(pixivDatabase.getStats()[2]).isOne();
    }

    @Test
    void partialPagesKeepTheirLayoutOutsideCompletedHistoryUntilPromotion(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path folder) throws Exception {
        long id = 9123L;
        var layout = new top.sywyar.pixivdownload.core.artwork.download.PartialArtwork(id, folder.toString(), 3,
                "{artwork_id}_p{page}", 180, "webp", "revision", List.of("first", "second", "third"), java.util.Map.of(), java.util.Map.of());
        insertArtwork(id, "old title", folder.resolve("old").toString(), 1, "jpg", 100L, 0);
        pixivDatabase.markArtworkDeleted(id);
        pixivDatabase.replaceArtworkFileNames(id, List.of("old_name"));
        var mediaStore = new top.sywyar.pixivdownload.core.asset.artwork.ArtworkMediaStoreImpl(
                DatabaseProviderTestFixtures.main(dataSource), null);
        var oldMedia = new top.sywyar.pixivdownload.core.asset.ArtworkMediaManifest("png", List.of("png"));
        mediaStore.save(id, 0, oldMedia);
        assertThat(pixivDatabase.startPartialArtwork(layout)).isTrue();
        assertThat(pixivDatabase.getArtwork(id).deleted()).isTrue();
        assertThat(pixivDatabase.getArtwork(id).title()).isEqualTo("old title");
        assertThat(pixivDatabase.getArtworkFileNames(id)).containsExactly("old_name");
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        var selectedMedia = new top.sywyar.pixivdownload.core.asset.ArtworkMediaManifest("jpg", List.of("webp"), false);
        java.nio.file.Files.write(folder.resolve("fixture.webp"), new byte[]{42});
        String stamp = top.sywyar.pixivdownload.core.artwork.download.PartialArtwork.fingerprint(folder.resolve("fixture"), selectedMedia);
        mediaStore.savePartial(id, 2, selectedMedia, stamp);
        assertThat(mediaStore.find(id, 0)).contains(oldMedia);
        assertThat(mediaStore.find(id, 2)).isEmpty();
        var snapshot = pixivDatabase.partialArtwork(id);
        assertThat(snapshot.totalPages()).isEqualTo(3);
        assertThat(snapshot.sourceRevision()).isEqualTo(layout.sourceRevision());
        assertThat(snapshot.availableFiles()).isEmpty();
        java.nio.file.Files.write(folder.resolve("third.webp"), new byte[]{42});
        assertThat(snapshot.availableFiles()).containsExactlyEntriesOf(java.util.Map.of(2, List.of("third.webp")));
        assertThat(snapshot.verifiedFiles()).containsOnlyKeys(2);
        java.nio.file.Files.write(folder.resolve("third.webp"), new byte[]{41});
        assertThat(snapshot.verifiedFiles()).isEmpty();
        java.nio.file.Files.write(folder.resolve("third.webp"), new byte[]{42});
        assertThatThrownBy(() -> pixivDatabase.promotePartialArtwork(id, 3)).isInstanceOf(IllegalStateException.class);
        assertThat(mediaStore.find(id, 0)).contains(oldMedia);
        mediaStore.savePartial(id, 0, selectedMedia, stamp);
        mediaStore.savePartial(id, 1, selectedMedia, stamp);
        var completion = new top.sywyar.pixivdownload.core.artwork.download.ArtworkDownloadCompletion(id, "fixture", folder,
                3, java.util.Set.of("webp"), 1234L, 0, false, null, null, layout.template(), null, null, null,
                List.of(), 180, layout.baseNames());
        new top.sywyar.pixivdownload.core.download.ArtworkDownloadHistoryAdapter(pixivDatabase).completePartial(completion);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM artwork_media WHERE artwork_id=?", Integer.class, id)).isEqualTo(3);
        assertThat(pixivDatabase.partialArtwork(id)).isNull();
        assertThat(pixivDatabase.startPartialArtwork(layout)).isFalse();
        assertThat(pixivDatabase.getArtworkFileNames(id)).containsExactly("first", "second", "third");
    }

    @Test
    void recoveredPageFactsAreAtomicAndPreserveEarlierMedia(@org.junit.jupiter.api.io.TempDir Path root) throws Exception {
        long id = 8941;
        var media = new ArtworkMediaStoreImpl(DatabaseProviderTestFixtures.main(dataSource), null);
        var trusted = new top.sywyar.pixivdownload.core.asset.ArtworkMediaManifest("webp", List.of("jpg"), false, true);
        media.save(id, 0, trusted);
        var input = InsertArtworkArgument.builder().artworkId(id).title("recovery").folder(root.toString())
                .count(2).time(pixivDatabase.getUniqueTime()).extensions("jpg,png").build();
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        jdbc.execute("CREATE TRIGGER reject_page BEFORE INSERT ON artwork_media WHEN NEW.page=1 BEGIN SELECT RAISE(ABORT,'injected'); END");
        assertThatThrownBy(() -> pixivDatabase.registerRecoveredArtwork(input, List.of("jpg", "png"))).isInstanceOf(RuntimeException.class);
        assertThat(pixivDatabase.getArtwork(id)).isNull();
        assertThat(pixivDatabase.getArtworkFileNames(id)).isEmpty();
        assertThat(media.find(id, 0)).contains(trusted);
        assertThat(media.find(id, 1)).isEmpty();
        jdbc.execute("DROP TRIGGER reject_page");
        assertThat(pixivDatabase.registerRecoveredArtwork(input, List.of("png", "jpg"))).isFalse();
        assertThat(pixivDatabase.getArtwork(id)).isNull();
        assertThat(pixivDatabase.registerRecoveredArtwork(input, List.of("jpg", "png"))).isTrue();
        assertThat(pixivDatabase.registerRecoveredArtwork(input, List.of("jpg", "png"))).isFalse();
        assertThat(media.find(id, 0)).contains(trusted);
        assertThat(media.find(id, 1)).contains(new top.sywyar.pixivdownload.core.asset.ArtworkMediaManifest(
                top.sywyar.pixivdownload.core.asset.ArtworkMediaManifest.UNKNOWN_ORIGINAL, List.of("png"), false, false));
        assertThat(pixivDatabase.getArtworkFileNames(id)).containsExactly(id + "_p0", id + "_p1");
    }

    @Test
    void selectionOfRegisteredArtworkCannotClaimANewDirectory(@org.junit.jupiter.api.io.TempDir Path root)
            throws Exception {
        long id = 9124L;
        Path current = Files.createDirectory(root.resolve("current"));
        Path other = root.resolve("different");
        insertArtwork(id, "saved", current.toString(), 1, "jpg", 100L, 0);
        var layout = new PartialArtwork(id, other.toString(), 1, "{artwork_id}_p{page}", 180,
                "original", "revision", List.of("page"), Map.of(), Map.of());
        var directories = new top.sywyar.pixivdownload.core.asset.DownloadDirectories(
                DatabaseProviderTestFixtures.main(dataSource), codec, null);
        var type = top.sywyar.pixivdownload.core.work.model.WorkType.ARTWORK;
        directories.prepare(type, id, current);
        assertThat(directories.prepareIf(type, id, other, () -> pixivDatabase.startPartialArtwork(layout))).isFalse();
        assertThat(other).doesNotExist();
        assertThat(directories.owns(type, id, current)).isTrue();
        assertThat(directories.owns(type, id, other)).isFalse();
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM download_directories", Integer.class)).isOne();
        assertThat(pixivDatabase.partialArtwork(id)).isNull();

        pixivDatabase.markArtworkDeleted(id);
        Files.createDirectory(other);
        Files.writeString(other.resolve("unrelated"), "keep");
        assertThatThrownBy(() -> directories.prepareIf(type, id, other, () -> pixivDatabase.startPartialArtwork(layout)))
                .isInstanceOf(top.sywyar.pixivdownload.core.work.directory.DirectoryTemplateException.class);
        assertThat(pixivDatabase.partialArtwork(id)).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM download_directories", Integer.class)).isOne();
    }

    @Test
    void replacementCommitFailureRestoresRegisteredMediaAndEveryPage(@org.junit.jupiter.api.io.TempDir Path root)
            throws Exception {
        long id = 9178L;
        Path first = Files.write(root.resolve("first.jpg"), new byte[]{1});
        Path second = Files.write(root.resolve("second.jpg"), new byte[]{1});
        Path newPage = Files.write(root.resolve("staged"), new byte[]{2});
        insertArtwork(id, "prior", root.toString(), 2, "jpg", 100L, 0);
        var history = new ArtworkDownloadHistoryAdapter(pixivDatabase);
        var old = pixivDatabase.getArtwork(id);
        var media = new ArtworkMediaStoreImpl(DatabaseProviderTestFixtures.main(dataSource), null);
        var jpg = new ArtworkMediaManifest("jpg", List.of("jpg"));
        var png = new ArtworkMediaManifest("png", List.of("png"));
        media.save(id, 0, jpg);
        media.save(id, 1, jpg);
        var files = new StagedFileDeletion(TestI18nBeans.appMessages(), FileOperationTestSupport.journal(dataSource));
        var completion = new ArtworkDownloadCompletion(id, "replacement", root, 1, Set.of("png"),
                1234L, 0, false, null, null, "{artwork_id}_p{page}", null, null, null,
                List.of(), 180, List.of("first"));
        Path newFile = root.resolve("first.png");
        Runnable commit = () -> {
            try { media.save(id, 0, png); } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
            history.record(completion);
        };
        String previous = System.setProperty(RuntimeFiles.DATA_DIR_PROPERTY, root.resolve("runtime").toString());
        try {
            assertThatThrownBy(() -> files.changeFiles(Map.of(newFile, newPage), List.of(first, second), () -> {
                commit.run();
                throw new IllegalStateException("commit failed");
            })).isInstanceOf(IllegalStateException.class).hasMessage("commit failed");
            assertThat(Files.readAllBytes(first)).containsExactly(1);
            assertThat(Files.readAllBytes(second)).containsExactly(1);
            assertThat(newFile).doesNotExist();
            assertThat(pixivDatabase.getArtwork(id)).isEqualTo(old);
            assertThat(media.find(id, 0)).contains(jpg);
            assertThat(media.find(id, 1)).contains(jpg);
            files.changeFiles(Map.of(newFile, newPage), List.of(first, second), commit);
            assertThat(Files.readAllBytes(newFile)).containsExactly(2);
            assertThat(first).doesNotExist();
            assertThat(second).doesNotExist();
            assertThat(media.find(id, 0)).contains(png);
            assertThat(media.find(id, 1)).isEmpty();
            assertThat(pixivDatabase.getArtwork(id).count()).isOne();
        } finally {
            if (previous == null) System.clearProperty(RuntimeFiles.DATA_DIR_PROPERTY);
            else System.setProperty(RuntimeFiles.DATA_DIR_PROPERTY, previous);
        }
    }

    @Test
    void failedRebuildCommitRestoresPartialRecordAndOriginalBytes(
            @org.junit.jupiter.api.io.TempDir Path directory) throws Exception {
        long id = 9134L;
        Path old = Files.writeString(directory.resolve("first.jpg"), "old page");
        Path staged = Files.writeString(directory.resolve("replacement"), "new page");
        var layout = new PartialArtwork(id, directory.toString(), 2, "{artwork_id}_p{page}", 180, "original",
                "old revision", List.of("first", "second"), Map.of(), Map.of());
        var manifest = new ArtworkMediaManifest("jpg", List.of("jpg"));
        var media = new ArtworkMediaStoreImpl(DatabaseProviderTestFixtures.main(dataSource), null);
        assertThat(pixivDatabase.startPartialArtwork(layout)).isTrue();
        media.savePartial(id, 0, manifest, PartialArtwork.fingerprint(directory.resolve("first"), manifest));
        var completion = new ArtworkDownloadCompletion(id, "new title", directory, 1, Set.of("jpg"),
                1234L, 0, false, null, null, layout.template(), null, null, null, List.of(), 180, List.of("first"));
        var journal = FileOperationTestSupport.journal(dataSource);
        var files = new StagedFileDeletion(TestI18nBeans.appMessages(), journal);
        var history = new ArtworkDownloadHistoryAdapter(pixivDatabase);
        String previous = System.setProperty(RuntimeFiles.DATA_DIR_PROPERTY, directory.resolve("runtime").toString());
        try {
            assertThatThrownBy(() -> files.changeFiles(Map.of(old, staged), List.of(), () -> {
                history.record(completion);
                throw new IllegalStateException("commit failed");
            })).isInstanceOf(IllegalStateException.class).hasMessage("commit failed");
            assertThat(pixivDatabase.getArtwork(id)).isNull();
            assertThat(Files.readString(old)).isEqualTo("old page");
            assertThat(pixivDatabase.partialArtwork(id).verifiedFiles()).containsOnlyKeys(0);
            files.changeFiles(Map.of(old, staged), List.of(), () -> history.record(completion));
            assertThat(Files.readString(old)).isEqualTo("new page");
            assertThat(pixivDatabase.getArtwork(id).count()).isEqualTo(1);
            assertThat(pixivDatabase.partialArtwork(id)).isNull();
        } finally {
            if (previous == null) System.clearProperty(RuntimeFiles.DATA_DIR_PROPERTY);
            else System.setProperty(RuntimeFiles.DATA_DIR_PROPERTY, previous);
        }
    }

    @AfterEach
    void tearDown() {
        dataSource.destroy();
    }

    @Test
    @DisplayName("下载与导入保留登记时间，重新下载更新该时间")
    void preservesRecordTimeForImportsAndDownloads() {
        var imported = InsertArtworkArgument.builder().artworkId(123L).title("fixture").folder("/fixture")
                .count(1).extensions("jpg").time(1234L).fileName(1L).build();
        pixivDatabase.insertArtwork(imported);
        assertThat(pixivDatabase.getArtwork(123L).time()).isEqualTo(1234L);
        pixivDatabase.recordDownloadedArtwork(imported);
        var record = pixivDatabase.getArtwork(123L);
        assertThat(record.time()).isEqualTo(1234L);
        pixivDatabase.recordDownloadedArtwork(InsertArtworkArgument.builder().artworkId(123L).title("fixture")
                .folder("/fixture").count(1).extensions("jpg").time(5678L).fileName(1L).build());
        assertThat(pixivDatabase.getArtwork(123L).time()).isEqualTo(5678L);
    }

    @Test
    @DisplayName("真实 SQLite 软删除事务失败时同时恢复文件、命名事实与作品记录")
    void deletionRollsBackFilesAndDatabase(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        String property = top.sywyar.pixivdownload.config.RuntimeFiles.DATA_DIR_PROPERTY;
        String previous = System.getProperty(property);
        System.setProperty(property, directory.resolve("data").toString());
        try {
            java.nio.file.Path image = directory.resolve("image.png");
            java.nio.file.Files.writeString(image, "original bytes", java.nio.charset.StandardCharsets.UTF_8);
            insertArtwork(12345L, "fixture", directory.toString(), 1, "png", 100L, 0);
            pixivDatabase.replaceArtworkFileNames(12345L, List.of("image"));
            var jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
            jdbc.execute("CREATE TRIGGER fail_delete BEFORE UPDATE OF deleted ON artworks "
                    + "BEGIN SELECT RAISE(ABORT, 'injected transaction failure'); END");
            var deletion = new top.sywyar.pixivdownload.core.asset.StagedFileDeletion(TestI18nBeans.appMessages(), top.sywyar.pixivdownload.core.asset.FileOperationTestSupport.journal(dataSource));
            assertThatThrownBy(() -> deletion.deleteAtomically(List.of(image),
                    () -> pixivDatabase.markArtworkDeleted(12345L))).isInstanceOf(RuntimeException.class);
            assertThat(java.nio.file.Files.readString(image)).isEqualTo("original bytes");
            assertThat(pixivDatabase.hasActiveArtwork(12345L)).isTrue();
            assertThat(pixivDatabase.getArtworkFileNames(12345L)).containsExactly("image");
        } finally {
            if (previous == null) System.clearProperty(property); else System.setProperty(property, previous);
        }
    }

    @Test
    void recoveringOnlyAuthorKeepsUntitledImportedRecordAndPreservesLaterValues() {
        pixivDatabase.insertArtwork(InsertArtworkArgument.builder().artworkId(42L).title("").folder("/import")
                .count(1).extensions("jpg").time(1L).fileName(1L).build());
        pixivDatabase.fillArtworkMetadataIfMissing(42L, null, null, null, 7L, null);
        assertThat(pixivDatabase.getArtwork(42L).title()).isEmpty();
        pixivDatabase.fillArtworkMetadataIfMissing(42L, "first", null, null, 8L, null);
        pixivDatabase.fillArtworkMetadataIfMissing(42L, "second", null, null, null, "description");
        var work = pixivDatabase.getArtwork(42L);
        assertThat(work.title()).isEqualTo("first");
        assertThat(work.authorId()).isEqualTo(7L);
        assertThat(work.description()).isEqualTo("description");
        pixivDatabase.markArtworkDeleted(42L);
        pixivDatabase.fillArtworkMetadataIfMissing(42L, null, null, true, null, null);
        assertThat(pixivDatabase.getArtwork(42L).isAi()).isNull();
    }

    // ========== insertArtwork & getArtwork ==========

    @Nested
    @DisplayName("insertArtwork & getArtwork")
    class InsertAndGetTests {

        @Test
        @DisplayName("插入作品后应能查询到")
        void shouldInsertAndRetrieveArtwork() {
            insertArtwork(12345L, "测试作品", "/path/to/12345", 3, "jpg", 1700000001L, 0);

            ArtworkRecord record = pixivDatabase.getArtwork(12345L);

            assertThat(record).isNotNull();
            assertThat(record.artworkId()).isEqualTo(12345L);
            assertThat(record.title()).isEqualTo("测试作品");
            assertThat(record.folder()).isEqualTo("/path/to/12345");
            assertThat(record.count()).isEqualTo(3);
            assertThat(record.extensions()).isEqualTo("jpg");
            assertThat(record.time()).isEqualTo(1700000001L);
            assertThat(record.xRestrict()).isEqualTo(0);
            assertThat(record.isAi()).isNull();
            assertThat(record.authorId()).isNull();
            assertThat(record.fileName()).isEqualTo(PixivDatabase.DEFAULT_FILE_NAME_TEMPLATE_ID);
            assertThat(record.moved()).isFalse();
        }

        @Test
        @DisplayName("缺少必填字段时应拒绝构建作品写入参数")
        void shouldRejectMissingRequiredInsertArgumentField() {
            assertThatThrownBy(() -> InsertArtworkArgument.builder()
                    .title("test")
                    .folder("/path")
                    .count(1)
                    .extensions("jpg")
                    .time(1L)
                    .build())
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("artworkId");
        }

        @Test
        @DisplayName("插入作品时应写入 authorId")
        void shouldInsertArtworkWithAuthorId() {
            pixivDatabase.insertArtwork(artwork(12346L, "author test", "/path/to/12346",
                    1, "png", 1700000007L, 0).authorId(777L).build());

            ArtworkRecord record = pixivDatabase.getArtwork(12346L);

            assertThat(record).isNotNull();
            assertThat(record.authorId()).isEqualTo(777L);
        }

        @Test
        @DisplayName("插入作品时应写入 isAi")
        void shouldInsertArtworkWithIsAi() {
            pixivDatabase.insertArtwork(artwork(12347L, "ai test", "/path/to/12347",
                    1, "png", 1700000008L, 0)
                    .isAi(true)
                    .authorId(888L)
                    .description("desc")
                    .build());

            ArtworkRecord record = pixivDatabase.getArtwork(12347L);

            assertThat(record).isNotNull();
            assertThat(record.isAi()).isTrue();
            assertThat(record.authorId()).isEqualTo(888L);
            assertThat(record.description()).isEqualTo("desc");
            assertThat(record.fileName()).isEqualTo(PixivDatabase.DEFAULT_FILE_NAME_TEMPLATE_ID);
        }

        @Test
        @DisplayName("插入作品时应写入文件名模板 id")
        void shouldInsertArtworkWithFileNameTemplateId() {
            long templateId = pixivDatabase.getOrCreateFileNameTemplateId("{artwork_title}_p{page}");

            pixivDatabase.insertArtwork(artwork(12348L, "file name test", "/path/to/12348",
                    1, "png", 1700000012L, 0)
                    .isAi(false)
                    .authorId(888L)
                    .description("desc")
                    .fileName(templateId)
                    .build());

            ArtworkRecord record = pixivDatabase.getArtwork(12348L);

            assertThat(record).isNotNull();
            assertThat(record.fileName()).isEqualTo(templateId);
            assertThat(pixivDatabase.getFileNameTemplate(templateId)).isEqualTo("{artwork_title}_p{page}");
            assertThat(pixivDatabase.getOrCreateFileNameTemplateId("{artwork_title}_p{page}")).isEqualTo(templateId);
        }

        @Test
        @DisplayName("插入 R18 作品")
        void shouldInsertR18Artwork() {
            insertArtwork(11111L, "R18作品", "/path/r18", 1, "png", 1700000002L, 1);

            ArtworkRecord record = pixivDatabase.getArtwork(11111L);

            assertThat(record).isNotNull();
            assertThat(record.xRestrict()).isEqualTo(1);
        }

        @Test
        @DisplayName("插入 R18G 作品")
        void shouldInsertR18GArtwork() {
            insertArtwork(11112L, "R18G作品", "/path/r18g", 1, "png", 1700000009L, 2);

            ArtworkRecord record = pixivDatabase.getArtwork(11112L);

            assertThat(record).isNotNull();
            assertThat(record.xRestrict()).isEqualTo(2);
        }

        @Test
        @DisplayName("xRestrict 为 null 的作品")
        void shouldHandleNullXRestrict() {
            insertArtwork(22222L, "普通作品", "/path/normal", 1, "jpg", 1700000003L, null);

            ArtworkRecord record = pixivDatabase.getArtwork(22222L);

            assertThat(record).isNotNull();
            assertThat(record.xRestrict()).isNull();
        }

        @Test
        @DisplayName("INSERT OR IGNORE 重复插入不应覆盖")
        void shouldIgnoreDuplicateInsert() {
            insertArtwork(12345L, "原始标题", "/path/1", 1, "jpg", 1700000004L, 0);
            insertArtwork(12345L, "新标题", "/path/2", 2, "png", 1700000005L, 1);

            ArtworkRecord record = pixivDatabase.getArtwork(12345L);
            assertThat(record.title()).isEqualTo("原始标题");
            assertThat(record.folder()).isEqualTo("/path/1");
        }

        @Test
        @DisplayName("成功重下更新目录页数格式时间并清除旧移动位置")
        void redownloadReplacesStoredFileFacts() {
            insertArtwork(42L, "old", "/old/42", 3, "jpg", 100L, 0);
            pixivDatabase.updateArtworkMove(42L, "/moved/42", 200L);
            new top.sywyar.pixivdownload.core.download.ArtworkDownloadHistoryAdapter(pixivDatabase).record(
                    new top.sywyar.pixivdownload.core.artwork.download.ArtworkDownloadCompletion(
                            42L, "new", java.nio.file.Path.of("new", "42"), 2, java.util.Set.of("png"), 300L,
                            0, false, null, null, "{artwork_id}_p{page}", null, null, null, List.of()));
            ArtworkRecord saved = pixivDatabase.getArtwork(42L);
            assertThat(saved.folder()).isEqualTo(java.nio.file.Path.of("new", "42").toAbsolutePath().toString());
            assertThat(saved.count()).isEqualTo(2);
            assertThat(saved.extensions()).isEqualTo("png");
            assertThat(saved.time()).isEqualTo(300L);
            assertThat(saved.moved()).isFalse();
            assertThat(saved.moveFolder()).isNull();
            assertThat(saved.moveTime()).isNull();
        }

        @Test
        @DisplayName("下载基名快照不随标题和分级回填变化且软硬删除均清理快照")
        void fileNamesSurviveMetadataChangesAndFollowDeletion() {
            var history = new top.sywyar.pixivdownload.core.download.ArtworkDownloadHistoryAdapter(pixivDatabase);
            var completion = new top.sywyar.pixivdownload.core.artwork.download.ArtworkDownloadCompletion(
                    43L, "Original title", java.nio.file.Path.of("43"), 1, java.util.Set.of("jpg"), 400L,
                    0, false, null, null, "{artwork_title}_p{page}", null, null, null, List.of());
            history.record(completion);
            pixivDatabase.refreshArtworkMetadataAfterDownload(43L, "Updated title", 2, true,
                    null, null, pixivDatabase.getArtwork(43L).fileName(), null, null, null);
            var locator = new top.sywyar.pixivdownload.core.asset.artwork.ArtworkFileLocator(
                    pixivDatabase, new top.sywyar.pixivdownload.core.appconfig.DownloadConfig(), TestI18nBeans.appMessages(),
                    new top.sywyar.pixivdownload.core.asset.StagedFileDeletion(TestI18nBeans.appMessages(), top.sywyar.pixivdownload.core.asset.FileOperationTestSupport.journal(dataSource)),
                    org.mockito.Mockito.mock(top.sywyar.pixivdownload.core.asset.ArtworkMediaStore.class),
                    org.mockito.Mockito.mock(top.sywyar.pixivdownload.core.asset.ExternalWorkFiles.class), null);
            assertThat(locator.resolveStoredFileBaseName(pixivDatabase.getArtwork(43L), 0)).isEqualTo("Original title_p0");
            pixivDatabase.markArtworkDeleted(43L);
            assertThat(pixivDatabase.getArtworkFileNames(43L)).isEmpty();
            history.record(completion);
            assertThat(pixivDatabase.getArtworkFileNames(43L)).containsExactly("Original title_p0");
            pixivDatabase.deleteArtwork(43L);
            assertThat(pixivDatabase.getArtworkFileNames(43L)).isEmpty();
        }

        @Test
        @DisplayName("下载成功刷新有效元数据并整体替换非空标签，空值保留旧数据")
        void shouldRefreshDownloadedArtworkMetadataWithoutReplacingWithEmptyValues() {
            long fileNameId = pixivDatabase.getOrCreateFileNameTemplateId("{artwork_title}_p{page}");
            long fileAuthorNameId = pixivDatabase.getOrCreateFileAuthorNameId("新作者");
            pixivDatabase.insertArtwork(artwork(12345L, "旧标题", "/path/1", 1, "jpg", 1700000004L, 0)
                    .isAi(false)
                    .authorId(10L)
                    .description("旧简介")
                    .fileName(1L)
                    .seriesId(7L)
                    .seriesOrder(1L)
                    .build());
            pixivDatabase.saveArtworkTags(12345L, List.of(new TagDto(null, "旧标签", null)));

            pixivDatabase.refreshArtworkMetadataAfterDownload(12345L, "新标题", 2, true,
                    20L, "新简介", fileNameId, fileAuthorNameId, 8L, 2L);
            pixivDatabase.replaceArtworkTagsAfterDownload(12345L,
                    List.of(new TagDto(null, "新标签", "new-tag")));
            pixivDatabase.refreshArtworkMetadataAfterDownload(12345L, null, null, null,
                    null, null, fileNameId, null, null, null);
            pixivDatabase.replaceArtworkTagsAfterDownload(12345L, List.of());

            ArtworkRecord record = pixivDatabase.getArtwork(12345L);
            assertThat(record.title()).isEqualTo("新标题");
            assertThat(record.xRestrict()).isEqualTo(2);
            assertThat(record.isAi()).isTrue();
            assertThat(record.authorId()).isEqualTo(20L);
            assertThat(record.description()).isEqualTo("新简介");
            assertThat(record.fileName()).isEqualTo(fileNameId);
            assertThat(record.fileAuthorNameId()).isEqualTo(fileAuthorNameId);
            assertThat(record.seriesId()).isEqualTo(8L);
            assertThat(record.seriesOrder()).isEqualTo(2L);
            assertThat(pixivDatabase.getArtworkTags(12345L))
                    .extracting(TagDto::getName)
                    .containsExactly("新标签");
        }

        @Test
        @DisplayName("查询不存在的作品应返回 null")
        void shouldReturnNullForNonExistentArtwork() {
            assertThat(pixivDatabase.getArtwork(99999L)).isNull();
        }

        @Test
        @DisplayName("文件夹路径末尾斜杠应被去除")
        void shouldStripTrailingSlash() {
            insertArtwork(33333L, "test", "/path/to/folder/", 1, "jpg", 1700000006L, 0);

            ArtworkRecord record = pixivDatabase.getArtwork(33333L);
            assertThat(record.folder()).isEqualTo("/path/to/folder");
        }
    }

    // ========== hasArtwork ==========

    @Test
    @DisplayName("hasArtwork 应正确判断作品是否存在")
    void shouldCheckArtworkExistence() {
        assertThat(pixivDatabase.hasArtwork(12345L)).isFalse();

        insertArtwork(12345L, "test", "/path", 1, "jpg", 1700000010L, 0);

        assertThat(pixivDatabase.hasArtwork(12345L)).isTrue();
    }

    // ========== deleteArtwork ==========

    @Test
    @DisplayName("删除作品后应查询不到")
    void shouldDeleteArtwork() {
        insertArtwork(12345L, "test", "/path", 1, "jpg", 1700000011L, 0);
        assertThat(pixivDatabase.hasArtwork(12345L)).isTrue();

        pixivDatabase.deleteArtwork(12345L);
        assertThat(pixivDatabase.hasArtwork(12345L)).isFalse();
    }

    @Test
    @DisplayName("删除作品应一并清理标签关联与收藏夹关联")
    void shouldDeleteArtworkSatelliteRows() throws Exception {
        insertArtwork(12345L, "test", "/path", 1, "jpg", 1700000011L, 0);
        pixivDatabase.saveArtworkTags(12345L, List.of(new TagDto(null, "tag-a", null)));
        try (var conn = dataSource.getConnection(); var st = conn.createStatement()) {
            st.execute("INSERT INTO artwork_collections(collection_id, artwork_id, added_time) VALUES (1, 12345, 0)");
        }

        pixivDatabase.deleteArtwork(12345L);

        assertThat(pixivDatabase.getArtworkTags(12345L)).isEmpty();
        try (var conn = dataSource.getConnection(); var st = conn.createStatement();
             var rs = st.executeQuery("SELECT COUNT(*) FROM artwork_collections WHERE artwork_id = 12345")) {
            rs.next();
            assertThat(rs.getInt(1)).isZero();
        }
    }

    // ========== markArtworkDeleted（软删除） ==========

    @Test
    @DisplayName("软删除标记后主行保留：hasArtwork 仍命中、hasActiveArtwork 不命中、记录带 deleted 标志")
    void shouldMarkArtworkDeletedKeepingRow() {
        insertArtwork(12345L, "test", "/path", 1, "jpg", 1700000011L, 0);
        assertThat(pixivDatabase.hasActiveArtwork(12345L)).isTrue();
        assertThat(pixivDatabase.isArtworkDeleted(12345L)).isFalse();

        pixivDatabase.markArtworkDeleted(12345L);

        assertThat(pixivDatabase.hasArtwork(12345L)).isTrue();
        assertThat(pixivDatabase.hasActiveArtwork(12345L)).isFalse();
        assertThat(pixivDatabase.isArtworkDeleted(12345L)).isTrue();
        ArtworkRecord record = pixivDatabase.getArtwork(12345L);
        assertThat(record).isNotNull();
        assertThat(record.deleted()).isTrue();
    }

    @Test
    @DisplayName("软删除标记应照旧清理标签关联与收藏夹关联")
    void shouldMarkArtworkDeletedAndCleanSatelliteRows() throws Exception {
        insertArtwork(12345L, "test", "/path", 1, "jpg", 1700000011L, 0);
        pixivDatabase.saveArtworkTags(12345L, List.of(new TagDto(null, "tag-a", null)));
        try (var conn = dataSource.getConnection(); var st = conn.createStatement()) {
            st.execute("INSERT INTO artwork_collections(collection_id, artwork_id, added_time) VALUES (1, 12345, 0)");
        }

        pixivDatabase.markArtworkDeleted(12345L);

        assertThat(pixivDatabase.getArtworkTags(12345L)).isEmpty();
        try (var conn = dataSource.getConnection(); var st = conn.createStatement();
             var rs = st.executeQuery("SELECT COUNT(*) FROM artwork_collections WHERE artwork_id = 12345")) {
            rs.next();
            assertThat(rs.getInt(1)).isZero();
        }
    }

    @Test
    @DisplayName("软删除的作品被重新下载落库后删除标记复位，记录被全新行替换")
    void shouldReviveDeletedArtworkOnReinsert() {
        insertArtwork(12345L, "old", "/old", 1, "jpg", 1700000011L, 0);
        pixivDatabase.markArtworkDeleted(12345L);

        insertArtwork(12345L, "new", "/new", 2, "png", 1700000012L, 1);

        ArtworkRecord record = pixivDatabase.getArtwork(12345L);
        assertThat(record).isNotNull();
        assertThat(record.deleted()).isFalse();
        assertThat(record.title()).isEqualTo("new");
        assertThat(record.folder()).isEqualTo("/new");
        assertThat(pixivDatabase.hasActiveArtwork(12345L)).isTrue();
    }

    @Test
    @DisplayName("未被软删除的已有记录重新插入时保持原行不被覆盖（INSERT OR IGNORE 语义不变）")
    void shouldKeepActiveRowOnReinsert() {
        insertArtwork(12345L, "old", "/old", 1, "jpg", 1700000011L, 0);

        insertArtwork(12345L, "new", "/new", 2, "png", 1700000012L, 1);

        ArtworkRecord record = pixivDatabase.getArtwork(12345L);
        assertThat(record).isNotNull();
        assertThat(record.title()).isEqualTo("old");
        assertThat(record.folder()).isEqualTo("/old");
    }

    @Test
    @DisplayName("软删除的作品不再出现在历史 ID 列表中")
    void shouldExcludeDeletedFromIdLists() {
        insertArtwork(1L, "a", "/a", 1, "jpg", 1700000020L, 0);
        insertArtwork(2L, "b", "/b", 1, "jpg", 1700000021L, 0);
        pixivDatabase.markArtworkDeleted(2L);

        assertThat(pixivDatabase.getAllArtworkIds()).containsExactly(1L);
        assertThat(pixivDatabase.countArtworks()).isEqualTo(1);
    }

    // ========== getAllArtworkIds ==========

    @Test
    @DisplayName("获取所有作品ID")
    void shouldReturnAllArtworkIds() {
        insertArtwork(1L, "a", "/a", 1, "jpg", 1700000020L, 0);
        insertArtwork(2L, "b", "/b", 1, "jpg", 1700000021L, 0);
        insertArtwork(3L, "c", "/c", 1, "jpg", 1700000022L, 0);

        List<Long> ids = pixivDatabase.getAllArtworkIds();
        assertThat(ids).containsExactlyInAnyOrder(1L, 2L, 3L);
    }

    // ========== getArtworkIdsSortedByTimeDesc ==========

    @Test
    @DisplayName("按时间倒序排列")
    void shouldReturnIdsSortedByTimeDesc() {
        insertArtwork(1L, "a", "/a", 1, "jpg", 1700000030L, 0);
        insertArtwork(2L, "b", "/b", 1, "jpg", 1700000032L, 0);
        insertArtwork(3L, "c", "/c", 1, "jpg", 1700000031L, 0);

        List<Long> ids = pixivDatabase.getArtworkIdsSortedByTimeDesc();
        assertThat(ids).containsExactly(2L, 3L, 1L);
    }

    // ========== getArtworkIdsSortedByTimeDescPaged ==========

    @Test
    @DisplayName("分页查询按时间倒序")
    void shouldReturnPagedResults() {
        for (int i = 1; i <= 20; i++) {
            insertArtwork((long) i, "art" + i, "/path/" + i, 1, "jpg", 1700000040L + i, 0);
        }

        List<Long> page0 = pixivDatabase.getArtworkIdsSortedByTimeDescPaged(0, 5);
        assertThat(page0).hasSize(5);
        assertThat(page0.get(0)).isEqualTo(20L);

        List<Long> page1 = pixivDatabase.getArtworkIdsSortedByTimeDescPaged(5, 5);
        assertThat(page1).hasSize(5);
        assertThat(page1.get(0)).isEqualTo(15L);
    }

    @Test
    @DisplayName("下载历史的两种排序都接受超出整数偏移的页码")
    void shouldReturnEmptyHistoryBeyondIntegerOffset() {
        insertArtwork(1L, "fixture", "/fixture", 1, "jpg", 100L, 0);
        var history = new top.sywyar.pixivdownload.core.download.DownloadedArtworkService(pixivDatabase, null);
        for (int page : List.of(1_073_741_824, Integer.MAX_VALUE)) {
            assertThat(history.getSortTimeArtworkPaged(page, 20)).isEmpty();
            assertThat(history.getSortAuthorArtworkPaged(page, 20)).isEmpty();
        }
        assertThat(history.getSortTimeArtworkPaged(0, 20)).containsExactly(1L);
        assertThat(history.getSortAuthorArtworkPaged(0, 20)).containsExactly(1L);
    }

    @Test
    @DisplayName("按作者排序分页时应将 null authorId 排在最后")
    void shouldReturnPagedResultsSortedByAuthorId() {
        pixivDatabase.insertArtwork(artwork(1L, "a", "/a", 1, "jpg", 100L, 0).authorId(20L).build());
        pixivDatabase.insertArtwork(artwork(2L, "b", "/b", 1, "jpg", 200L, 0).build());
        pixivDatabase.insertArtwork(artwork(3L, "c", "/c", 1, "jpg", 150L, 0).authorId(10L).build());

        List<Long> ids = pixivDatabase.getArtworkIdsSortedByAuthorIdAscPaged(0, 10);

        assertThat(ids).containsExactly(3L, 1L, 2L);
    }

    @Test
    @DisplayName("应更新作品 authorId 并查询缺失 authorId 的记录")
    void shouldUpdateAndQueryMissingAuthorIds() {
        pixivDatabase.insertArtwork(artwork(10L, "a", "/a", 1, "jpg", 1000L, 0).build());
        pixivDatabase.insertArtwork(artwork(11L, "b", "/b", 1, "jpg", 1001L, 0).authorId(88L).build());

        assertThat(pixivDatabase.getArtworkIdsMissingAuthor()).containsExactly(10L);

        pixivDatabase.updateAuthorId(10L, 99L);

        assertThat(pixivDatabase.getArtwork(10L).authorId()).isEqualTo(99L);
        assertThat(pixivDatabase.getArtworkIdsMissingAuthor()).isEmpty();
    }

    // ========== countArtworks ==========

    @Test
    @DisplayName("统计作品数量")
    void shouldCountArtworks() {
        assertThat(pixivDatabase.countArtworks()).isZero();

        insertArtwork(1L, "a", "/a", 1, "jpg", 1700000050L, 0);
        insertArtwork(2L, "b", "/b", 1, "jpg", 1700000051L, 0);

        assertThat(pixivDatabase.countArtworks()).isEqualTo(2);
    }

    // ========== updateArtworkMove ==========

    @Nested
    @DisplayName("updateArtworkMove")
    class MoveTests {

        @Test
        @DisplayName("更新移动信息")
        void shouldUpdateMoveInfo() {
            insertArtwork(12345L, "test", "/original/path", 1, "jpg", 1700000060L, 0);

            pixivDatabase.updateArtworkMove(12345L, "/new/path", 1700000070L);

            ArtworkRecord record = pixivDatabase.getArtwork(12345L);
            assertThat(record.moved()).isTrue();
            assertThat(record.moveFolder()).isEqualTo("/new/path");
            assertThat(record.moveTime()).isEqualTo(1700000070L);
        }

        @Test
        @DisplayName("移动路径末尾斜杠应被去除")
        void shouldStripTrailingSlashOnMove() {
            insertArtwork(12345L, "test", "/path", 1, "jpg", 1700000061L, 0);

            pixivDatabase.updateArtworkMove(12345L, "/new/path/", 1700000071L);

            ArtworkRecord record = pixivDatabase.getArtwork(12345L);
            assertThat(record.moveFolder()).isEqualTo("/new/path");
        }

        @Test
        @DisplayName("传入 classifierTargetFolder 应把它注册为前缀，子目录共用 {N}/<seq>")
        void shouldRegisterClassifierTargetFolderAsPrefix() {
            insertArtwork(701001L, "a", "/orig/a", 1, "jpg", 1700000400L, 0);
            insertArtwork(701002L, "b", "/orig/b", 1, "jpg", 1700000401L, 0);

            String preset = "/cls/preset-root";
            // 多图先落到编号子目录，应被编码到同一个 preset 前缀下
            pixivDatabase.updateArtworkMove(701001L, preset + "/0", 1700000500L, preset);
            // 后续单图直接落到 preset 根，应复用同一行
            pixivDatabase.updateArtworkMove(701002L, preset, 1700000501L, preset);

            assertThat(pixivDatabase.getArtwork(701001L).moveFolder()).isEqualTo(preset + "/0");
            assertThat(pixivDatabase.getArtwork(701002L).moveFolder()).isEqualTo(preset);
            assertThat(pixivDatabase.getArtworkByMoveFolder(preset).artworkId()).isEqualTo(701002L);
            assertThat(pixivDatabase.getArtworkByMoveFolder(preset + "/0").artworkId()).isEqualTo(701001L);
        }

        @Test
        @DisplayName("移动到未注册路径时应自动注册前缀，下次同前缀子目录直接编码")
        void shouldAutoRegisterUnknownMoveTargetAsPrefix() {
            insertArtwork(700001L, "a", "/orig/a", 1, "jpg", 1700000200L, 0);
            insertArtwork(700002L, "b", "/orig/b", 1, "jpg", 1700000201L, 0);

            String firstTarget = "/dyn/added-after-startup";
            pixivDatabase.updateArtworkMove(700001L, firstTarget, 1700000300L);
            // 第二个作品移动到该前缀下的子目录，应被编码为 {N}/0 而不是绝对路径
            String secondTarget = firstTarget + "/0";
            pixivDatabase.updateArtworkMove(700002L, secondTarget, 1700000301L);

            ArtworkRecord first = pixivDatabase.getArtwork(700001L);
            ArtworkRecord second = pixivDatabase.getArtwork(700002L);
            assertThat(first.moveFolder()).isEqualTo(firstTarget);
            assertThat(second.moveFolder()).isEqualTo(secondTarget);
            assertThat(pixivDatabase.getArtworkByMoveFolder(firstTarget).artworkId()).isEqualTo(700001L);
            assertThat(pixivDatabase.getArtworkByMoveFolder(secondTarget).artworkId()).isEqualTo(700002L);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"complete", "direct-delete", "missing-timing", "new-media", "new-during-delete", "changed-before-backup", "commit-failed", "partial"})
    @DisplayName("动图 ZIP 保留原始帧、逐帧时序和转换产物，仅完整归档才能删除文件和记录")
    void archiveDeletionRequiresEveryAnimationOutput(String scenario, @org.junit.jupiter.api.io.TempDir Path root)
            throws Exception {
        long id = 77;
        Path directory = Files.createDirectory(root.resolve("77"));
        Path frames = directory.resolve("animation.zip");
        try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(frames))) {
            for (int page = 0; page < 2; page++) {
                zip.putNextEntry(new java.util.zip.ZipEntry(page + ".jpg"));
                zip.write(page + 1);
                zip.closeEntry();
            }
        }
        byte[] frameBytes = Files.readAllBytes(frames);
        Path timing = Files.writeString(directory.resolve("animation.frames.properties"), "0.jpg=50\n1.jpg=150\n");
        Path webp = Files.write(directory.resolve("animation.webp"), new byte[]{5, 9});
        Path legacyMedia = Files.writeString(directory.resolve("animation.media.properties"), "original=zip");
        List<String> userFiles = List.of("animation.psd", "animation.png", "animation.frames.txt",
                "animation_thumb.jpg", "77.meta.txt", "notes.txt");
        for (String file : userFiles) Files.writeString(directory.resolve(file), file);
        insertArtwork(id, "Animation", directory.toString(), 1, "zip,webp", 100, 0);
        pixivDatabase.replaceArtworkFileNames(id, List.of("animation"));
        var db = DatabaseProviderTestFixtures.main(dataSource);
        var media = new ArtworkMediaStoreImpl(db, null);
        media.save(id, 0, new ArtworkMediaManifest("zip", List.of("zip", "webp")));
        if (scenario.equals("missing-timing")) Files.delete(timing);

        var config = new top.sywyar.pixivdownload.core.appconfig.DownloadConfig();
        config.setRootFolder(root + "/{artwork_id}");
        var messages = TestI18nBeans.appMessages();
        var journal = new StagedFileDeletion(messages, FileOperationTestSupport.journal(dataSource)) {
            @Override
            protected void deleteFile(Path original) throws java.io.IOException {
                super.deleteFile(original);
                if (scenario.equals("new-during-delete")) {
                    Files.writeString(directory.resolve("77.meta.json"), "new after verification");
                }
            }

            @Override
            public boolean deleteArchivedFiles(Map<Path, String> archived, Runnable commitRecord) {
                if (scenario.equals("changed-before-backup")) {
                    try {
                        var time = Files.getLastModifiedTime(webp);
                        Files.write(webp, new byte[]{7, 9});
                        Files.setLastModifiedTime(webp, time);
                    } catch (java.io.IOException failure) {
                        throw new java.io.UncheckedIOException(failure);
                    }
                }
                return super.deleteArchivedFiles(archived, commitRecord);
            }
        };
        var external = new top.sywyar.pixivdownload.core.asset.ExternalWorkFiles(db, codec, null);
        var locator = new top.sywyar.pixivdownload.core.asset.artwork.ArtworkFileLocator(
                pixivDatabase, config, messages, journal, media, external,
                new top.sywyar.pixivdownload.core.asset.DownloadDirectories(db, codec, null));
        var assets = new top.sywyar.pixivdownload.core.download.LocalWorkAssetService(
                new top.sywyar.pixivdownload.core.download.ArtworkFileService(pixivDatabase, locator, null),
                locator, pixivDatabase, null, config, messages, journal, external, null);
        var type = top.sywyar.pixivdownload.core.work.model.WorkType.ARTWORK;
        var query = org.mockito.Mockito.mock(top.sywyar.pixivdownload.core.work.service.WorkQueryService.class);
        org.mockito.Mockito.when(query.hasActiveWork(type, id)).thenReturn(true);
        var deletion = new top.sywyar.pixivdownload.core.metadata.CoreWorkDeletionService(query, assets, pixivDatabase, null, messages);
        var queue = new java.util.ArrayList<Runnable>();
        var settings = new top.sywyar.pixivdownload.core.appconfig.MultiModeConfig();
        var quota = new top.sywyar.pixivdownload.quota.UserQuotaService(settings, config, pixivDatabase, messages,
                queue::add, locator, null, deletion, assets);
        var exporter = new top.sywyar.pixivdownload.quota.QuotaArchiveExportServiceAdapter(
                quota, settings, deletion, pixivDatabase, null, assets);
        var sources = assets.archiveSources(type, id);
        assertThat(sources).containsExactlyInAnyOrder(frames, timing, webp, legacyMedia);
        var entries = new java.util.ArrayList<>(sources.stream().map(path -> top.sywyar.pixivdownload.core.archive.ArchiveExportEntry.file(
                path, id + "/" + path.getFileName(), id)).toList());
        boolean partial = scenario.equals("partial");
        if (partial) {
            Path first = Files.createDirectory(root.resolve("76"));
            Path image = Files.write(first.resolve("page.jpg"), new byte[]{10});
            insertArtwork(76L, "First", first.toString(), 1, "jpg", 99, 0);
            pixivDatabase.replaceArtworkFileNames(76L, List.of("page"));
            media.save(76L, 0, new ArtworkMediaManifest("jpg", List.of("jpg")));
            org.mockito.Mockito.when(query.hasActiveWork(type, 76L)).thenReturn(true);
            entries.add(top.sywyar.pixivdownload.core.archive.ArchiveExportEntry.file(image, "76/page.jpg", 76L));
        }
        List<Long> ids = partial ? List.of(76L, id) : List.of(id);
        if (partial || scenario.equals("commit-failed")) {
            new org.springframework.jdbc.core.JdbcTemplate(dataSource).execute("""
                    CREATE TRIGGER reject_source_deletion BEFORE UPDATE OF deleted ON artworks
                    WHEN NEW.artwork_id = 77 AND NEW.deleted = 1
                    BEGIN SELECT RAISE(ABORT, 'fixture record commit failure'); END""");
        }
        var request = new top.sywyar.pixivdownload.core.archive.ArchiveExportRequest(entries, "artworks", ids.size(), entries.size(),
                "zip", new top.sywyar.pixivdownload.core.archive.ArchiveWorkDeletion("ARTWORK", ids));
        String previous = System.setProperty(RuntimeFiles.DATA_DIR_PROPERTY, root.resolve("runtime").toString());
        try {
            if (scenario.equals("direct-delete")) {
                assertThat(deletion.delete(type, id)).isTrue();
                assertThat(pixivDatabase.getArtwork(id).deleted()).isTrue();
                for (Path path : sources) assertThat(path).doesNotExist();
                for (String file : userFiles) assertThat(Files.readString(directory.resolve(file))).isEqualTo(file);
                return;
            }
            var result = exporter.export(request);
            var controller = new top.sywyar.pixivdownload.quota.ArchiveController(quota, settings, null, pixivDatabase, messages);
            var queued = controller.archiveStatus(result.archiveToken()).getBody().getSourceDeletion();
            assertThat(queued.status()).isEqualTo("pending");
            assertThat(queued.requestedWorks()).isEqualTo(ids.size());
            if (scenario.equals("new-media")) {
                Files.write(directory.resolve("animation.gif"), new byte[]{8});
                media.save(id, 0, new ArtworkMediaManifest("zip", List.of("zip", "webp", "gif")));
            }
            queue.getFirst().run();
            var archive = quota.getArchive(result.archiveToken());
            boolean deleted = scenario.equals("complete");
            var resultStatus = controller.archiveStatus(result.archiveToken()).getBody();
            var sourceDeletion = resultStatus.getSourceDeletion();
            assertThat(sourceDeletion.status()).isEqualTo(scenario.equals("missing-timing") ? "not_started"
                    : partial ? "partial" : deleted ? "succeeded" : "failed");
            assertThat(sourceDeletion.requestedWorks()).isEqualTo(ids.size());
            assertThat(sourceDeletion.deletedWorks()).isEqualTo(partial || deleted ? 1 : 0);
            if (partial) {
                assertThat(pixivDatabase.getArtwork(76L).deleted()).isTrue();
                assertThat(root.resolve("76/page.jpg")).doesNotExist();
            }
            assertThat(pixivDatabase.getArtwork(id).deleted()).isEqualTo(deleted);
            for (String file : userFiles) assertThat(Files.readString(directory.resolve(file))).isEqualTo(file);
            if (deleted) {
                for (Path source : sources) assertThat(source).doesNotExist();
            } else {
                assertThat(Files.readAllBytes(frames)).isEqualTo(frameBytes);
                assertThat(Files.readAllBytes(webp)).containsExactly(scenario.equals("changed-before-backup") ? 7 : 5, 9);
            }
            if (scenario.equals("missing-timing")) {
                assertThat(archive.getStatus()).isEqualTo("error");
                assertThat(media.find(id, 0)).contains(new ArtworkMediaManifest("zip", List.of("zip", "webp")));
            } else {
                assertThat(archive.getStatus()).isEqualTo("ready");
                assertThat(controller.downloadArchive(result.archiveToken()).getStatusCode().value()).isEqualTo(200);
                try (var zip = new java.util.zip.ZipFile(archive.getArchivePath().toFile())) {
                    assertThat(zip.stream().map(java.util.zip.ZipEntry::getName))
                            .containsExactlyInAnyOrderElementsOf(entries.stream().map(top.sywyar.pixivdownload.core.archive.ArchiveExportEntry::entryName).toList());
                    if (partial) assertThat(zip.getInputStream(zip.getEntry("76/page.jpg")).readAllBytes()).containsExactly(10);
                    assertThat(new String(zip.getInputStream(zip.getEntry("77/animation.media.properties")).readAllBytes(),
                            java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("original=zip");
                    assertThat(zip.getInputStream(zip.getEntry("77/animation.zip")).readAllBytes()).isEqualTo(frameBytes);
                    var delays = new java.util.Properties();
                    delays.load(zip.getInputStream(zip.getEntry("77/animation.frames.properties")));
                    assertThat(delays).containsEntry("0.jpg", "50").containsEntry("1.jpg", "150");
                    assertThat(zip.getInputStream(zip.getEntry("77/animation.webp")).readAllBytes()).containsExactly(5, 9);
                }
                if (!deleted) {
                    assertThat(timing).exists();
                    if (scenario.equals("new-media")) {
                        assertThat(directory.resolve("animation.gif")).exists();
                        assertThat(media.find(id, 0)).contains(new ArtworkMediaManifest("zip", List.of("zip", "webp", "gif")));
                    } else {
                        assertThat(media.find(id, 0)).contains(new ArtworkMediaManifest("zip", List.of("zip", "webp")));
                    }
                    if (scenario.equals("new-during-delete")) {
                        assertThat(Files.readString(directory.resolve("77.meta.json"))).isEqualTo("new after verification");
                    }
                }
            }
        } finally {
            if (previous == null) System.clearProperty(RuntimeFiles.DATA_DIR_PROPERTY);
            else System.setProperty(RuntimeFiles.DATA_DIR_PROPERTY, previous);
        }
    }

    @Test
    void retiredClassifierLeavesBothFilesAndArtworkRecordsUntouched(
            @org.junit.jupiter.api.io.TempDir Path root) throws Exception {
        Path source = Files.createDirectory(root.resolve("42"));
        Path target = Files.createDirectory(root.resolve("target"));
        Path sourceFile = Files.writeString(source.resolve("image.jpg"), "source");
        Path targetFile = Files.writeString(target.resolve("keep.jpg"), "target");
        insertArtwork(41, "A", root.resolve("41").toString(), 1, "jpg", 100, 0);
        insertArtwork(42, "B", root.resolve("B").toString(), 1, "jpg", 101, 0);
        pixivDatabase.updateArtworkMove(41, source.toString(), 102L);
        var first = pixivDatabase.getArtwork(41);
        var second = pixivDatabase.getArtwork(42);
        var controller = new top.sywyar.pixivdownload.gui.ImageClassifierController();
        var library = new top.sywyar.pixivdownload.core.download.controller.DownloadedWorkController(
                null, null, null, pixivDatabase, null, null, null, null, TestI18nBeans.appMessages());
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller, library).build();
        long moved = pixivDatabase.getStats()[2];
        for (long id : List.of(41L, 42L)) {
            for (String route : List.of("/api/tools/classifier/moves", "/api/downloaded/move/" + id,
                    "/api/library/artworks/" + id + "/move")) {
                var body = Map.of("source", source.toString(), "target", target.toString(), "artworkId", id,
                        "movePath", target.toString(), "moveTime", 200L);
                mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(route)
                                .contentType("application/json").content(tools.jackson.databind.json.JsonMapper.shared().writeValueAsBytes(body)))
                        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isGone())
                        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().bytes(new byte[0]));
            }
        }
        assertThat(pixivDatabase.getStats()[2]).isEqualTo(moved);
        assertThat(Files.readString(sourceFile)).isEqualTo("source");
        try (var entries = Files.list(target)) { assertThat(entries).containsExactly(targetFile); }
        assertThat(Files.readString(targetFile)).isEqualTo("target");
        assertThat(pixivDatabase.getArtwork(41)).isEqualTo(first);
        assertThat(pixivDatabase.getArtwork(42)).isEqualTo(second);
    }

    // ========== getArtworkByMoveFolder ==========

    @Test
    @DisplayName("通过移动路径查找作品")
    void shouldFindArtworkByMoveFolder() {
        insertArtwork(12345L, "test", "/original", 1, "jpg", 1700000080L, 0);
        pixivDatabase.updateArtworkMove(12345L, "/moved/12345", 1700000090L);

        ArtworkRecord record = pixivDatabase.getArtworkByMoveFolder("/moved/12345");
        assertThat(record).isNotNull();
        assertThat(record.artworkId()).isEqualTo(12345L);

        // 带尾部斜杠也应能找到
        ArtworkRecord record2 = pixivDatabase.getArtworkByMoveFolder("/moved/12345/");
        assertThat(record2).isNotNull();
        assertThat(record2.artworkId()).isEqualTo(12345L);
    }

    @Test
    @DisplayName("查找不存在的移动路径应返回 null")
    void shouldReturnNullForNonExistentMoveFolder() {
        assertThat(pixivDatabase.getArtworkByMoveFolder("/nonexistent")).isNull();
    }

    // ========== getArtworksOlderThan ==========

    @Test
    @DisplayName("查找早于指定时间的作品")
    void shouldReturnArtworksOlderThan() {
        insertArtwork(1L, "old", "/old", 1, "jpg", 1000L, 0);
        insertArtwork(2L, "new", "/new", 1, "jpg", 2000L, 0);

        List<ArtworkRecord> old = pixivDatabase.getArtworksOlderThan(1500L);
        assertThat(old).hasSize(1);
        assertThat(old.get(0).artworkId()).isEqualTo(1L);
    }

    // ========== statistics ==========

    @Nested
    @DisplayName("Statistics 统计操作")
    class StatisticsTests {

        @Test
        @DisplayName("初始统计应全为 0")
        void shouldHaveZeroInitialStats() {
            int[] stats = pixivDatabase.getStats();
            assertThat(stats).containsExactly(0, 0, 0);
        }

        @Test
        @DisplayName("下载终态应原子累计并在日期变化时重置当日计数")
        void shouldRecordDailyDownloadOutcomes() {
            pixivDatabase.recordCompletedDownload(5, "2026-08-23");
            pixivDatabase.recordFailedDownload("2026-08-23");
            pixivDatabase.recordCompletedDownload(3, "2026-08-24");
            pixivDatabase.recordFailedDownload("2026-08-24");

            int[] stats = pixivDatabase.getStats();
            assertThat(stats[0]).isEqualTo(2);  // total_artworks
            assertThat(stats[1]).isEqualTo(8);  // total_images
            assertThat(pixivDatabase.getStatisticsData()).satisfies(data -> {
                assertThat(data.dailyDate()).isEqualTo("2026-08-24");
                assertThat(data.dailyCompleted()).isEqualTo(1);
                assertThat(data.dailyFailed()).isEqualTo(1);
            });
        }

        @Test
        @DisplayName("incrementMoved 应递增移动数")
        void shouldIncrementMoved() {
            pixivDatabase.incrementMoved();
            pixivDatabase.incrementMoved();

            int[] stats = pixivDatabase.getStats();
            assertThat(stats[2]).isEqualTo(2);  // total_moved
        }

        @Test
        @DisplayName("setStats 应直接设置统计值")
        void shouldSetStats() {
            pixivDatabase.setStats(100, 500, 30);

            int[] stats = pixivDatabase.getStats();
            assertThat(stats).containsExactly(100, 500, 30);
        }
    }

    // ========== getUniqueTime ==========

    @Test
    @DisplayName("getUniqueTime 应返回不与已有记录冲突的时间戳")
    void shouldReturnUniqueTime() {
        long time1 = pixivDatabase.getUniqueTime();
        insertArtwork(1L, "a", "/a", 1, "jpg", time1, 0);

        long time2 = pixivDatabase.getUniqueTime();
        assertThat(time2).isGreaterThanOrEqualTo(time1);

        // 确保 time2 与 time1 不冲突
        insertArtwork(2L, "b", "/b", 1, "jpg", time2, 0);
        assertThat(pixivDatabase.hasArtwork(2L)).isTrue();
    }

    private void insertArtwork(long artworkId, String title, String folder, int count,
                               String extensions, long time, Integer xRestrict) {
        pixivDatabase.insertArtwork(artwork(artworkId, title, folder, count, extensions, time, xRestrict).build());
    }

    private static InsertArtworkArgument.InsertArtworkArgumentBuilder artwork(
            long artworkId, String title, String folder, int count,
            String extensions, long time, Integer xRestrict) {
        return InsertArtworkArgument.builder()
                .artworkId(artworkId)
                .title(title)
                .folder(folder)
                .count(count)
                .extensions(extensions)
                .time(time)
                .xRestrict(xRestrict);
    }

    @Test
    @DisplayName("重复初始化时不应因 authorId 列迁移失败")
    void shouldAllowRepeatedInit() {
        assertThatCode(() -> pixivDatabase.init()).doesNotThrowAnyException();
    }
}
