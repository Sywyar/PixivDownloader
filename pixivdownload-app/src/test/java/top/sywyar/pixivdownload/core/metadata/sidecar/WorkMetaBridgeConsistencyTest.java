package top.sywyar.pixivdownload.core.metadata.sidecar;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import top.sywyar.pixivdownload.author.AuthorService;
import top.sywyar.pixivdownload.core.appconfig.DownloadConfig;
import top.sywyar.pixivdownload.core.asset.StagedFileDeletion;
import top.sywyar.pixivdownload.core.asset.artwork.ArtworkFileLocator;
import top.sywyar.pixivdownload.core.db.PixivDatabase;
import top.sywyar.pixivdownload.core.db.InsertArtworkArgument;
import top.sywyar.pixivdownload.core.db.PixivMapper;
import top.sywyar.pixivdownload.core.db.pathprefix.PathPrefixCodec;
import top.sywyar.pixivdownload.core.db.pathprefix.PathPrefixMapper;
import top.sywyar.pixivdownload.core.db.schema.DatabaseInitializer;
import top.sywyar.pixivdownload.core.metadata.CoreWorkMetadataRepository;
import top.sywyar.pixivdownload.core.metadata.novel.NovelMetadataRepository;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.plugin.registry.schema.DatabaseSchemaRegistry;
import top.sywyar.pixivdownload.core.work.model.WorkMetadata;
import top.sywyar.pixivdownload.core.work.model.WorkType;
import top.sywyar.pixivdownload.core.work.service.WorkMetadataRepository;
import top.sywyar.pixivdownload.series.MangaSeriesService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * meta 捕获端到端一致性守卫：钉住「捕获真写一遍后，<b>列投影读</b>（{@link WorkMetadataRepository}）与
 * <b>数据库快照</b>来自同一份值」的持久化往返，以及软删除下列投影过滤、记录清理的语义。
 *
 * <p>这是 快照↔列投影一致性的<b>持久化端到端</b>那一截（curator 内存级一致性已由
 * {@code WorkMetaCuratorTest} 钉），用真 in-memory SQLite + 真文件系统跑通两类媒体。
 */
@DisplayName("元数据捕获一致性：数据库快照、列投影与软删除")
class WorkMetaBridgeConsistencyTest {

    private static final String UPLOAD_ISO = "2026-06-06T21:27:00+00:00";
    private static final long UPLOAD_MILLIS = OffsetDateTime.parse(UPLOAD_ISO).toInstant().toEpochMilli();

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();

    private SingleConnectionDataSource dataSource;
    private SqlSession sqlSession;
    private PixivDatabase pixivDatabase;
    private NovelMetadataRepository novelMetadataRepository;

    // 写入侧（捕获 → 列投影 + 快照）
    private WorkMetaCaptureService captureService;
    // 读取侧列投影
    private WorkMetadataRepository metadataRepository;

    @BeforeEach
    void setUp() {
        dataSource = new SingleConnectionDataSource();
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setUrl("jdbc:sqlite::memory:");
        dataSource.setSuppressClose(true);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        Environment env = new Environment("test", new JdbcTransactionFactory(), dataSource);
        Configuration config = new Configuration(env);
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(PixivMapper.class);
        config.addMapper(PathPrefixMapper.class);
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(config);
        sqlSession = factory.openSession(true);

        DatabaseSchemaRegistry registry = DatabaseSchemaRegistry.forBuiltInPlugins();
        DatabaseInitializer initializer = new DatabaseInitializer(
                jdbc, registry.contributions(), registry.mergedSchema(),
                TestI18nBeans.appMessages(), event -> {});
        initializer.initialize();

        // 作品目录置于下载根下 → 路径列走符号根 {0}，编码/解码可复现，快照 落点 == 读点
        DownloadConfig downloadConfig = new DownloadConfig();
        downloadConfig.setRootFolder(tempDir.toAbsolutePath().normalize().toString());
        PathPrefixCodec codec = new PathPrefixCodec(
                sqlSession.getMapper(PathPrefixMapper.class), downloadConfig, TestI18nBeans.appMessages());
        codec.init();

        pixivDatabase = new PixivDatabase(
                sqlSession.getMapper(PixivMapper.class), TestI18nBeans.appMessages(), codec, initializer);
        pixivDatabase.init();
        novelMetadataRepository = new NovelMetadataRepository(dataSource, codec);

        captureService = new WorkMetaCaptureService(
                new WorkMetaCurator(mapper), new top.sywyar.pixivdownload.core.metadata.WorkMetadataStore(dataSource, initializer), mapper);

        AuthorService authorService = mock(AuthorService.class);
        when(authorService.getAuthorNames(anyCollection())).thenReturn(java.util.Map.of());
        MangaSeriesService mangaSeriesService = mock(MangaSeriesService.class);
        when(mangaSeriesService.getSeriesByIds(anyCollection())).thenReturn(java.util.List.of());

        metadataRepository = new CoreWorkMetadataRepository(
                pixivDatabase, novelMetadataRepository, authorService, mangaSeriesService);
    }

    @AfterEach
    void tearDown() {
        sqlSession.close();
        dataSource.destroy();
    }

    private JsonNode json(String text) {
        try {
            return mapper.readTree(text);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private JsonNode readSnapshot(Path dir, long id) {
        try {
            return mapper.readTree(java.util.Objects.requireNonNullElse(new JdbcTemplate(dataSource).queryForObject(
                    dir.getFileName().toString().startsWith("novel-")
                            ? "SELECT metadata_json FROM novels WHERE novel_id = ?"
                            : "SELECT metadata_json FROM artworks WHERE artwork_id = ?", String.class, id), "null"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Path artworkDir(long id) {
        Path dir = tempDir.resolve(String.valueOf(id));
        try {
            Files.createDirectories(dir);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return dir;
    }

    private Path novelDir(long id) {
        Path dir = tempDir.resolve("novel-" + id);
        try {
            Files.createDirectories(dir);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return dir;
    }

    private void insertNovel(long id, Path dir, boolean original) {
        new JdbcTemplate(dataSource).update("""
                        INSERT INTO novels(novel_id, title, folder, count, extensions, time, R18, is_ai,
                                           author_id, description, file_name, file_author_name_id,
                                           series_id, series_order, word_count, text_length,
                                           reading_time_seconds, page_count, is_original, x_language,
                                           raw_content, cover_ext, deleted)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                        """,
                id, "小说", dir.toString(), 1, "", 2000L, 0, false,
                null, null, 1L, null, null, null, null, null,
                null, null, original, null, "正文", null);
    }

    @Nested
    @DisplayName("持久化 round-trip：列投影与数据库快照 同源")
    class RoundTrip {

        @Test
        @DisplayName("插画：捕获后 upload_time/is_original 列投影与 快照 normalized 逐字段一致")
        void artworkColumnAndSnapshotAgree() {
            long id = 7L;
            pixivDatabase.insertArtwork(InsertArtworkArgument.builder()
                    .artworkId(id)
                    .title("作品")
                    .folder(artworkDir(id).toString())
                    .count(1)
                    .extensions("jpg")
                    .time(1000L)
                    .xRestrict(0)
                    .isAi(false)
                    .fileName(1L)
                    .build());

            captureService.captureArtwork(id, json("{\"uploadDate\":\"" + UPLOAD_ISO + "\",\"isOriginal\":true,"
                    + "\"description\":\"d\"}"), null, "schedule");

            WorkMetadata fromColumn = metadataRepository.find(WorkType.ARTWORK, id).orElseThrow();
            JsonNode fromSnapshot = readSnapshot(artworkDir(id), id);

            assertThat(fromColumn.uploadTime())
                    .isEqualTo(fromSnapshot.path("normalized").path("uploadTime").longValue())
                    .isEqualTo(UPLOAD_MILLIS);
            assertThat(fromColumn.isOriginal())
                    .isEqualTo(fromSnapshot.path("normalized").path("isOriginal").booleanValue())
                    .isEqualTo(true);
            assertThat(fromSnapshot.path("source").asText()).isEqualTo("schedule");
        }

        @Test
        @DisplayName("小说：捕获后 upload_time 列投影与 快照 normalized 一致；is_original 顶层与小说块同源")
        void novelColumnAndSnapshotAgree() {
            long id = 42L;
            insertNovel(id, novelDir(id), true);

            captureService.captureNovel(id, json("{\"uploadDate\":\"" + UPLOAD_ISO + "\",\"isOriginal\":true,"
                    + "\"content\":\"很长的正文……\",\"description\":\"d\"}"), "schedule");

            WorkMetadata fromColumn = metadataRepository.find(WorkType.NOVEL, id).orElseThrow();
            JsonNode fromSnapshot = readSnapshot(novelDir(id), id);

            assertThat(fromColumn.uploadTime())
                    .isEqualTo(fromSnapshot.path("normalized").path("uploadTime").longValue())
                    .isEqualTo(UPLOAD_MILLIS);
            // 列 is_original 来自 insert（捕获不写小说 is_original 列），快照 来自捕获 body，两者同值
            assertThat(fromColumn.isOriginal())
                    .isEqualTo(fromSnapshot.path("normalized").path("isOriginal").booleanValue())
                    .isEqualTo(true);
            assertThat(fromSnapshot.path("source").asText()).isEqualTo("schedule");
        }
    }

    @Nested
    @DisplayName("软删除语义：清理数据库快照 + 查询层过滤")
    class SoftDelete {

        @Test
        @DisplayName("插画软删后：清理数据库快照，列投影 find 返回 empty")
        void artworkSoftDeleteKeepsSnapshotButFiltersColumnRead() {
            long id = 8L;
            Path dir = artworkDir(id);
            pixivDatabase.insertArtwork(InsertArtworkArgument.builder()
                    .artworkId(id)
                    .title("作品")
                    .folder(dir.toString())
                    .count(1)
                    .extensions("jpg")
                    .time(1000L)
                    .xRestrict(0)
                    .isAi(false)
                    .fileName(1L)
                    .build());
            captureService.captureArtwork(id, json("{\"uploadDate\":\"" + UPLOAD_ISO + "\",\"isOriginal\":true}"),
                    null, "schedule");
            assertThat(readSnapshot(dir, id).isObject()).isTrue();
            assertThat(Files.exists(dir.resolve(id + ".meta.json"))).isFalse();

            pixivDatabase.markArtworkDeleted(id);

            // 软删除同时清理数据库快照，避免重下载误用旧事实。
            assertThat(readSnapshot(dir, id).isNull()).isTrue();
            // 查询层（列投影桥）按 deleted=1 过滤，软删行视为不存在
            assertThat(metadataRepository.find(WorkType.ARTWORK, id)).as("列投影读过滤软删").isEmpty();
        }

        @Test
        @DisplayName("小说软删后：清理数据库快照，列投影 find 返回 empty")
        void novelSoftDeleteKeepsSnapshotButFiltersColumnRead() {
            long id = 43L;
            Path dir = novelDir(id);
            insertNovel(id, dir, true);
            captureService.captureNovel(id, json("{\"uploadDate\":\"" + UPLOAD_ISO + "\",\"isOriginal\":true}"),
                    "schedule");
            assertThat(readSnapshot(dir, id).isObject()).isTrue();
            assertThat(Files.exists(dir.resolve(id + ".meta.json"))).isFalse();

            novelMetadataRepository.markNovelDeleted(id);

            assertThat(readSnapshot(dir, id).isNull()).isTrue();
            assertThat(metadataRepository.find(WorkType.NOVEL, id)).as("列投影读过滤软删").isEmpty();
        }
    }

    @Test
    @DisplayName("软删硬删与重下载同步维护逐页媒体记录，新下载的媒体事实不被旧行清理误删")
    void mediaRecordsFollowWorkLifecycle() throws Exception {
        var store = new top.sywyar.pixivdownload.core.asset.artwork.ArtworkMediaStoreImpl(dataSource, null);
        var original = new top.sywyar.pixivdownload.core.asset.ArtworkMediaManifest("jpg", java.util.List.of("jpg"));
        var converted = new top.sywyar.pixivdownload.core.asset.ArtworkMediaManifest("jpg", java.util.List.of("webp"), false);
        var row = InsertArtworkArgument.builder().artworkId(55L).title("作品").folder(artworkDir(55).toString())
                .count(1).extensions("jpg").time(1000L).fileName(1L).build();
        store.save(55L, 0, original);
        store.save(55L, 3, original);
        pixivDatabase.insertArtwork(row);
        assertThat(store.find(55L, 3)).isEmpty();
        assertThat(store.find(55L, 0)).contains(original);
        pixivDatabase.markArtworkDeleted(55L);
        assertThat(store.find(55L, 0)).isEmpty();
        store.save(55L, 0, converted);
        pixivDatabase.insertArtwork(row);
        assertThat(store.find(55L, 0)).contains(converted);
        pixivDatabase.deleteArtwork(55L);
        assertThat(store.find(55L, 0)).isEmpty();
        assertThat(pixivDatabase.getArtwork(55L)).isNull();
    }
}
