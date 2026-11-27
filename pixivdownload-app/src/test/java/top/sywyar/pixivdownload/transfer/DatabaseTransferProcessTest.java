package top.sywyar.pixivdownload.transfer;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures;
import top.sywyar.pixivdownload.core.db.pathprefix.PathPrefix;
import top.sywyar.pixivdownload.core.db.pathprefix.PathPrefixCodec;
import top.sywyar.pixivdownload.core.db.schema.CoreMigrationSchema;
import top.sywyar.pixivdownload.plugin.registry.schema.DatabaseSchemaRegistry;
import top.sywyar.pixivdownload.migration.MigrationProcessHarness;
import top.sywyar.pixivdownload.core.work.model.WorkType;
import top.sywyar.pixivdownload.core.importing.LocalImportService;
import top.sywyar.pixivdownload.plugin.api.gui.importing.LocalArtworkImports;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@DisplayName("数据库归档的真实导出、预览与停机替换")
class DatabaseTransferProcessTest {
    @TempDir Path root;
    private final ObjectMapper json = JsonMapper.builderWithJackson2Defaults().build();

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    @DisplayName("预览保持目标不变，下一进程替换主私库及选中配置并保留旧库备份")
    void replacesDatabaseAfterProcessHandoff(boolean privateStore) throws Exception {
        Path source = setup("source", "归档 🌸", 8111), target = setup("target", "目标旧数据", 8222);
        scheduledTask(source);
        Files.createDirectories(source.resolve("state/gui"));
        Files.writeString(source.resolve("state/gui/window-state.json"), "{\"width\":1280,\"height\":900,\"maximized\":false}");
        Files.createDirectories(source.resolve("state/neutral-migration"));
        Files.writeString(source.resolve("state/neutral-migration/preferences.txt"), "owner-declared state");
        Files.createDirectories(target.resolve("state/gui"));
        Files.writeString(target.resolve("state/gui/window-state.json"), "{\"width\":800,\"height\":600,\"maximized\":false}");
        String targetIdentity = "";
        if (privateStore) {
            privateStore(source, 100);
            targetIdentity = privateStore(target, 200);
        }
        Path archive = root.resolve("environment.pdbackup");
        assertRun(source, "export", archive);
        byte[] configBefore = Files.readAllBytes(target.resolve("config/config.yaml"));
        assertRun(target, "preview", archive);
        var preview = json.readTree(target.resolve("preview.json").toFile());
        assertThat(preview.path("databaseFailure").asString()).isEmpty();
        assertThat(preview.path("databases")).hasSize(privateStore ? 2 : 1);
        assertThat(preview.path("databases").get(0).path("replacesExisting").asBoolean()).isTrue();
        assertThat(Files.readAllBytes(target.resolve("config/config.yaml"))).isEqualTo(configBefore);
        assertThat(target.resolve("data/environment-transfers")).doesNotExist();
        if (!privateStore) assertThat(target.resolve("data/install_identity.txt")).doesNotExist();
        else assertThat(Files.readString(target.resolve("data/install_identity.txt"))).isEqualTo(targetIdentity);
        assertThat(target.resolve("config/credentials")).doesNotExist();
        assertThat(tag(target.resolve("main.db"))).isEqualTo("目标旧数据");
        assertRun(target, "prepare", archive);
        assertThat(tag(target.resolve("main.db"))).isEqualTo("目标旧数据");
        assertRun(target, "run", archive);
        var job = json.readTree(target.resolve("data/environment-transfers/pending.json").toFile());
        assertThat(job.path("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(tag(target.resolve("main.db"))).isEqualTo("归档 🌸");
        assertThat(json.readTree(target.resolve("state/gui/window-state.json").toFile()).path("width").intValue()).isEqualTo(1280);
        assertThat(Files.readString(target.resolve("state/neutral-migration/preferences.txt"))).isEqualTo("owner-declared state");
        assertThat(tag(target.resolve("data/environment-transfers").resolve(job.path("id").asString()).resolve("database-backups/host.backup")))
                .isEqualTo("目标旧数据");
        assertThat(Files.readString(target.resolve("config/config.yaml"))).contains("server.port: 8111").contains(target.resolve("main.db").toString().replace("\\", "\\\\"));
        assertThat(tag(source.resolve("main.db"))).isEqualTo("归档 🌸");
        try (var connection = DatabaseProviderTestFixtures.openExisting(target.resolve("main.db")); var sql = connection.createStatement();
             var row = sql.executeQuery("SELECT enabled,run_state,run_claim_token,last_outcome FROM scheduled_tasks")) {
            assertThat(row.next()).isTrue(); assertThat(row.getLong(1)).isZero();
            assertThat(row.getString(2)).isNull(); assertThat(row.getString(3)).isNull();
            assertThat(row.getString(4)).isEqualTo("INTERRUPTED");
        }
        try (var connection = DatabaseProviderTestFixtures.openExisting(source.resolve("main.db")); var sql = connection.createStatement();
             var row = sql.executeQuery("SELECT enabled,run_claim_token FROM scheduled_tasks")) {
            assertThat(row.next()).isTrue(); assertThat(row.getLong(1)).isEqualTo(1);
            assertThat(row.getString(2)).isEqualTo("old-process-claim");
        }
        if (privateStore) {
            assertThat(Files.readString(target.resolve("data/install_identity.txt"))).isEqualTo(targetIdentity);
            assertThat(importedAt(target.resolve("data/pixiv-batch-downloader-import/plugin.db"))).isEqualTo(100);
            assertThat(importedAt(target.resolve("data/environment-transfers").resolve(job.path("id").asString())
                    .resolve("database-backups/pixiv-batch-downloader-import.backup"))).isEqualTo(200);
        }
        assertRun(target, "run", archive);
    }

    @Test @DisplayName("确认后目标变化拒绝替换，失败不阻断原数据继续使用")
    void targetChangesBeforeRestore() throws Exception {
        Path source = setup("source", "source", 8111), target = setup("target", "old", 8222);
        Path archive = root.resolve("environment.pdbackup");
        assertRun(source, "export", archive);
        assertRun(target, "prepare", archive);
        try (var connection = DatabaseProviderTestFixtures.openExisting(target.resolve("main.db")); var sql = connection.createStatement()) {
            sql.execute("UPDATE tags SET name='concurrent edit'");
        }
        assertRun(target, "run", archive);
        var job = json.readTree(target.resolve("data/environment-transfers/pending.json").toFile());
        assertThat(job.path("status").asString()).isEqualTo("FAILED");
        assertThat(tag(target.resolve("main.db"))).isEqualTo("concurrent edit");
        assertThat(Files.readString(target.resolve("config/config.yaml"))).contains("server.port: 8222");
    }

    @Test @DisplayName("提交中断的耐久报告要求人工核对，不能误报全部回滚或继续业务启动")
    void interruptedCommitRemainsRecoveryRequired() throws Exception {
        Path source = setup("source", "source", 8111), target = setup("target", "old", 8222);
        Path archive = root.resolve("environment.pdbackup");
        assertRun(source, "export", archive);
        assertRun(target, "prepare", archive);
        Path journal = target.resolve("data/environment-transfers/pending.json");
        var job = (tools.jackson.databind.node.ObjectNode) json.readTree(journal.toFile());
        job.put("status", "APPLYING");
        Files.write(journal, json.writeValueAsBytes(job));
        var store = new top.sywyar.pixivdownload.migration.database.ProviderDatabaseMigration.StoreReport("archive", "fixture", "fixture", "fixture",
                top.sywyar.pixivdownload.migration.database.ProviderDatabaseMigration.Phase.COMMITTING, "", 0, null);
        var report = new top.sywyar.pixivdownload.migration.database.ProviderDatabaseMigration.Report(1, "IN_PROGRESS",
                Map.of("main", store), "private-row-content-must-not-be-logged", List.of());
        Files.write(target.resolve("data/environment-transfers").resolve(job.path("id").asString()).resolve("database-report.json"),
                json.writeValueAsBytes(report));
        assertThat(MigrationProcessHarness.run(target, DatabaseTransferTestEntry.class, "interrupted", "run")).isNotZero();
        assertThat(json.readTree(journal.toFile()).path("status").asString()).isEqualTo("RECOVERY_REQUIRED");
        assertThat(Files.readString(target.resolve("interrupted.log"))).doesNotContain("private-row-content-must-not-be-logged");
        assertThat(tag(target.resolve("main.db"))).isEqualTo("old");
        assertThat(Files.readString(target.resolve("config/config.yaml"))).contains("server.port: 8222");
    }

    @Test @DisplayName("已确认的配置被外部修改时，在任何数据库提交前中止")
    void changedFilePreventsDatabaseCommit() throws Exception {
        Path source = setup("source", "source", 8111), target = setup("target", "old", 8222);
        Path archive = root.resolve("environment.pdbackup");
        assertRun(source, "export", archive);
        assertRun(target, "prepare", archive);
        Files.writeString(target.resolve("config/config.yaml"), "# changed after preview\n", StandardOpenOption.APPEND);
        byte[] before = Files.readAllBytes(target.resolve("main.db"));
        assertRun(target, "run", archive);
        var job = json.readTree(target.resolve("data/environment-transfers/pending.json").toFile());
        assertThat(job.path("status").asString()).isEqualTo("FAILED");
        assertThat(job.path("failureCode").asString()).isEqualTo("transfer.target-changed");
        assertThat(Files.readString(target.resolve("run.log")).lines().filter(line -> line.contains(job.path("id").asString())))
                .anyMatch(line -> line.contains("transfer.target-changed"));
        assertThat(Files.readAllBytes(target.resolve("main.db"))).isEqualTo(before);
        assertThat(tag(target.resolve("main.db"))).isEqualTo("old");
        assertThat(Files.readString(target.resolve("config/config.yaml"))).contains("# changed after preview");
    }

    private String privateStore(Path directory, long time) throws Exception {
        Path data = Files.createDirectories(directory.resolve("data"));
        String identity = UUID.randomUUID().toString();
        Files.writeString(data.resolve("install_identity.txt"), identity);
        Path file = Files.createDirectory(data.resolve("pixiv-batch-downloader-import")).resolve("plugin.db");
        try (var connection = DatabaseProviderTestFixtures.openConnection(file); var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE imported_works(work_type TEXT NOT NULL,work_id INTEGER NOT NULL,imported_at INTEGER NOT NULL,PRIMARY KEY(work_type,work_id))");
            sql.execute("INSERT INTO imported_works VALUES('NOVEL',9007199254740993," + time + ")");
        }
        return identity;
    }

    @Test @DisplayName("跨进程恢复映射真实路径列，符号根冻结且普通文本与源数据库不变")
    void mapsDatabasePathsAcrossProcesses() throws Exception {
        verifyDatabasePathRestore(false);
    }

    @Test void readsRestoredArtworkThroughTheGallery() throws Exception {
        Path archive = root.resolve("gallery.pdbackup");
        boolean windows = java.io.File.separatorChar == '\\';
        exportGallery(archive, windows);
        restoreGallery(archive, windows, "plain");
        if (!windows) {
            restoreGallery(archive, false, "link");
            restoreGallery(archive, false, "child-link");
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"pair", "shared", "single", "outside", "split", "nested", "alias", "long"})
    void checksPageMappingsAgainstTheDestinationDirectory(String scenario) throws Exception {
        Path archive = root.resolve("pages.pdbackup");
        // Archive two distinct source spellings even when this test host cannot create both.
        exportPageNames(archive, scenario, true);
        if (scenario.equals("alias")) Files.move(root.resolve("source"), root.resolve("disconnected-source"));
        restorePageNames(archive, scenario, false);
    }

    @Test void restoresSharedReferencesFromAnArchiveWithoutPageIdentities() throws Exception {
        Path original = root.resolve("original.pdbackup"), legacy = root.resolve("legacy.pdbackup");
        exportPageNames(original, "shared", false);
        var archives = new top.sywyar.pixivdownload.transfer.archive.TransferArchive();
        char[] password = "fixture archive secret".toCharArray();
        try {
            var preview = archives.inspect(original, password);
            Path staged = root.resolve("entries");
            archives.extract(original, password, staged, preview);
            var entries = new ArrayList<top.sywyar.pixivdownload.transfer.archive.TransferArchive.Source>();
            for (int i = 0; i < preview.items().size(); i++) {
                var descriptor = preview.items().get(i).descriptor();
                if (descriptor.path().equals(SourcePageIdentities.ENTRY)) continue;
                Path entry = staged.resolve(Integer.toString(i));
                entries.add(new top.sywyar.pixivdownload.transfer.archive.TransferArchive.Source(descriptor, () -> Files.newInputStream(entry)));
            }
            assertThat(entries).hasSize(preview.items().size() - 1);
            archives.write(legacy, password, preview.manifest().applicationVersion(), entries);
        } finally { Arrays.fill(password, '\0'); }
        restorePageNames(legacy, "shared", false);
    }

    void exportPageNames(Path archive, String scenario, boolean simulatedPosix) throws Exception {
        Path source = setup("source", "source", 8111);
        Path library = Files.createDirectory(source.resolve("library"));
        Path pictures = Files.createDirectory((scenario.equals("outside") ? source : library).resolve("pictures"));
        Files.writeString(source.resolve("config/config.yaml"), "download.root-folder: " + json.writeValueAsString(library + "/{artwork_id}") + "\n",
                StandardOpenOption.APPEND);
        var endpoint = DatabaseProviderTestFixtures.sqlite(source.resolve("main.db"));
        try (var conn = endpoint.openConnection()) {
            endpoint.migrationEndpoint().target().applySchema(conn, CoreMigrationSchema.create(DatabaseSchemaRegistry.forBuiltInPlugins(), true), true);
            try (var insert = conn.prepareStatement("INSERT INTO path_prefixes(id,path) VALUES(7,?)")) {
                insert.setString(1, library.resolve("pictures").toString());
                insert.executeUpdate();
            }
        }
        String upperName = scenario.equals("long") ? "A".repeat(220) + ".jpg" : "Cover.jpg";
        String lowerName = upperName.toLowerCase(Locale.ROOT);
        Path cover = Files.write(pictures.resolve(upperName), new byte[]{(byte)0xff,(byte)0xd8,(byte)0xff,11});
        var inputs = new ArrayList<>(List.of(cover));
        boolean canCreatePair = Files.notExists(pictures.resolve(lowerName));
        boolean pair = scenario.equals("pair") || scenario.equals("long");
        if (scenario.equals("alias")) Assumptions.assumeFalse(canCreatePair, "Requires case-insensitive source");
        if (scenario.equals("long") && !simulatedPosix) Assumptions.assumeTrue(canCreatePair, "Requires two distinct source pages");
        if (pair && canCreatePair)
            inputs.add(Files.write(pictures.resolve(lowerName), new byte[]{(byte)0xff,(byte)0xd8,(byte)0xff,22}));
        var data = new org.springframework.jdbc.datasource.SingleConnectionDataSource("jdbc:sqlite:" + source.resolve("main.db"), true);
        try {
            var db = DatabaseProviderTestFixtures.main(data);
            var config = new top.sywyar.pixivdownload.core.appconfig.DownloadConfig();
            config.setRootFolder(library + "/{artwork_id}");
            var messages = top.sywyar.pixivdownload.i18n.TestI18nBeans.appMessages();
            var codec = new PathPrefixCodec(new top.sywyar.pixivdownload.core.db.pathprefix.PathPrefixMapper(db), config, messages);
            codec.init();
            var sql = new org.springframework.jdbc.core.JdbcTemplate(data);
            var registry = DatabaseSchemaRegistry.forBuiltInPlugins();
            var initializer = DatabaseProviderTestFixtures.initializer(sql, registry.contributions(), registry.mergedSchema(), messages, event -> {});
            var database = new top.sywyar.pixivdownload.core.db.PixivDatabase(new top.sywyar.pixivdownload.core.db.PixivMapper(db), messages, codec, initializer);
            database.init();
            var external = new top.sywyar.pixivdownload.core.asset.ExternalWorkFiles(db, codec, initializer);
            var importer = new top.sywyar.pixivdownload.core.download.LocalWorkFileImporter(
                    new top.sywyar.pixivdownload.core.download.ArtworkDownloadHistoryAdapter(database), database, external,
                    new org.springframework.jdbc.datasource.DataSourceTransactionManager(data),
                    new top.sywyar.pixivdownload.core.download.DownloadLifecycleRegistry(), (id, name) -> {},
                    new top.sywyar.pixivdownload.core.asset.DownloadDirectories(db, codec, initializer));
            assertThat(importer.importLocalFiles(new top.sywyar.pixivdownload.core.work.importing.LocalWorkFileImportRequest(
                    WorkType.ARTWORK, 42, Set.of("split", "nested").contains(scenario) ? library : pictures, inputs))).isTrue();
            for (int i = 0; i < inputs.size(); i++) {
                Path found = external.file(WorkType.ARTWORK, 42, i);
                assertThat(Files.isSameFile(found, inputs.get(i))).isTrue();
                assertThat(Files.readAllBytes(found)[3]).isEqualTo((byte)(i == 0 ? 11 : 22));
            }
            if (scenario.equals("shared") || scenario.equals("alias")) {
                Path another = scenario.equals("alias") ? pictures.resolve("cOVER.jpg") : cover;
                assertThat(Files.isSameFile(cover, another)).isTrue();
                assertThat(importer.importLocalFiles(new top.sywyar.pixivdownload.core.work.importing.LocalWorkFileImportRequest(
                        WorkType.ARTWORK, 43, pictures, List.of(another)))).isTrue();
                assertThat(Files.readAllBytes(external.file(WorkType.ARTWORK, 43, 0)))
                        .isEqualTo(Files.readAllBytes(cover));
            }
            if (scenario.equals("outside"))
                assertThat(sql.queryForObject("SELECT file_path FROM external_work_files WHERE work_id=42", String.class)).isEqualTo(cover.toString());
            if (simulatedPosix && pair && !canCreatePair) {
                sql.update("UPDATE path_prefixes SET path='/source/pictures' WHERE id=7");
                sql.update("INSERT INTO external_work_files(reference_id,work_type,work_id,page,root_path,file_path,record_time) "
                        + "SELECT 'second',work_type,work_id,1,root_path,?,record_time FROM external_work_files WHERE work_id=42", "{7}/" + lowerName);
                sql.update("UPDATE artworks SET count=2 WHERE artwork_id=42");
            }
        } finally { data.destroy(); }
        assertRun(source, "export", archive);
    }

    void restorePageNames(Path archive, String scenario, boolean foreignOutside) throws Exception {
        Path target = setup("dest", "original", 8222);
        Path library = Files.createDirectories(target.resolve("library"));
        Path pictures = Files.createDirectory((scenario.equals("split") ? target : library).resolve("pictures"));
        boolean offline = scenario.equals("long"), pair = offline || scenario.equals("pair");
        boolean shared = scenario.equals("alias") || scenario.equals("shared");
        Path upper = pictures.resolve(offline ? "A".repeat(220) + ".jpg" : "Cover.jpg");
        Path lowerPath = pictures.resolve(upper.getFileName().toString().toLowerCase(Locale.ROOT));
        byte[] first = {(byte)0xff,(byte)0xd8,(byte)0xff,11}, second = {(byte)0xff,(byte)0xd8,(byte)0xff,22};
        Files.write(upper, first);
        boolean sensitive = Files.notExists(lowerPath);
        if (offline) Files.delete(upper);
        else if (sensitive && pair) Files.write(lowerPath, second);
        byte[] before = Files.readAllBytes(target.resolve("main.db"));
        assertRun(target, "preview", archive);
        assertRun(target, "prepare-paths", archive, library.toString(), pictures.toString());
        assertRun(target, "run", archive);
        var job = json.readTree(target.resolve("data/environment-transfers/pending.json").toFile());
        if (foreignOutside || !sensitive && pair || scenario.equals("split")) {
            assertThat(job.required("status").asString()).isEqualTo("FAILED");
            assertThat(job.required("failureCode").asString()).isEqualTo(foreignOutside
                    ? "transfer.database-path-unrepresentable" : scenario.equals("split")
                    ? "transfer.database-page-outside-root" : "transfer.database-path-collision");
            String log = Files.readString(target.resolve("run.log"));
            assertThat(log).contains(job.path("id").asString());
            if (foreignOutside) assertThat(log).contains("archiveRow=1");
            else if (scenario.equals("split")) {
                assertThat(log).contains("ARTWORK 42 page 0", "root_path {0}", "file_path {7}");
                assertThat(Files.readAllBytes(upper)).isEqualTo(first);
            } else assertThat(log).contains("ARTWORK 42 page 0", "ARTWORK 42 page 1");
            assertThat(Files.readAllBytes(target.resolve("main.db"))).isEqualTo(before);
            assertThat(tag(target.resolve("main.db"))).isEqualTo("original");
            return;
        }
        assertThat(job.required("status").asString()).isEqualTo("SUCCEEDED");
        var data = new org.springframework.jdbc.datasource.SingleConnectionDataSource("jdbc:sqlite:" + target.resolve("main.db"), true);
        try {
            var db = DatabaseProviderTestFixtures.main(data);
            var codec = new PathPrefixCodec(new top.sywyar.pixivdownload.core.db.pathprefix.PathPrefixMapper(db),
                    new top.sywyar.pixivdownload.core.appconfig.DownloadConfig(), top.sywyar.pixivdownload.i18n.TestI18nBeans.appMessages());
            codec.init();
            var external = new top.sywyar.pixivdownload.core.asset.ExternalWorkFiles(db, codec, null);
            var sql = new org.springframework.jdbc.core.JdbcTemplate(data);
            if (offline) {
                assertThat(external.file(WorkType.ARTWORK, 42, 0)).isNull();
                assertThat(external.file(WorkType.ARTWORK, 42, 1)).isNull();
                Files.write(upper, first);
                Files.write(lowerPath, second);
            }
            for (int i = 0; i < (shared || pair ? 2 : 1); i++) {
                boolean lower = i == 1 && pair;
                int id = shared && i == 1 ? 43 : 42;
                Path found = external.file(WorkType.ARTWORK, id, lower ? 1 : 0);
                Path expected = scenario.equals("outside") ? root.resolve("source/pictures/Cover.jpg") : lower ? lowerPath : upper;
                assertThat(Files.isSameFile(found, expected)).isTrue();
                assertThat(Files.readAllBytes(found)[3]).isEqualTo((byte)(lower ? 22 : 11));
                assertThat(sql.queryForObject("SELECT file_path FROM external_work_files WHERE work_id=? AND page=?", String.class, id, lower ? 1 : 0))
                        .isEqualTo(scenario.equals("outside") ? expected.toString() : scenario.equals("alias") && i == 1
                                ? "{7}/cOVER.jpg" : "{7}/" + expected.getFileName());
            }
        } finally { data.destroy(); }
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void rejectsArchivedPosixNameBeforeReplacingWindowsDatabase() throws Exception {
        Path source = setup("historical-posix", "gallery", 8111);
        var target = DatabaseProviderTestFixtures.sqlite(source.resolve("main.db"));
        try (var conn = target.openConnection(); var sql = conn.createStatement()) {
            target.migrationEndpoint().target().applySchema(conn, CoreMigrationSchema.create(DatabaseSchemaRegistry.forBuiltInPlugins(), true), true);
            sql.executeUpdate("INSERT INTO path_prefixes(id,path) VALUES(7,'/library/seven')");
            sql.executeUpdate("INSERT INTO external_work_files(reference_id,work_type,work_id,page,root_path,file_path,record_time) "
                    + "VALUES('ref2','ARTWORK',2,0,'{7}','{7}/portrait:blue.jpg',2)");
        }
        Path archive = root.resolve("historical.pdbackup");
        assertRun(source, "export", archive);
        restoreGallery(archive, false, "plain");
    }

    void exportGallery(Path archive, boolean windows) throws Exception {
        Path source = setup("source-gallery", "gallery", 8111);
        Path zero = Files.createDirectory(source.resolve("zero")), seven = Files.createDirectory(zero.resolve("seven"));
        Files.writeString(source.resolve("config/config.yaml"), "download.root-folder: " + json.writeValueAsString(zero + "/{artwork_id}") + "\n",
                StandardOpenOption.APPEND);
        var target = DatabaseProviderTestFixtures.sqlite(source.resolve("main.db"));
        try (var conn = target.openConnection()) {
            target.migrationEndpoint().target().applySchema(conn, CoreMigrationSchema.create(DatabaseSchemaRegistry.forBuiltInPlugins(), true), true);
            try (var prefix = conn.prepareStatement("INSERT INTO path_prefixes(id,path) VALUES(7,?)")) {
                prefix.setString(1, seven.toString()); prefix.executeUpdate();
            }
            var encoder = PathPrefixCodec.snapshot(zero.toString(), List.of(new PathPrefix(7, seven.toString())));
            var roots = List.of("{0}", "{7}", "{0}", "{7}", seven.toString(), "{0}");
            var files = List.of(windows ? "{0}\\volume\\one.jpg" : "{0}/volume\\one.jpg",
                    windows ? "{7}\\volume\\one.jpg" : encoder.encode(seven.resolve("volume\\one.jpg").toString()),
                    "{0}/volume/one.jpg", "{7}/volume/one.jpg", seven.resolve("volume/one.jpg").toString(), "{7}/6_p0.jpg");
            try (var artwork = conn.prepareStatement("INSERT INTO artworks(artwork_id,title,folder,count,extensions,time) VALUES(?,'one',?,1,'jpg',?)");
                 var ref = conn.prepareStatement("INSERT INTO external_work_files(reference_id,work_type,work_id,page,root_path,file_path,record_time) VALUES(?,'ARTWORK',?,0,?,?,?)")) {
                for (int i = 0; i < files.size(); i++) {
                    int id = i + 1;
                    artwork.setInt(1, id); artwork.setString(2, roots.get(i)); artwork.setInt(3, id); artwork.executeUpdate();
                    ref.setString(1, "ref" + id); ref.setInt(2, id); ref.setString(3, roots.get(i));
                    ref.setString(4, files.get(i)); ref.setInt(5, id); ref.executeUpdate();
                }
            }
        }
        createGalleryFiles(zero, seven, windows);
        assertGalleryFiles(source, zero, seven, windows, false);
        assertRun(source, "export", archive);
    }

    void restoreGallery(Path archive, boolean windowsSource, String mapping) throws Exception {
        Path target = setup("gallery-" + mapping, "original", 8222);
        Path library = Files.createDirectories(root.resolve("shared-library"));
        Path base = library;
        if (!mapping.equals("plain")) {
            Files.createSymbolicLink(target.resolve("link"), Files.createDirectories(library.resolve("nested")));
            base = target.resolve("link/..");
        }
        Path zero = (mapping.equals("child-link") ? library : base).resolve("zero"), seven = base.resolve("zero/seven");
        if (Files.notExists(seven)) {
            Files.createDirectories(seven);
            createGalleryFiles(zero, seven, windowsSource || java.io.File.separatorChar == '\\');
        }
        byte[] before = Files.readAllBytes(target.resolve("main.db"));
        assertRun(target, "preview", archive);
        assertRun(target, "prepare-paths", archive, zero.toString(), seven.toString());
        assertRun(target, "run", archive);
        var job = json.readTree(target.resolve("data/environment-transfers/pending.json").toFile());
        if (!windowsSource && java.io.File.separatorChar == '\\') {
            assertThat(job.required("status").asString()).isEqualTo("FAILED");
            assertThat(job.required("failureCode").asString()).isEqualTo("transfer.database-path-unrepresentable");
            assertThat(Files.readString(target.resolve("run.log"))).contains("table=external_work_files, column=file_path, archiveRow=");
            assertThat(Files.readAllBytes(target.resolve("main.db"))).isEqualTo(before);
            assertThat(tag(target.resolve("main.db"))).isEqualTo("original");
            return;
        }
        assertThat(job.required("status").asString()).isEqualTo("SUCCEEDED");
        assertGalleryFiles(target, zero, seven, windowsSource, true);
    }

    private static void createGalleryFiles(Path zero, Path seven, boolean windowsSource) throws Exception {
        Files.write(seven.resolve("6_p0.jpg"), new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, 6});
        for (Path dir : List.of(zero, seven)) {
            Files.createDirectory(dir.resolve("volume"));
            Files.writeString(dir.resolve("volume/one.jpg"), dir.getFileName() + "-nested");
            if (!windowsSource) Files.writeString(dir.resolve("volume\\one.jpg"), dir.getFileName() + "-literal");
        }
    }

    private static void assertGalleryFiles(Path instance, Path zero, Path seven, boolean windowsSource, boolean rescan) throws Exception {
        var data = new org.springframework.jdbc.datasource.SingleConnectionDataSource("jdbc:sqlite:" + instance.resolve("main.db"), true);
        try {
            var db = DatabaseProviderTestFixtures.main(data);
            var config = new top.sywyar.pixivdownload.core.appconfig.DownloadConfig();
            String template = new top.sywyar.pixivdownload.gui.config.ConfigFileEditor(instance.resolve("config/config.yaml")).read("download.root-folder");
            if (template != null) config.setRootFolder(template);
            var codec = new PathPrefixCodec(new top.sywyar.pixivdownload.core.db.pathprefix.PathPrefixMapper(db),
                    config, top.sywyar.pixivdownload.i18n.TestI18nBeans.appMessages());
            codec.init();
            var external = new top.sywyar.pixivdownload.core.asset.ExternalWorkFiles(db, codec, null);
            for (int id = 1; id <= 5; id++) {
                Path dir = id == 1 || id == 3 ? zero : seven;
                boolean literal = !windowsSource && id <= 2;
                Path found = external.file(top.sywyar.pixivdownload.core.work.model.WorkType.ARTWORK, id, 0);
                assertThat(found).as("gallery artwork %s", id).isNotNull();
                assertThat(Files.isSameFile(found, dir.resolve(literal ? "volume\\one.jpg" : "volume/one.jpg"))).isTrue();
                assertThat(Files.readString(found)).isEqualTo(dir.getFileName() + (literal ? "-literal" : "-nested"));
            }
            Path inner = external.file(WorkType.ARTWORK, 6, 0);
            assertThat(inner).isNotNull();
            assertThat(Files.isSameFile(inner, zero.resolve("seven/6_p0.jpg"))).isTrue();
            assertThat(Files.readAllBytes(inner)).containsExactly(0xff, 0xd8, 0xff, 6);
            if (rescan) {
                var messages = top.sywyar.pixivdownload.i18n.TestI18nBeans.appMessages();
                var sql = new org.springframework.jdbc.core.JdbcTemplate(data);
                var registry = DatabaseSchemaRegistry.forBuiltInPlugins();
                var initializer = DatabaseProviderTestFixtures.initializer(sql, registry.contributions(), registry.mergedSchema(), messages, event -> {});
                var database = new top.sywyar.pixivdownload.core.db.PixivDatabase(
                        new top.sywyar.pixivdownload.core.db.PixivMapper(db), messages, codec, initializer);
                var importer = new top.sywyar.pixivdownload.core.download.LocalWorkFileImporter(
                        new top.sywyar.pixivdownload.core.download.ArtworkDownloadHistoryAdapter(database), database, external,
                        new org.springframework.jdbc.datasource.DataSourceTransactionManager(data),
                        new top.sywyar.pixivdownload.core.download.DownloadLifecycleRegistry(), (id, name) -> {},
                        new top.sywyar.pixivdownload.core.asset.DownloadDirectories(db, codec, initializer));
                try (var service = new LocalImportService(new top.sywyar.pixivdownload.core.importing.LocalImportConfig(),
                        importer, database, external)) {
                    String pattern = "/{artwork_id}_p{page}.{extension}";
                    String original = seven.toRealPath() + pattern;
                    assertScan(service, List.of(original), LocalArtworkImports.Disposition.DUPLICATE);
                    Path extra = Files.createDirectory(instance.resolve("additional"));
                    sql.update("INSERT INTO external_work_files(reference_id,work_type,work_id,page,root_path,file_path,record_time) "
                            + "VALUES('old-missing','ARTWORK',6,1,'{7}','{7}/absent.jpg',6)");
                    Path added = Files.copy(inner, extra.resolve("6_p2.jpg"));
                    assertScan(service, List.of(original, extra + pattern), LocalArtworkImports.Disposition.SUPPLEMENT);
                    assertThat(external.files(WorkType.ARTWORK, 6)).containsExactly(inner, null, added);
                    assertThat(sql.queryForList("SELECT file_path FROM external_work_files WHERE work_id=6 AND page<2 ORDER BY page",
                            String.class)).containsExactly("{7}/6_p0.jpg", "{7}/absent.jpg");
                    Files.copy(inner, extra.resolve("6_p0.jpg"));
                    assertScan(service, List.of(extra + pattern), LocalArtworkImports.Disposition.CONFLICT);
                    assertThat(database.getArtwork(6).count()).isEqualTo(3);
                }
            }
        } finally { data.destroy(); }
    }

    private static void assertScan(LocalImportService service, List<String> templates,
            LocalArtworkImports.Disposition expected) throws Exception {
        var scan = service.scan(new LocalArtworkImports.Request(templates, List.of(6L), true, ""));
        awaitPhase(service, scan.id(), LocalArtworkImports.Phase.READY);
        var item = service.items(scan.id(), 0, 10).getFirst();
        assertThat(item.disposition()).isEqualTo(expected);
        if (expected == LocalArtworkImports.Disposition.CONFLICT) assertThat(item.reason()).isEqualTo("IMPORT_PAGE_CONFLICT");
        service.start(scan.id());
        awaitPhase(service, scan.id(), LocalArtworkImports.Phase.COMPLETED);
        var result = service.status(scan.id());
        assertThat(result.skipped()).isEqualTo(expected == LocalArtworkImports.Disposition.DUPLICATE ? 1 : 0);
        assertThat(result.imported()).isEqualTo(expected == LocalArtworkImports.Disposition.SUPPLEMENT ? 1 : 0);
        assertThat(result.failed()).isEqualTo(expected == LocalArtworkImports.Disposition.CONFLICT ? 1 : 0);
    }

    private static void awaitPhase(LocalImportService service, UUID id, LocalArtworkImports.Phase phase) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (service.status(id).phase() != phase && System.nanoTime() < deadline) Thread.sleep(10);
        assertThat(service.status(id).phase()).isEqualTo(phase);
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
    void restoresSelectedDirectoriesThroughSymlinkParent() throws Exception {
        verifyDatabasePathRestore(true);
    }

    private void verifyDatabasePathRestore(boolean throughLink) throws Exception {
        Path source = setup("source", "source", 8111), target = setup("target", "old", 8222);
        Path base = target;
        if (throughLink) {
            Files.createSymbolicLink(target.resolve("link"), Files.createDirectories(target.resolve("real/nested")));
            base = target.resolve("link/..");
            Files.createDirectory(target.resolve("mapped-root"));
            Files.createDirectory(target.resolve("mapped-root/mapped-prefix"));
        }
        Path rootPath = base.resolve("mapped-root"), prefixPath = rootPath.resolve("mapped-prefix");
        Path physicalBase = throughLink ? target.resolve("real") : target;
        Path zero = Files.writeString(Files.createDirectory(physicalBase.resolve("mapped-root")).resolve("zero.jpg"), "zero");
        Path seven = Files.writeString(Files.createDirectory(zero.getParent().resolve("mapped-prefix")).resolve("seven.jpg"), "seven");
        Path bare = Files.writeString(seven.resolveSibling("bare.jpg"), "bare");
        String oldRoot = source.resolve("works").toString(), oldPrefix = source.resolve("works/other works").toString();
        Files.writeString(source.resolve("config/config.yaml"), "download.root-folder: " + json.writeValueAsString(oldRoot) + "\n",
                StandardOpenOption.APPEND);
        var endpoint = DatabaseProviderTestFixtures.sqlite(source.resolve("main.db")).migrationEndpoint();
        try (var connection = endpoint.target().openConnection(top.sywyar.pixivdownload.plugin.api.database.DatabaseConnectionTarget.OpenMode.READ_WRITE)) {
            endpoint.target().applySchema(connection, new top.sywyar.pixivdownload.plugin.api.database.migration.MigrationSchema(1,
                    top.sywyar.pixivdownload.core.db.schema.contribution.CoreSchemaContribution.CONTRIBUTION.tables(), List.of(), List.of()), true);
            try (var insert = connection.prepareStatement("INSERT INTO path_prefixes(id,path) VALUES(7,?)")) {
                insert.setString(1, oldPrefix); insert.executeUpdate();
            }
            try (var insert = connection.prepareStatement("INSERT INTO external_work_files "
                    + "(reference_id, work_type, work_id, page, root_path, file_path, record_time) "
                    + "VALUES(?, 'ARTWORK', ?, 0, ?, ?, 1)")) {
                for (int index = 0; index < 3; index++) {
                    insert.setString(1, "ref" + index); insert.setInt(2, index + 1);
                    insert.setString(3, index == 0 ? "{0}" : index == 1 ? "{7}" : oldRoot);
                    insert.setString(4, index == 0 ? "{0}/zero.jpg" : index == 1 ? "{7}/seven.jpg" : oldPrefix + "/bare.jpg");
                    insert.executeUpdate();
                }
            }
            try (var update = connection.prepareStatement("UPDATE tags SET name=?")) {
                update.setString(1, oldPrefix); update.executeUpdate();
            }
        }
        Path archive = root.resolve("paths.pdbackup");
        assertRun(source, "export", archive);
        assertRun(target, "prepare-paths", archive, rootPath.toString(), prefixPath.toString());
        var preview = json.readTree(target.resolve("preview.json").toFile());
        assertThat(preview.path("databasePaths")).hasSize(2);
        assertRun(target, "run", archive);
        assertThat(tag(target.resolve("main.db"))).isEqualTo(oldPrefix);
        try (var connection = DatabaseProviderTestFixtures.openExisting(target.resolve("main.db")); var sql = connection.createStatement()) {
            Path restoredPrefix;
            try (var rows = sql.executeQuery("SELECT path FROM path_prefixes WHERE id=7")) {
                assertThat(rows.next()).isTrue();
                restoredPrefix = Path.of(rows.getString(1));
                assertThat(Files.isSameFile(restoredPrefix, prefixPath)).isTrue();
            }
            var restored = PathPrefixCodec.snapshot(target.toString(), List.of(new PathPrefix(7, restoredPrefix.toString())));
            try (var rows = sql.executeQuery("SELECT root_path,file_path FROM external_work_files ORDER BY reference_id")) {
                assertThat(rows.next()).isTrue(); assertThat(Files.isSameFile(Path.of(rows.getString(1)), rootPath)).isTrue();
                assertThat(Files.isSameFile(Path.of(rows.getString(2)), zero)).isTrue();
                assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).isEqualTo("{7}");
                assertThat(rows.getString(2)).isEqualTo("{7}/seven.jpg");
                assertThat(Files.isSameFile(Path.of(restored.resolve(rows.getString(2))), seven)).isTrue();
                assertThat(rows.next()).isTrue(); assertThat(Files.isSameFile(Path.of(rows.getString(1)), rootPath)).isTrue();
                assertThat(rows.getString(2)).isEqualTo("{7}/bare.jpg");
                assertThat(Files.isSameFile(Path.of(restored.resolve(rows.getString(2))), bare)).isTrue();
                assertThat(rows.next()).isFalse();
            }
        }
        try (var connection = DatabaseProviderTestFixtures.openExisting(source.resolve("main.db")); var sql = connection.createStatement();
                var row = sql.executeQuery("SELECT root_path FROM external_work_files WHERE reference_id='ref0'")) {
            assertThat(row.next()).isTrue(); assertThat(row.getString(1)).isEqualTo("{0}");
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    @DisplayName("按原路径恢复或只改一个历史别名，各作品保留原前缀 ID")
    void restoresHistoricalAliasesWithOriginalIds(boolean remapFirst) throws Exception {
        Path source = setup("source", "source", 8111), target = setup("target", "old", 8222);
        Path library = Files.createDirectory(source.resolve("Library"));
        String first = library.toString();
        String alias = first.contains("\\") ? first.replace('\\', '/') : library.resolve(".").toString();
        assertThat(first).isNotEqualTo(alias);
        assertThat(Files.isSameFile(library, Path.of(alias))).isTrue();
        Files.createDirectory(library.resolve("A"));
        Files.createDirectory(library.resolve("B"));
        var endpoint = DatabaseProviderTestFixtures.sqlite(source.resolve("main.db")).migrationEndpoint();
        try (var conn = endpoint.target().openConnection(top.sywyar.pixivdownload.plugin.api.database.DatabaseConnectionTarget.OpenMode.READ_WRITE)) {
            var schema = CoreMigrationSchema.create(DatabaseSchemaRegistry.forBuiltInPlugins(), true);
            endpoint.target().applySchema(conn, schema, true);
            try (var insert = conn.prepareStatement("INSERT INTO path_prefixes(id,path) VALUES(7,?),(8,?)")) {
                insert.setString(1, first);
                insert.setString(2, alias);
                insert.executeUpdate();
            }
            try (var sql = conn.createStatement()) {
                sql.executeUpdate("INSERT INTO artworks(artwork_id,title,folder,count,extensions,time) VALUES"
                        + "(1,'A','{7}/A',1,'jpg',1),(2,'B','{8}/B',1,'jpg',2)");
            }
        }
        Path archive = root.resolve("aliases.pdbackup");
        assertRun(source, "export", archive);
        assertRun(target, "preview", archive);
        var preview = json.readTree(target.resolve("preview.json").toFile());
        assertThat(preview.required("databaseFailure").asString()).isEmpty();
        assertThat(preview.required("databasePaths").valueStream().filter(p -> p.required("id").asLong() > 0)
                .collect(java.util.stream.Collectors.toMap(p -> p.required("id").asLong(), p -> p.required("source").asString())))
                .isEqualTo(Map.of(7L, first, 8L, alias));
        Path firstDestination = remapFirst ? Files.createDirectory(target.resolve("mapped-prefix")) : library;
        if (remapFirst) Files.createDirectory(firstDestination.resolve("A"));
        assertRun(target, remapFirst ? "prepare-paths" : "prepare", archive);
        assertThat(tag(target.resolve("main.db"))).isEqualTo("old");
        assertRun(target, "run", archive);
        assertThat(json.readTree(target.resolve("data/environment-transfers/pending.json").toFile()).required("status").asString())
                .isEqualTo("SUCCEEDED");
        try (var conn = DatabaseProviderTestFixtures.openExisting(target.resolve("main.db")); var sql = conn.createStatement()) {
            var prefixes = new ArrayList<PathPrefix>();
            try (var rows = sql.executeQuery("SELECT id,path FROM path_prefixes ORDER BY id")) {
                while (rows.next()) prefixes.add(new PathPrefix(rows.getLong(1), rows.getString(2)));
            }
            assertThat(prefixes).containsExactly(new PathPrefix(7, firstDestination.toString()), new PathPrefix(8, alias));
            var codec = PathPrefixCodec.snapshot(library.toString(), prefixes);
            try (var rows = sql.executeQuery("SELECT folder FROM artworks ORDER BY artwork_id")) {
                for (String ref : List.of("{7}/A", "{8}/B")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString(1)).isEqualTo(ref);
                    Path expected = ref.equals("{7}/A") ? firstDestination.resolve("A") : library.resolve("B");
                    assertThat(Files.isSameFile(Path.of(codec.resolve(rows.getString(1))), expected)).isTrue();
                }
                assertThat(rows.next()).isFalse();
            }
        }
    }

    @Test @DisplayName("源库与目标库仍有文件恢复意图时拒绝迁移，不发布归档或覆盖目标")
    void rejectsPendingFileRecovery() throws Exception {
        Path source = setup("source", "source", 8111), target = setup("target", "old", 8222);
        pendingFileOperation(target);
        Path archive = root.resolve("environment.pdbackup");
        assertRun(source, "export", archive);
        assertRun(target, "preview", archive);
        assertThat(json.readTree(target.resolve("preview.json").toFile()).path("databaseFailure").asString())
                .isEqualTo("transfer.database-unavailable");
        assertThat(tag(target.resolve("main.db"))).isEqualTo("old");
        assertThat(target.resolve("data/environment-transfers")).doesNotExist();
        pendingFileOperation(source);
        Path rejected = root.resolve("rejected.pdbackup");
        assertThat(MigrationProcessHarness.run(source, DatabaseTransferTestEntry.class, "pending-files", "export",
                rejected.toString(), source.resolve("preview.json").toString())).isNotZero();
        assertThat(Files.readString(source.resolve("pending-files.log"))).contains("transfer.database-pending-files");
        assertThat(rejected).doesNotExist();
        assertThat(tag(source.resolve("main.db"))).isEqualTo("source");
    }

    private void pendingFileOperation(Path directory) throws Exception {
        var endpoint = DatabaseProviderTestFixtures.sqlite(directory.resolve("main.db")).migrationEndpoint();
        try (var connection = endpoint.target().openConnection(top.sywyar.pixivdownload.plugin.api.database.DatabaseConnectionTarget.OpenMode.READ_WRITE)) {
            endpoint.target().applySchema(connection, new top.sywyar.pixivdownload.plugin.api.database.migration.MigrationSchema(1,
                    top.sywyar.pixivdownload.core.db.schema.contribution.CoreSchemaContribution.CONTRIBUTION.tables(), List.of(), List.of()), true);
            try (var sql = connection.prepareStatement("INSERT INTO file_operation_entries(entry_id,operation_id,ordinal,target_path,committed) VALUES('fixture:0','fixture',0,?,0)")) {
                sql.setString(1, directory.resolve("pending-file").toString());
                sql.executeUpdate();
            }
        }
    }
    private void scheduledTask(Path directory) throws Exception {
        var endpoint = DatabaseProviderTestFixtures.sqlite(directory.resolve("main.db")).migrationEndpoint();
        try (var connection = endpoint.target().openConnection(top.sywyar.pixivdownload.plugin.api.database.DatabaseConnectionTarget.OpenMode.READ_WRITE)) {
            endpoint.target().applySchema(connection, new top.sywyar.pixivdownload.plugin.api.database.migration.MigrationSchema(1,
                    top.sywyar.pixivdownload.core.schedule.db.ScheduleSchemaContribution.CONTRIBUTION.tables(), List.of(), List.of()), true);
            try (var sql = connection.createStatement()) {
                sql.execute("INSERT INTO scheduled_tasks(name,type,params_json,trigger_kind,cookie_mode,created_time,run_state,run_claim_token) "
                        + "VALUES('fixture task','fixture-source','{}','INTERVAL','NONE',1,'RUNNING','old-process-claim')");
            }
        }
    }
    private long importedAt(Path file) throws Exception {
        try (var connection = DatabaseProviderTestFixtures.openExisting(file); var sql = connection.createStatement();
             var row = sql.executeQuery("SELECT imported_at FROM imported_works")) { assertThat(row.next()).isTrue(); return row.getLong(1); }
    }

    private Path setup(String name, String tag, int port) throws Exception {
        Path directory = Files.createDirectory(root.resolve(name));
        Path database = directory.resolve("main.db");
        try (var connection = DatabaseProviderTestFixtures.openConnection(database); var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE tags(tag_id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL UNIQUE,translated_name TEXT)");
            try (var insert = connection.prepareStatement("INSERT INTO tags VALUES(9007199254740993,?,NULL)")) {
                insert.setString(1, tag); insert.executeUpdate();
            }
        }
        Path config = Files.createDirectory(directory.resolve("config")).resolve("config.yaml");
        Files.writeString(config, "database.provider: database-sqlite\ndatabase.engine: sqlite\ndatabase.jdbc-url: "
                + json.writeValueAsString("jdbc:sqlite:" + database) + "\nserver.port: " + port + "\n");
        return directory;
    }
    private void assertRun(Path directory, String action, Path archive, String... mappedPaths) throws Exception {
        var arguments = new ArrayList<>(List.of(action, archive.toString(), directory.resolve("preview.json").toString()));
        arguments.addAll(List.of(mappedPaths));
        int exit = MigrationProcessHarness.run(directory, DatabaseTransferTestEntry.class, action, arguments.toArray(String[]::new));
        assertThat(exit).as(Files.readString(directory.resolve(action + ".log"))).isZero();
    }
    private String tag(Path file) throws Exception {
        try (var connection = DatabaseProviderTestFixtures.openExisting(file); var sql = connection.createStatement();
             var row = sql.executeQuery("SELECT name FROM tags WHERE tag_id=9007199254740993")) {
            assertThat(row.next()).isTrue(); return row.getString(1);
        }
    }
}
