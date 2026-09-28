package top.sywyar.pixivdownload.core.metadata.sidecar;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import top.sywyar.pixivdownload.core.db.schema.DatabaseInitializer;
import top.sywyar.pixivdownload.core.metadata.WorkMetadataStore;
import top.sywyar.pixivdownload.core.work.model.WorkType;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.plugin.registry.schema.DatabaseSchemaRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.*;

@DisplayName("作品元数据快照与查询列集中入库")
class WorkMetadataPersistenceTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();
    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbc;
    private WorkMetaCaptureService service;

    @BeforeEach
    void setUp() {
        dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        jdbc = new JdbcTemplate(dataSource);
        var registry = DatabaseSchemaRegistry.forBuiltInPlugins();
        var initializer = new DatabaseInitializer(jdbc, registry.contributions(), registry.mergedSchema(),
                TestI18nBeans.appMessages(), event -> {});
        initializer.initialize();
        for (String table : new String[]{"artworks", "novels"}) {
            String id = table.equals("artworks") ? "artwork_id" : "novel_id";
            jdbc.update("INSERT INTO " + table + "(" + id + ", title, folder, count, extensions, time)"
                    + " VALUES(7, 'fixture', ?, 1, 'jpg', 1000)", directory.toString());
        }
        service = new WorkMetaCaptureService(new WorkMetaCurator(mapper), new WorkMetadataStore(dataSource, initializer), mapper);
    }

    @AfterEach
    void close() { dataSource.destroy(); }

    private String snapshot(String table) {
        return jdbc.queryForObject("SELECT metadata_json FROM " + table, String.class);
    }

    @Test
    @DisplayName("已有作品数据库自动补齐快照列和媒体表，原记录不变")
    void existingDatabaseAddsStorageWithoutLosingWorks() {
        jdbc.execute("ALTER TABLE artworks DROP COLUMN metadata_json");
        jdbc.execute("ALTER TABLE novels DROP COLUMN metadata_json");
        jdbc.execute("DROP TABLE artwork_media");
        var registry = DatabaseSchemaRegistry.forBuiltInPlugins();
        new DatabaseInitializer(jdbc, registry.contributions(), registry.mergedSchema(),
                TestI18nBeans.appMessages(), event -> {}).initialize();
        assertThat(jdbc.queryForObject("SELECT title FROM artworks WHERE artwork_id = 7", String.class)).isEqualTo("fixture");
        assertThat(jdbc.queryForObject("SELECT title FROM novels WHERE novel_id = 7", String.class)).isEqualTo("fixture");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM artwork_media", Integer.class)).isZero();
        service.captureForwardedArtwork(7, "{\"illustTitle\":\"插画\"}");
        service.captureForwardedNovel(7, "{\"title\":\"小说\"}");
        assertThat(snapshot("artworks")).contains("插画");
        assertThat(snapshot("novels")).contains("小说");
    }

    @Test
    @DisplayName("同 ID 插画和小说分别保存裁剪快照，不生成目录记录文件")
    void persistsSeparateWorkTypesWithoutFiles() throws Exception {
        service.capture(WorkType.ARTWORK, 7, "{\"illustTitle\":\"中文标题\",\"isOriginal\":true}", null, "schedule");
        service.captureForwardedNovel(7, "{\"title\":\"小说\",\"content\":\"正文不复制\",\"textEmbeddedImages\":{\"1\":{}}}");
        assertThat(snapshot("artworks")).contains("中文标题", "\"source\":\"schedule\"", "\"raw\"");
        assertThat(snapshot("novels")).contains("小说", "\"source\":\"forward\"").doesNotContain("正文不复制", "textEmbeddedImages");
        assertThat(jdbc.queryForObject("SELECT is_original FROM artworks", Integer.class)).isEqualTo(1);
        try (var files = Files.list(directory)) { assertThat(files).isEmpty(); }
    }

    @Test
    @DisplayName("无效输入与超限快照不覆盖已有内容，合法投影仍可更新")
    void rejectsInvalidAndOversizedSnapshots() {
        service.captureForwardedArtwork(7, "{\"illustTitle\":\"有效标题\"}");
        String saved = snapshot("artworks");
        for (String input : new String[]{"", " ", "{", "[]", "null"}) {
            service.captureForwardedArtwork(7, input);
            service.captureForwardedNovel(7, input);
        }
        service.captureArtwork(7, null, null, "schedule");
        assertThat(snapshot("artworks")).isEqualTo(saved);
        assertThat(snapshot("novels")).isNull();
        var body = mapper.createObjectNode().put("description", "x".repeat(300_000)).put("isOriginal", true);
        service.captureArtwork(7, body, null, "schedule");
        assertThat(snapshot("artworks")).isEqualTo(saved);
        assertThat(jdbc.queryForObject("SELECT is_original FROM artworks", Integer.class)).isEqualTo(1);
        service.captureNovel(7, body, "schedule");
        assertThat(snapshot("novels")).isNull();
    }

    @Test
    @DisplayName("空标题或占位标题不覆盖已有快照，有效标题可刷新")
    void preservesMeaningfulSnapshot() {
        service.captureForwardedArtwork(7, "{\"illustTitle\":\"原有标题\"}");
        String saved = snapshot("artworks");
        for (String title : new String[]{"", "作品 7", "Artwork 7"}) {
            service.captureForwardedArtwork(7, mapper.createObjectNode().put("illustTitle", title).toString());
            assertThat(snapshot("artworks")).isEqualTo(saved);
        }
        service.captureForwardedArtwork(7, "{\"illustTitle\":\"新的标题\"}");
        assertThat(snapshot("artworks")).contains("新的标题").doesNotContain("原有标题");
    }

    @Test
    @DisplayName("SQL 失败整条回滚且捕获不反报下载失败，未登记与已删除作品不新增快照")
    void preservesAtomicityAndLifecycle() {
        service.captureForwardedArtwork(7, "{\"illustTitle\":\"原有标题\",\"isOriginal\":true}");
        String saved = snapshot("artworks");
        jdbc.execute("CREATE TRIGGER reject_metadata BEFORE UPDATE OF metadata_json ON artworks"
                + " BEGIN SELECT RAISE(ABORT, 'fixture'); END");
        assertThatCode(() -> service.captureForwardedArtwork(7, "{\"illustTitle\":\"新标题\",\"isOriginal\":false}")).doesNotThrowAnyException();
        assertThat(snapshot("artworks")).isEqualTo(saved);
        assertThat(jdbc.queryForObject("SELECT is_original FROM artworks", Integer.class)).isEqualTo(1);
        jdbc.execute("DROP TRIGGER reject_metadata");
        jdbc.update("UPDATE artworks SET deleted = 1, metadata_json = NULL");
        service.captureForwardedArtwork(7, "{\"illustTitle\":\"已删除作品\"}");
        service.captureForwardedArtwork(8, "{\"illustTitle\":\"未登记作品\"}");
        assertThat(snapshot("artworks")).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM artworks", Integer.class)).isEqualTo(1);
    }
}
