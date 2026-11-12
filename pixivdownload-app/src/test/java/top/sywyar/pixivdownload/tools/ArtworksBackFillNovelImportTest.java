package top.sywyar.pixivdownload.tools;

import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import top.sywyar.pixivdownload.core.appconfig.DownloadConfig;
import top.sywyar.pixivdownload.core.artwork.download.ArtworkDownloadHistory;
import top.sywyar.pixivdownload.core.asset.ExternalWorkFiles;
import top.sywyar.pixivdownload.core.database.DatabaseAvailability;
import top.sywyar.pixivdownload.core.db.*;
import top.sywyar.pixivdownload.core.db.pathprefix.*;
import top.sywyar.pixivdownload.core.db.schema.DatabaseInitializer;
import top.sywyar.pixivdownload.core.download.LocalWorkFileImporter;
import top.sywyar.pixivdownload.core.work.importing.*;
import top.sywyar.pixivdownload.core.work.model.*;
import top.sywyar.pixivdownload.core.work.service.*;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.plugin.api.download.lifecycle.DownloadLifecycle;
import top.sywyar.pixivdownload.plugin.api.storage.PluginMainDatabase;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityOwner;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("小说本地导入、元数据回填与阅读")
class ArtworksBackFillNovelImportTest {
    @TempDir Path directory;

    @ParameterizedTest @ValueSource(strings = {"txt", "epub"})
    @DisplayName("真实小说 owner 登记正文后回填系列与资料，阅读仍使用本地正文且原文件不变")
    void readsImportedNovelAfterBackfill(String format) throws Exception {
        var target = DatabaseProviderTestFixtures.sqlite(directory.resolve("works.db"));
        try (var connection = target.openConnection()) { DatabaseInitializer.prepareOffline(connection, target); }
        Path root = Path.of("").toAbsolutePath();
        if (!Files.isDirectory(root.resolve("pixivdownload-app"))) root = root.getParent();
        Path classes = root.resolve("pixivdownload-plugin-novel/target/classes");
        assertThat(classes.resolve("top/sywyar/pixivdownload/novel/download/NovelFileImportHandler.class")).isRegularFile();
        String body = "[chapter:本地章节]\n正文 🌸\n[newpage]\n最后一段";
        Path original = source(format, body);
        byte[] before = Files.readAllBytes(original);
        try (var loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, getClass().getClassLoader());
             var pool = target.openPool(DatabasePoolProperties.MINIMUM_POOL_SIZE)) {
            var main = DatabaseProviderTestFixtures.main(pool);
            var settings = new DownloadConfig();
            settings.setRootFolder(directory.toString());
            var codec = new PathPrefixCodec(new PathPrefixMapper(main), settings, TestI18nBeans.appMessages());
            codec.init();
            try (var owner = main.forOwner("novel", List.of("pixiv-novels", "pixiv-tags"))) {
                Class<?> mapperType = loader.loadClass("top.sywyar.pixivdownload.novel.db.NovelMapper");
                Class<?> databaseType = loader.loadClass("top.sywyar.pixivdownload.novel.db.NovelDatabase");
                Object mapper = loader.loadClass("top.sywyar.pixivdownload.novel.db.RelationalNovelMapper")
                        .getConstructor(PluginMainDatabase.class).newInstance(owner);
                Object novels = databaseType.getConstructor(mapperType, WorkTagCatalog.class, StoredPathCodec.class, DatabaseAvailability.class)
                        .newInstance(mapper, mock(WorkTagCatalog.class), codec, (DatabaseAvailability) () -> true);
                call(novels, "init");
                var names = mock(WorkFileNameCatalog.class);
                var jdbc = new JdbcTemplate(pool);
                jdbc.update("INSERT INTO file_name_templates(id,template) VALUES(1,'{id}_p{page}')");
                when(names.getOrCreateTemplateId(anyString())).thenReturn(1L);
                var handler = (WorkFileImportHandler) loader.loadClass("top.sywyar.pixivdownload.novel.download.NovelFileImportHandler")
                        .getConstructor(databaseType, WorkFileNameCatalog.class).newInstance(novels, names);
                var external = new ExternalWorkFiles(main, codec, null);
                var importer = new LocalWorkFileImporter(mock(ArtworkDownloadHistory.class), mock(PixivDatabase.class),
                        external, new DataSourceTransactionManager(pool), mock(DownloadLifecycle.class), mock(AuthorObservationService.class));
                importer.register(new ExternalCapabilityOwner("novel", "novel", 1, 1), Map.of(WorkType.NOVEL, handler));
                var request = new LocalWorkFileImportRequest(WorkType.NOVEL, 42, directory, List.of(original.getFileName()));
                assertThat(importer.importLocalFiles(request)).isTrue();
                assertThat(external.file(WorkType.NOVEL, 42, 0)).isEqualTo(original);
                String stored = jdbc.queryForObject("SELECT raw_content FROM novels WHERE novel_id=42", String.class);
                assertThat(stored).contains("[chapter:本地章节]", "正文 🌸", "[newpage]", "最后一段");
                assertThat(jdbc.queryForObject("SELECT metadata_json FROM novels WHERE novel_id=42", String.class)).isNull();
                var result = ArtworksBackFillPixivClient.parseResponse(200, """
                        {"body":{"id":"42","title":"Filled title","userId":"7","userName":"Author",
                        "xRestrict":0,"aiType":1,"description":"Description","seriesNavData":{"seriesId":"500","order":3,"title":"Series"},
                        "uploadDate":"2020-02-03T12:00:00+09:00","isOriginal":true,
                        "tags":{"tags":[{"tag":"fixture"}]},"content":"Different upstream body"}}
                        """, JsonMapper.builderWithJackson2Defaults().build(), WorkType.NOVEL, 42);
                try (var backfill = ArtworksBackFillDatabase.open(target)) {
                    var candidate = backfill.currentCandidate(WorkType.NOVEL, 42);
                    backfill.applyUpdates(candidate, result, true, true, true, true, true, true, true);
                    var filled = backfill.currentCandidate(WorkType.NOVEL, 42);
                    assertThat(filled.hasMissing()).as("%s", filled).isFalse();
                }
                assertThat(jdbc.queryForObject("SELECT series_id FROM novels WHERE novel_id=42", Long.class)).isEqualTo(500L);
                assertThat(jdbc.queryForObject("SELECT series_order FROM novels WHERE novel_id=42", Long.class)).isEqualTo(3L);
                assertThat(jdbc.queryForObject("SELECT raw_content FROM novels WHERE novel_id=42", String.class)).isEqualTo(stored);
                assertThat(jdbc.queryForObject("SELECT time FROM novels WHERE novel_id=42", Long.class)).isPositive();
                var controllerType = loader.loadClass("top.sywyar.pixivdownload.novelgallery.controller.NovelGalleryController");
                Object controller = controllerType.getConstructors()[0].newInstance(null, null, null, novels,
                        mock(WorkAssetService.class), mock(WorkVisibilityService.class), TestI18nBeans.appMessages());
                var response = (ResponseEntity<?>) call(controller, "getNovelContent", 42L, null, null, WorkVisibilityScope.unrestricted());
                assertThat(response.getStatusCode().value()).isEqualTo(200);
                assertThat((String) call(response.getBody(), "content")).isEqualTo(stored);
                assertThat(((Set<?>) call(novels, "searchNovelContentIds", "正文")).contains(42L)).isTrue();
                assertThat(importer.importLocalFiles(request)).isFalse();
                Path broken = Files.writeString(directory.resolve("broken.txt"), "   ");
                assertThatThrownBy(() -> importer.importLocalFiles(new LocalWorkFileImportRequest(
                        WorkType.NOVEL, 43, directory, List.of(broken)))).isInstanceOf(java.io.IOException.class);
                assertThat(call(novels, "getNovel", 43L)).isNull();
                assertThat(external.contains(WorkType.NOVEL, 43)).isFalse();
            }
        }
        assertThat(Files.readAllBytes(original)).isEqualTo(before);
    }

    private Object call(Object receiver, String method, Object... args) {
        return ReflectionTestUtils.invokeMethod(receiver, method, args);
    }

    private Path source(String format, String body) throws Exception {
        Path file = directory.resolve("novel." + format);
        if (format.equals("txt")) return Files.writeString(file, body, StandardCharsets.UTF_8);
        try (var zip = new ZipOutputStream(Files.newOutputStream(file), StandardCharsets.UTF_8)) {
            entry(zip, "mimetype", "application/epub+zip");
            entry(zip, "META-INF/container.xml", """
                    <container><rootfiles><rootfile full-path="book.opf"
                    media-type="application/oebps-package+xml"/></rootfiles></container>
                    """);
            entry(zip, "book.opf", """
                    <package><manifest><item id="body" href="body.xhtml" media-type="application/xhtml+xml"/></manifest>
                    <spine><itemref idref="body"/></spine></package>
                    """);
            entry(zip, "body.xhtml", "<html><body><p>" + body.replace("\n", "</p><p>") + "</p></body></html>");
        }
        return file;
    }

    private void entry(ZipOutputStream zip, String name, String text) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(text.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
