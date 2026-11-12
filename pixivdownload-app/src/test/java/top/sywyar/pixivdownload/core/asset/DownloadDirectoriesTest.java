package top.sywyar.pixivdownload.core.asset;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import top.sywyar.pixivdownload.core.appconfig.DownloadConfig;
import top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures;
import top.sywyar.pixivdownload.core.db.pathprefix.*;
import top.sywyar.pixivdownload.core.work.directory.DirectoryTemplateException;
import top.sywyar.pixivdownload.core.work.model.WorkType;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.plugin.registry.schema.DatabaseSchemaRegistry;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@DisplayName("下载目录归属与既有共享目录隔离")
class DownloadDirectoriesTest {
    @TempDir Path temp;
    private SingleConnectionDataSource source;
    private DownloadDirectories directories;
    private PathPrefixCodec codec;
    private JdbcTemplate jdbc;

    @BeforeEach void setup() {
        source = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        jdbc = new JdbcTemplate(source);
        var registry = DatabaseSchemaRegistry.forBuiltInPlugins();
        var initializer = DatabaseProviderTestFixtures.initializer(jdbc, registry.contributions(),
                registry.mergedSchema(), TestI18nBeans.appMessages(), event -> {});
        initializer.initialize();
        var config = new DownloadConfig();
        config.setRootFolder(temp + "/{artwork_id}");
        codec = new PathPrefixCodec(new PathPrefixMapper(DatabaseProviderTestFixtures.main(source)), config, TestI18nBeans.appMessages());
        codec.init();
        directories = new DownloadDirectories(DatabaseProviderTestFixtures.main(source), codec, initializer);
    }

    @AfterEach void close() { source.destroy(); }

    @Test @DisplayName("相同数字的插画和小说独立登记，跨类型及嵌套冲突拒绝")
    void rejectsOtherIdentityAndNesting() throws Exception {
        Path artwork = temp.resolve("42");
        directories.prepare(WorkType.ARTWORK, 42, artwork);
        Files.writeString(artwork.resolve("page.jpg"), "retained");
        assertThatThrownBy(() -> directories.prepare(WorkType.NOVEL, 42, artwork))
                .isInstanceOf(DirectoryTemplateException.class).hasMessageContaining("occupied");
        assertThatThrownBy(() -> directories.prepare(WorkType.ARTWORK, 43, artwork.resolve("child")))
                .isInstanceOf(DirectoryTemplateException.class);
        assertThatThrownBy(() -> directories.prepare(WorkType.NOVEL, 43, temp))
                .isInstanceOf(DirectoryTemplateException.class);
        directories.prepare(WorkType.NOVEL, 42, temp.resolve("novel-42"));
        assertThat(Files.readString(artwork.resolve("page.jpg"))).isEqualTo("retained");
        assertThat(directories.owns(WorkType.ARTWORK, 42, artwork)).isTrue();
        assertThat(directories.owns(WorkType.NOVEL, 42, artwork)).isFalse();
    }

    @Test @DisplayName("部分下载重启后保留归属，未知非空目录不得接管")
    void retainsClaimAcrossRestart() throws Exception {
        Path output = temp.resolve("custom/42-title");
        directories.prepare(WorkType.ARTWORK, 42, output);
        Files.writeString(output.resolve("partial.part"), "partial");
        var restarted = new DownloadDirectories(DatabaseProviderTestFixtures.main(source), codec, null);
        restarted.prepare(WorkType.ARTWORK, 42, output);
        assertThatThrownBy(() -> restarted.prepare(WorkType.ARTWORK, 43, output)).isInstanceOf(DirectoryTemplateException.class);
        Path unknown = Files.createDirectory(temp.resolve("unknown"));
        Files.writeString(unknown.resolve("keep.txt"), "keep");
        assertThatThrownBy(() -> restarted.prepare(WorkType.NOVEL, 7, unknown)).isInstanceOf(DirectoryTemplateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM download_directories", Integer.class)).isEqualTo(1);
    }

    @Test @DisplayName("同一路径并发申请只允许一个身份获得归属")
    void serializesClaimsBeforeCreation() throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Callable<Boolean>> calls = List.of(
                    () -> attempt(start, 1), () -> attempt(start, 2));
            var futures = calls.stream().map(executor::submit).toList();
            start.countDown();
            int accepted = 0;
            for (var future : futures) if (future.get(10, TimeUnit.SECONDS)) accepted++;
            assertThat(accepted).isEqualTo(1);
        }
    }

    private boolean attempt(CountDownLatch start, long id) throws Exception {
        start.await();
        try { directories.prepare(WorkType.ARTWORK, id, temp.resolve("same")); return true; }
        catch (DirectoryTemplateException expected) { return false; }
    }

    @Test @DisplayName("旧共享 folder 行保持合法，但新下载不能接管或嵌套占用")
    void preservesLegacySharedRows() throws Exception {
        Path shared = Files.createDirectory(temp.resolve("shared"));
        for (long id : List.of(1L, 2L)) jdbc.update(
                "INSERT INTO artworks(artwork_id,title,folder,count,extensions,time) VALUES(?,?,?,1,'jpg',?)",
                id, "title", shared.toString(), id);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM artworks", Integer.class)).isEqualTo(2);
        assertThatThrownBy(() -> directories.prepare(WorkType.ARTWORK, 1, shared)).isInstanceOf(DirectoryTemplateException.class);
        assertThatThrownBy(() -> directories.prepare(WorkType.ARTWORK, 3, shared.resolve("3"))).isInstanceOf(DirectoryTemplateException.class);
        directories.prepare(WorkType.ARTWORK, 3, temp.resolve("3"));
        assertThatThrownBy(() -> directories.requireImportable(List.of(temp.resolve("3/file.jpg"))))
                .isInstanceOf(DirectoryTemplateException.class);
        assertThatCode(() -> directories.requireImportable(List.of(shared.resolve("file.jpg")))).doesNotThrowAnyException();
    }

    @Test @DisplayName("外部只读引用所在根允许共享，实际引用文件不能被下载覆盖")
    void protectsExternalFilesWithoutOwningWholeImportRoot() throws Exception {
        Path file = Files.writeString(temp.resolve("external.jpg"), "original");
        jdbc.update("INSERT INTO external_work_files(reference_id,work_type,work_id,page,root_path,file_path,record_time) VALUES('r','ARTWORK',7,0,?,?,1)",
                temp.toString(), file.toString());
        assertThatThrownBy(() -> directories.prepare(WorkType.ARTWORK, 8, temp)).isInstanceOf(DirectoryTemplateException.class);
        directories.prepare(WorkType.ARTWORK, 8, temp.resolve("8"));
        assertThat(Files.readString(file)).isEqualTo("original");
    }
}
