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

    @Test void recoveryCommitsBeforeACompetingImportCanAdmitItsPage() throws Exception {
        Path directory = Files.createDirectory(temp.resolve("recover"));
        Path page = Files.writeString(directory.resolve("page.jpg"), "page");
        var registering = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        var attempting = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var recovery = executor.submit(() -> directories.recoverArtwork(42, directory, () -> {
                registering.countDown();
                try { return finish.await(10, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
            }));
            try {
                assertThat(registering.await(5, TimeUnit.SECONDS)).isTrue();
                var racingImport = executor.submit(() -> {
                    attempting.countDown();
                    directories.requireImportable(List.of(page));
                });
                assertThat(attempting.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> racingImport.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                finish.countDown();
                assertThat(recovery.get(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> racingImport.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(DirectoryTemplateException.class);
                assertThat(directories.owns(WorkType.ARTWORK, 42, directory)).isTrue();
            } finally { finish.countDown(); }
        }
    }

    @Test void failedRecoveryClaimRollsBackTheWorkRecordAndLeavesItsFile() throws Exception {
        Path directory = Files.createDirectory(temp.resolve("42"));
        Path page = Files.writeString(directory.resolve("42_p0.jpg"), "page");
        jdbc.execute("CREATE TRIGGER reject_claim BEFORE INSERT ON download_directories BEGIN SELECT RAISE(ABORT, 'conflict'); END");
        assertThatThrownBy(() -> directories.recoverArtwork(42, directory, () -> jdbc.update(
                "INSERT INTO artworks(artwork_id,title,folder,count,extensions,time) VALUES(42,'work',?,1,'jpg',1)",
                directory.toString()) == 1)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM artworks WHERE artwork_id=42", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM download_directories", Integer.class)).isZero();
        assertThat(Files.readString(page)).isEqualTo("page");
    }

    @Test void recursiveDeletionProtectsUnfinishedDownloadsAndEmptySubtrees() throws Exception {
        Path novel = Files.createDirectory(temp.resolve("novel-7"));
        Path partial = novel.resolve("partial");
        directories.prepare(WorkType.ARTWORK, 42, partial);
        assertThat(directories.foreignNovelDirectories(7, novel)).containsExactly(partial);
        assertThatThrownBy(() -> directories.reserveNovelDeletion(7, novel)).isInstanceOf(DirectoryTemplateException.class);
        // A work with an active record can also own a different, unfinished layout.
        jdbc.update("INSERT INTO artworks(artwork_id,title,folder,count,extensions,time) VALUES(42,'work',?,1,'png',4)",
                temp.resolve("other").toString());
        jdbc.update("INSERT INTO artwork_partial_downloads(artwork_id,folder,total_pages,template,max_length,output_settings) VALUES(42,?,2,'x',40,'')",
                partial.toString());
        assertThat(directories.foreignNovelDirectories(7, novel)).containsExactly(partial);
        jdbc.update("DELETE FROM artwork_partial_downloads WHERE artwork_id=42");
        Path sibling = temp.resolve("novel-71");
        try (var reservation = directories.reserveNovelDeletion(7, novel);
             var independent = directories.reserveNovelDeletion(71, sibling)) {
            assertThatThrownBy(() -> directories.reserveDeletion(List.of(partial.resolve("new.png")))).isInstanceOf(DirectoryTemplateException.class);
            assertThatThrownBy(() -> directories.prepare(WorkType.ARTWORK, 43, novel.resolve("new")))
                    .isInstanceOf(DirectoryTemplateException.class);
            assertThat(Files.exists(novel.resolve("new"))).isFalse();
            assertThatThrownBy(() -> directories.requireDirectoryAvailable(temp)).isInstanceOf(DirectoryTemplateException.class);
            directories.requireDirectoryAvailable(temp.resolve("novel-70"));
            reservation.close();
            directories.requireDirectoryAvailable(partial);
            assertThatThrownBy(() -> directories.requireDirectoryAvailable(sibling)).isInstanceOf(DirectoryTemplateException.class);
        }
    }

    @Test void exactDeletionReservationsProtectOldRecordsWhileUnrelatedImportsCanProceed() throws Exception {
        Path stored = Files.writeString(temp.resolve("protected.jpg"), "import");
        codec.getOrCreatePrefixId(temp.toString());
        jdbc.update("INSERT INTO external_work_files(reference_id,work_type,work_id,page,root_path,file_path,record_time) VALUES('r','ARTWORK',7,0,?,?,1)",
                codec.encode(temp.toString()), codec.encode(stored.toString()));
        assertThatThrownBy(() -> directories.reserveDeletion(List.of(stored))).isInstanceOf(DirectoryTemplateException.class);
        Path first = Files.writeString(temp.resolve("first.jpg"), "first");
        Path other = Files.writeString(temp.resolve("other.jpg"), "other");
        var lease = directories.reserveDeletion(List.of(first));
        try (lease) {
            try (var independent = directories.reserveDeletion(List.of(other))) {
                assertThatThrownBy(() -> directories.reserveDeletion(List.of(first))).isInstanceOf(DirectoryTemplateException.class);
                assertThatThrownBy(() -> directories.requireImportable(List.of(other))).isInstanceOf(DirectoryTemplateException.class);
            }
            try (var executor = Executors.newSingleThreadExecutor()) {
                executor.submit(() -> directories.requireImportable(List.of(other))).get(5, TimeUnit.SECONDS);
            }
            assertThatThrownBy(() -> directories.requireImportable(List.of(first))).isInstanceOf(DirectoryTemplateException.class);
        }
        try (var next = directories.reserveDeletion(List.of(first))) {
            lease.close();
            assertThatThrownBy(() -> directories.requireImportable(List.of(first))).isInstanceOf(DirectoryTemplateException.class);
        }
        directories.requireImportable(List.of(first));
        assertThat(Files.readString(stored)).isEqualTo("import");
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
