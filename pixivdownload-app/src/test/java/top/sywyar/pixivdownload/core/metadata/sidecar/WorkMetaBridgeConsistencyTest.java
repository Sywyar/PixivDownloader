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
import top.sywyar.pixivdownload.core.db.PixivMapper;
import top.sywyar.pixivdownload.core.db.pathprefix.PathPrefixCodec;
import top.sywyar.pixivdownload.core.db.pathprefix.PathPrefixMapper;
import top.sywyar.pixivdownload.core.db.schema.DatabaseInitializer;
import top.sywyar.pixivdownload.core.metadata.CoreWorkMetadataRepository;
import top.sywyar.pixivdownload.core.metadata.novel.NovelMetadataRepository;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.plugin.registry.DatabaseSchemaRegistry;
import top.sywyar.pixivdownload.plugin.api.work.model.WorkMetadata;
import top.sywyar.pixivdownload.plugin.api.work.model.WorkType;
import top.sywyar.pixivdownload.plugin.api.work.service.WorkMetadataRepository;
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
 * <b>sidecar 落盘文档</b>来自同一份值」的持久化 round-trip，以及软删除下列投影过滤、文件保留的语义。
 *
 * <p>这是 sidecar↔列投影一致性的<b>持久化端到端</b>那一截（curator 内存级一致性已由
 * {@code WorkMetaCuratorTest} 钉），用真 in-memory SQLite + 真文件系统跑通两类媒体。
 */
@DisplayName("meta 捕获一致性：sidecar 落盘、列投影与软删除")
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

    // 写入侧（捕获 → 列投影 + sidecar）
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

        // 作品目录置于下载根下 → 路径列走符号根 {0}，编码/解码可复现，sidecar 落点 == 读点
        DownloadConfig downloadConfig = new DownloadConfig();
        downloadConfig.setRootFolder(tempDir.toAbsolutePath().normalize().toString());
        PathPrefixCodec codec = new PathPrefixCodec(
                sqlSession.getMapper(PathPrefixMapper.class), downloadConfig, TestI18nBeans.appMessages());
        codec.init();

        pixivDatabase = new PixivDatabase(
                sqlSession.getMapper(PixivMapper.class), TestI18nBeans.appMessages(), codec, initializer);
        pixivDatabase.init();
        novelMetadataRepository = new NovelMetadataRepository(dataSource, codec);

        StagedFileDeletion stagedFileDeletion = new StagedFileDeletion(TestI18nBeans.appMessages());
        ArtworkFileLocator artworkFileLocator = new ArtworkFileLocator(
                pixivDatabase, downloadConfig, TestI18nBeans.appMessages(), stagedFileDeletion);
        WorkSidecarStore sidecarStore = new WorkSidecarStore(mapper);

        captureService = new WorkMetaCaptureService(
                new WorkMetaCurator(mapper), sidecarStore, pixivDatabase, novelMetadataRepository,
                artworkFileLocator, mapper);

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

    private JsonNode readSidecar(Path dir, long id) {
        try {
            return mapper.readTree(dir.resolve(WorkSidecarFiles.fileName(id)).toFile());
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
    @DisplayName("持久化 round-trip：列投影与落盘 sidecar 同源")
    class RoundTrip {

        @Test
        @DisplayName("插画：捕获后 upload_time/is_original 列投影与 sidecar normalized 逐字段一致")
        void artworkColumnAndSidecarAgree() {
            long id = 7L;
            pixivDatabase.insertArtwork(id, "作品", artworkDir(id).toString(), 1, "jpg",
                    1000L, 0, false, null, null, 1L);

            captureService.captureArtwork(id, json("{\"uploadDate\":\"" + UPLOAD_ISO + "\",\"isOriginal\":true,"
                    + "\"description\":\"d\"}"), null, "schedule");

            WorkMetadata fromColumn = metadataRepository.find(WorkType.ARTWORK, id).orElseThrow();
            JsonNode fromSidecar = readSidecar(artworkDir(id), id);

            assertThat(fromColumn.uploadTime())
                    .isEqualTo(fromSidecar.path("normalized").path("uploadTime").longValue())
                    .isEqualTo(UPLOAD_MILLIS);
            assertThat(fromColumn.isOriginal())
                    .isEqualTo(fromSidecar.path("normalized").path("isOriginal").booleanValue())
                    .isEqualTo(true);
            assertThat(fromSidecar.path("source").asText()).isEqualTo("schedule");
        }

        @Test
        @DisplayName("小说：捕获后 upload_time 列投影与 sidecar normalized 一致；is_original 顶层与小说块同源")
        void novelColumnAndSidecarAgree() {
            long id = 42L;
            insertNovel(id, novelDir(id), true);

            captureService.captureNovel(id, json("{\"uploadDate\":\"" + UPLOAD_ISO + "\",\"isOriginal\":true,"
                    + "\"content\":\"很长的正文……\",\"description\":\"d\"}"), "schedule");

            WorkMetadata fromColumn = metadataRepository.find(WorkType.NOVEL, id).orElseThrow();
            JsonNode fromSidecar = readSidecar(novelDir(id), id);

            assertThat(fromColumn.uploadTime())
                    .isEqualTo(fromSidecar.path("normalized").path("uploadTime").longValue())
                    .isEqualTo(UPLOAD_MILLIS);
            // 列 is_original 来自 insert（捕获不写小说 is_original 列），sidecar 来自捕获 body，两者同值
            assertThat(fromColumn.isOriginal())
                    .isEqualTo(fromSidecar.path("normalized").path("isOriginal").booleanValue())
                    .isEqualTo(true);
            assertThat(fromSidecar.path("source").asText()).isEqualTo("schedule");
        }
    }

    @Nested
    @DisplayName("软删除语义：sidecar 文件保留 + 查询层过滤")
    class SoftDelete {

        @Test
        @DisplayName("插画软删后：sidecar 文件保留，列投影 find 返回 empty")
        void artworkSoftDeleteKeepsSidecarButFiltersColumnRead() {
            long id = 8L;
            Path dir = artworkDir(id);
            pixivDatabase.insertArtwork(id, "作品", dir.toString(), 1, "jpg", 1000L, 0, false, null, null, 1L);
            captureService.captureArtwork(id, json("{\"uploadDate\":\"" + UPLOAD_ISO + "\",\"isOriginal\":true}"),
                    null, "schedule");
            assertThat(Files.exists(dir.resolve(id + ".meta.json"))).isTrue();

            pixivDatabase.markArtworkDeleted(id);

            // 软删除是软删除：sidecar 文件不被移除（仅硬删除链路才删文件）
            assertThat(Files.exists(dir.resolve(id + ".meta.json"))).as("软删后 sidecar 文件保留").isTrue();
            // 查询层（列投影桥）按 deleted=1 过滤，软删行视为不存在
            assertThat(metadataRepository.find(WorkType.ARTWORK, id)).as("列投影读过滤软删").isEmpty();
        }

        @Test
        @DisplayName("小说软删后：sidecar 文件保留，列投影 find 返回 empty")
        void novelSoftDeleteKeepsSidecarButFiltersColumnRead() {
            long id = 43L;
            Path dir = novelDir(id);
            insertNovel(id, dir, true);
            captureService.captureNovel(id, json("{\"uploadDate\":\"" + UPLOAD_ISO + "\",\"isOriginal\":true}"),
                    "schedule");
            assertThat(Files.exists(dir.resolve(id + ".meta.json"))).isTrue();

            novelMetadataRepository.markNovelDeleted(id);

            assertThat(Files.exists(dir.resolve(id + ".meta.json"))).as("软删后 sidecar 文件保留").isTrue();
            assertThat(metadataRepository.find(WorkType.NOVEL, id)).as("列投影读过滤软删").isEmpty();
        }
    }
}
