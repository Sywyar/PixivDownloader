package top.sywyar.pixivdownload.core.download;

import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import top.sywyar.pixivdownload.core.appconfig.DownloadConfig;
import top.sywyar.pixivdownload.core.artwork.download.*;
import top.sywyar.pixivdownload.core.work.importing.*;
import top.sywyar.pixivdownload.core.work.model.WorkType;
import top.sywyar.pixivdownload.core.asset.ExternalWorkFiles;
import top.sywyar.pixivdownload.core.db.*;
import top.sywyar.pixivdownload.core.db.pathprefix.*;
import top.sywyar.pixivdownload.core.db.schema.DatabaseInitializer;
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
    private final DownloadLifecycle lifecycle = new DownloadLifecycle() {
        public void checkAdmission(DownloadAttempt attempt) {}
        public void publish(DownloadEvent event) { events.add(event); }
    };

    @BeforeEach void setUp() throws Exception {
        source = Files.createDirectory(temp.resolve("source"));
        output = Files.createDirectory(temp.resolve("output"));
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", source.resolve("custom.png").toFile());
        dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        var config = new Configuration(new Environment("test", new SpringManagedTransactionFactory(), dataSource));
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(PixivMapper.class);
        config.addMapper(top.sywyar.pixivdownload.author.AuthorMapper.class);
        config.addMapper(PathPrefixMapper.class);
        var session = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));
        var registry = DatabaseSchemaRegistry.forBuiltInPlugins();
        var initializer = new DatabaseInitializer(new org.springframework.jdbc.core.JdbcTemplate(dataSource),
                registry.contributions(), registry.mergedSchema(), TestI18nBeans.appMessages(), event -> {});
        initializer.initialize();
        settings = new DownloadConfig();
        settings.setRootFolder(output.toString());
        var codec = new PathPrefixCodec(session.getMapper(PathPrefixMapper.class), settings, TestI18nBeans.appMessages());
        codec.init();
        database = new PixivDatabase(session.getMapper(PixivMapper.class), TestI18nBeans.appMessages(), codec, initializer);
        database.init();
        history = new ArtworkDownloadHistoryAdapter(database);
        externalFiles = new ExternalWorkFiles(dataSource, codec, initializer);
        authorMapper = session.getMapper(top.sywyar.pixivdownload.author.AuthorMapper.class);
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
}
