package top.sywyar.pixivdownload.core.download;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import top.sywyar.pixivdownload.core.appconfig.DownloadConfig;
import top.sywyar.pixivdownload.core.artwork.download.*;
import top.sywyar.pixivdownload.core.work.importing.*;
import top.sywyar.pixivdownload.core.work.model.WorkType;
import top.sywyar.pixivdownload.core.asset.ExternalWorkFiles;
import top.sywyar.pixivdownload.core.db.*;
import top.sywyar.pixivdownload.core.db.pathprefix.*;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.plugin.api.download.lifecycle.*;
import top.sywyar.pixivdownload.plugin.registry.schema.DatabaseSchemaRegistry;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@DisplayName("外部文件登记、只读定位与事务")
class LocalWorkFileImporterTest {
    @TempDir Path temp;
    private SingleConnectionDataSource dataSource;
    private PixivDatabase database;
    private ArtworkDownloadHistory history;
    private DownloadConfig settings;
    private Path source;
    private Path output;
    private ExternalWorkFiles externalFiles;
    private top.sywyar.pixivdownload.author.AuthorMapper authorMapper;
    private top.sywyar.pixivdownload.author.AuthorService authors;
    private final List<DownloadEvent> events = new ArrayList<>();
    private final DownloadLifecycleRegistry lifecycle = new DownloadLifecycleRegistry();

    @BeforeEach void setUp() throws Exception {
        lifecycle.register(new top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityOwner(
                "import-observer", "import-observer", 1, 1),
                new DownloadLifecycleRegistry.Contribution(List.of(events::add), List.of()));
        source = Files.createDirectory(temp.resolve("source"));
        output = Files.createDirectory(temp.resolve("output"));
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", source.resolve("custom.png").toFile());
        dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        var registry = DatabaseSchemaRegistry.forBuiltInPlugins();
        var initializer = top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.initializer(new org.springframework.jdbc.core.JdbcTemplate(dataSource),
                registry.contributions(), registry.mergedSchema(), TestI18nBeans.appMessages(), event -> {});
        initializer.initialize();
        settings = new DownloadConfig();
        settings.setRootFolder(output.toString());
        var codec = new PathPrefixCodec(new PathPrefixMapper(top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.main(dataSource)), settings, TestI18nBeans.appMessages());
        codec.init();
        database = new PixivDatabase(new PixivMapper(top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.main(dataSource)), TestI18nBeans.appMessages(), codec, initializer);
        database.init();
        history = new ArtworkDownloadHistoryAdapter(database);
        externalFiles = new ExternalWorkFiles(DatabaseProviderTestFixtures.main(dataSource), codec, initializer);
        authorMapper = new top.sywyar.pixivdownload.author.AuthorMapper(top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.main(dataSource));
        authors = new top.sywyar.pixivdownload.author.AuthorService(authorMapper, database,
                org.mockito.Mockito.mock(org.springframework.web.client.RestTemplate.class),
                org.mockito.Mockito.mock(org.springframework.scheduling.TaskScheduler.class),
                TestI18nBeans.appMessages(), initializer);
    }

    @AfterEach void close() { if (dataSource != null) dataSource.destroy(); }

    private WorkFileImportRequest request(List<Path> paths) {
        return new WorkFileImportRequest(WorkType.ARTWORK, 42, "Title", paths.size(), 1, false, 84L, "Author", null,
                null, null, List.of(), source, paths, null);
    }
    private LocalWorkFileImporter importer(ArtworkDownloadHistory target) {
        return new LocalWorkFileImporter(target, database, externalFiles, new DataSourceTransactionManager(dataSource), lifecycle, authors::observe);
    }
    @Test @DisplayName("纯本地登记保存文件引用，未知元数据保持为空且重复执行不覆盖")
    void importsLocalFactsWithoutMetadata() throws Exception {
        Path original = source.resolve("custom.png");
        byte[] bytes = Files.readAllBytes(original);
        var request = new LocalWorkFileImportRequest(WorkType.ARTWORK, 42, source, List.of(original));
        var importer = importer(history);
        assertThat(importer.importLocalFiles(request)).isTrue();
        var row = new org.springframework.jdbc.core.JdbcTemplate(dataSource).queryForMap(
                "SELECT title,author_id,r18,is_ai,description,metadata_json,time FROM artworks WHERE artwork_id=42");
        assertThat(row.get("title")).isEqualTo("");
        assertThat(((Number) row.get("time")).longValue()).isPositive();
        for (String field : List.of("author_id", "r18", "is_ai", "description", "metadata_json"))
            assertThat(row.get(field)).as(field).isNull();
        assertThat(externalFiles.file(WorkType.ARTWORK, 42, 0)).isEqualTo(original);
        assertThat(importer.importLocalFiles(request)).isFalse();
        assertThat(Files.readAllBytes(original)).isEqualTo(bytes);
        try (var files = Files.list(output)) { assertThat(files.count()).isZero(); }
    }

    @Test
    @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    @DisplayName("真实模板扫描后重验文件，单项失败不留下记录，成功项保留原文件且日志不泄露路径")
    void templateImportRevalidatesPreview(org.springframework.boot.test.system.CapturedOutput outputLog) throws Exception {
        Path missing = Files.copy(source.resolve("custom.png"), source.resolve("42_p0.png"));
        Path retained = Files.copy(source.resolve("custom.png"), source.resolve("43_p0.png"));
        byte[] original = Files.readAllBytes(retained);
        var previousLocale = Locale.getDefault();
        try (var service = new top.sywyar.pixivdownload.core.importing.LocalImportService(
                new top.sywyar.pixivdownload.core.importing.LocalImportConfig(), importer(history), database, externalFiles)) {
            Locale.setDefault(Locale.SIMPLIFIED_CHINESE);
            var preview = service.scan(new top.sywyar.pixivdownload.plugin.api.gui.importing.LocalArtworkImports.Request(
                    List.of(source + "/{artworkId}_p{page}.{extension}"), List.of(), List.of(), false, ""));
            awaitPhase(service, preview.id(), top.sywyar.pixivdownload.plugin.api.gui.importing.LocalArtworkImports.Phase.READY);
            assertThat(database.getArtwork(43)).isNull();
            Files.delete(missing);
            service.start(preview.id());
            awaitPhase(service, preview.id(), top.sywyar.pixivdownload.plugin.api.gui.importing.LocalArtworkImports.Phase.COMPLETED);
            var result = service.status(preview.id());
            assertThat(result.processed()).isEqualTo(2);
            assertThat(result.imported()).isEqualTo(1);
            assertThat(result.failed()).isEqualTo(1);
            assertThat(result.problems()).hasSize(1);
            assertThat(result.problems().getFirst().artworkId()).isEqualTo(42L);
            assertThat(database.getArtwork(42)).isNull();
            assertThat(database.getArtwork(43).count()).isEqualTo(1);
            assertThat(externalFiles.file(WorkType.ARTWORK, 43, 0)).isEqualTo(retained);
            assertThat(Files.readAllBytes(retained)).isEqualTo(original);
            try (var files = Files.list(output)) { assertThat(files.count()).isZero(); }
        } finally { Locale.setDefault(previousLocale); }
        assertThat(outputLog.getAll()).contains("Local import scan accepted:", "Local import preview ready:",
                "Local import started:", "Local import finished:", "processed=2, imported=1, skipped=0, failed=1")
                .doesNotContain(source.toString());
    }

    private static void awaitPhase(top.sywyar.pixivdownload.core.importing.LocalImportService service, UUID id,
            top.sywyar.pixivdownload.plugin.api.gui.importing.LocalArtworkImports.Phase expected) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (service.status(id).phase() != expected && System.nanoTime() < deadline) Thread.sleep(10);
        assertThat(service.status(id).phase()).isEqualTo(expected);
    }
    @Test @DisplayName("任意源文件名直接登记并定位，删除记录保留源文件且重试不复活软删除作品")
    void referencesAndRetainsOriginals() throws Exception {
        Path original = source.resolve("custom.png");
        byte[] before = Files.readAllBytes(original);
        var importer = importer(history);
        assertThat(importer.importFiles(request(List.of(original)))).isTrue();
        var row = database.getArtwork(42);
        assertThat(row.count()).isEqualTo(1);
        assertThat(row.xRestrict()).isEqualTo(1);
        assertThat(authorMapper.findById(84).name()).isEqualTo("Author");
        var locator = new top.sywyar.pixivdownload.core.asset.artwork.ArtworkFileLocator(
                database, settings, TestI18nBeans.appMessages(), null,
                org.mockito.Mockito.mock(top.sywyar.pixivdownload.core.asset.ArtworkMediaStore.class), externalFiles);
        assertThat(locator.resolveImageFile(row, 0)).isEqualTo(original.toFile());
        assertThat(importer.importFiles(request(List.of(original)))).isFalse();
        assertThat(locator.deleteArtworkFiles(row)).isTrue();
        database.markArtworkDeleted(42);
        assertThat(importer.importFiles(request(List.of(original)))).isFalse();
        assertThat(Files.readAllBytes(original)).isEqualTo(before);
        try (var stream = Files.list(output)) { assertThat(stream.count()).isZero(); }
    }
    @Test @DisplayName("元数据写入失败整笔回滚，不留文件引用且不触碰原文件")
    void rollsBackFailedRegistration() throws Exception {
        ArtworkDownloadHistory failing = new ArtworkDownloadHistory() {
            public long allocateRecordTime(long time) { return history.allocateRecordTime(time); }
            public void record(ArtworkDownloadCompletion completion) {
                history.record(completion); throw new IllegalStateException("forced rollback");
            }
        };
        assertThatThrownBy(() -> importer(failing).importFiles(request(List.of(Path.of("custom.png")))))
                .isInstanceOf(IllegalStateException.class).hasMessage("forced rollback");
        assertThat(database.getArtwork(42)).isNull();
        assertThat(authorMapper.findById(84)).isNull();
        assertThat(externalFiles.contains(WorkType.ARTWORK, 42)).isFalse();
        assertThat(source.resolve("custom.png")).exists();
        assertThat(events).extracting(DownloadEvent::phase).doesNotContain(DownloadEvent.Phase.COMPLETED);
    }
    @Test @DisplayName("越界、缺失文件和伪装媒体不能进入画廊")
    void rejectsInvalidFiles() throws Exception {
        for (String name : List.of("../escape.png", "missing.png")) {
            assertThatThrownBy(() -> importer(history).importFiles(request(List.of(Path.of(name)))))
                    .isInstanceOf(java.io.IOException.class);
        }
        Files.writeString(source.resolve("fake.png"), "not-an-image-at-all");
        assertThatThrownBy(() -> importer(history).importFiles(request(List.of(Path.of("fake.png")))))
                .isInstanceOf(java.io.IOException.class);
        assertThat(database.getArtwork(42)).isNull();
    }
    @Test @DisplayName("缺失源文件不回退猜名，新的托管下载不沿用旧只读引用")
    void missingAndReplacedRecord() throws Exception {
        Path original = source.resolve("custom.png");
        importer(history).importFiles(request(List.of(original)));
        Files.delete(original);
        assertThat(externalFiles.contains(WorkType.ARTWORK, 42)).isTrue();
        assertThat(externalFiles.file(WorkType.ARTWORK, 42, 0)).isNull();
        new org.springframework.jdbc.core.JdbcTemplate(dataSource).update("UPDATE artworks SET time = time + 1 WHERE artwork_id = 42");
        assertThat(externalFiles.contains(WorkType.ARTWORK, 42)).isFalse();
        assertThat(externalFiles.files(WorkType.ARTWORK, 42)).isEmpty();
    }

    private LocalWorkFileImportRequest parsed(Path root, Path file, Integer page) {
        return new LocalWorkFileImportRequest(WorkType.ARTWORK, 42, root, List.of(file),
                List.of(new LocalWorkFileImportRequest.Source(root, page, Map.of("artworkId", "42", "artworkTitle", "Hint"))));
    }

    @Test @DisplayName("解析导入允许缺页，重复幂等并跨目录补充，保留可靠元数据")
    void supplementsExternalFiles() throws Exception {
        var importer = importer(history);
        Path original = source.resolve("custom.png");
        assertThat(importer.importLocalFiles(parsed(source, original, 2))).isTrue();
        assertThat(externalFiles.hasCompletePageIdentity(42)).isFalse();
        assertThat(database.getArtwork(42).title()).isEmpty();
        assertThat(importer.importLocalFiles(parsed(source, original, 2))).isFalse();
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        jdbc.update("UPDATE artworks SET title='Reliable title' WHERE artwork_id=42");
        Path other = Files.createDirectory(temp.resolve("other"));
        Path second = Files.copy(original, other.resolve("second.png"));
        assertThat(importer.importLocalFiles(parsed(other, second, 0))).isTrue();
        assertThat(database.getArtwork(42).title()).isEqualTo("Reliable title");
        assertThat(database.getArtwork(42).count()).isEqualTo(2);
        assertThat(externalFiles.references(WorkType.ARTWORK, 42)).extracting(ExternalWorkFiles.Reference::sourcePage).containsExactly(2, 0);
        assertThat(Files.readAllBytes(original)).isEqualTo(Files.readAllBytes(second));
    }

    @Test @DisplayName("同一来源页的不同文件整笔拒绝，原有引用不变")
    void pageConflictRollsBack() throws Exception {
        var importer = importer(history);
        Path original = source.resolve("custom.png");
        importer.importLocalFiles(parsed(source, original, 0));
        Path second = Files.copy(original, source.resolve("second.png"));
        assertThatThrownBy(() -> importer.importLocalFiles(parsed(source, second, 0)))
                .hasMessage("IMPORT_PAGE_CONFLICT");
        assertThat(database.getArtwork(42).count()).isEqualTo(1);
        assertThat(externalFiles.files(WorkType.ARTWORK, 42)).containsExactly(original);
    }

    @Test @DisplayName("来源快照和完整页码同时存在才确认完整，未知页码不推断")
    void completenessNeedsAuthoritativeCount() throws Exception {
        Path original = source.resolve("custom.png");
        importer(history).importLocalFiles(parsed(source, original, 0));
        assertThat(externalFiles.hasCompletePageIdentity(42)).isFalse();
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        var raw = mapper.createObjectNode().put("id", "42").put("pageCount", 1);
        var snapshot = new top.sywyar.pixivdownload.core.metadata.sidecar.WorkMetaCurator(mapper)
                .curateArtwork(42, raw, null, "backfill").document().toString();
        new org.springframework.jdbc.core.JdbcTemplate(dataSource).update("UPDATE artworks SET metadata_json=? WHERE artwork_id=42", snapshot);
        assertThat(externalFiles.hasCompletePageIdentity(42)).isTrue();
        var legacy = (tools.jackson.databind.node.ObjectNode) mapper.readTree(snapshot);
        // 首版快照仍受支持；未知格式不能作为完整页数的依据。
        legacy.put("schemaVersion", 1);
        new org.springframework.jdbc.core.JdbcTemplate(dataSource).update("UPDATE artworks SET metadata_json=? WHERE artwork_id=42", legacy.toString());
        assertThat(externalFiles.hasCompletePageIdentity(42)).isTrue();
        legacy.put("schemaVersion", Integer.MAX_VALUE);
        new org.springframework.jdbc.core.JdbcTemplate(dataSource).update("UPDATE artworks SET metadata_json=? WHERE artwork_id=42", legacy.toString());
        assertThat(externalFiles.hasCompletePageIdentity(42)).isFalse();
        new org.springframework.jdbc.core.JdbcTemplate(dataSource).update("UPDATE artworks SET metadata_json=? WHERE artwork_id=42", snapshot);
        new org.springframework.jdbc.core.JdbcTemplate(dataSource).update("UPDATE external_work_files SET source_page=NULL WHERE work_id=42");
        assertThat(externalFiles.hasCompletePageIdentity(42)).isFalse();
    }
}
