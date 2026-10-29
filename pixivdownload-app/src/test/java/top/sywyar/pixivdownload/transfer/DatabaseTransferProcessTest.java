package top.sywyar.pixivdownload.transfer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures;
import top.sywyar.pixivdownload.migration.MigrationProcessHarness;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@DisplayName("数据库归档的真实导出、预览与停机替换")
class DatabaseTransferProcessTest {
    @TempDir Path root;
    private final ObjectMapper json = new ObjectMapper();

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
        assertThat(preview.path("databaseFailure").asText()).isEmpty();
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
        assertThat(job.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(tag(target.resolve("main.db"))).isEqualTo("归档 🌸");
        assertThat(json.readTree(target.resolve("state/gui/window-state.json").toFile()).path("width").intValue()).isEqualTo(1280);
        assertThat(Files.readString(target.resolve("state/neutral-migration/preferences.txt"))).isEqualTo("owner-declared state");
        assertThat(tag(target.resolve("data/environment-transfers").resolve(job.path("id").asText()).resolve("database-backups/host.backup")))
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
            assertThat(importedAt(target.resolve("data/environment-transfers").resolve(job.path("id").asText())
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
        assertThat(job.path("status").asText()).isEqualTo("FAILED");
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
        var job = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(journal.toFile());
        job.put("status", "APPLYING");
        Files.write(journal, json.writeValueAsBytes(job));
        var store = new top.sywyar.pixivdownload.migration.database.ProviderDatabaseMigration.StoreReport("archive", "fixture", "fixture", "fixture",
                top.sywyar.pixivdownload.migration.database.ProviderDatabaseMigration.Phase.COMMITTING, "", 0, null);
        var report = new top.sywyar.pixivdownload.migration.database.ProviderDatabaseMigration.Report(1, "IN_PROGRESS",
                Map.of("main", store), "private-row-content-must-not-be-logged", List.of());
        Files.write(target.resolve("data/environment-transfers").resolve(job.path("id").asText()).resolve("database-report.json"),
                json.writeValueAsBytes(report));
        assertThat(MigrationProcessHarness.run(target, DatabaseTransferTestEntry.class, "interrupted", "run")).isNotZero();
        assertThat(json.readTree(journal.toFile()).path("status").asText()).isEqualTo("RECOVERY_REQUIRED");
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
        assertRun(target, "run", archive);
        var job = json.readTree(target.resolve("data/environment-transfers/pending.json").toFile());
        assertThat(job.path("status").asText()).isEqualTo("FAILED");
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
        Path source = setup("source", "source", 8111), target = setup("target", "old", 8222);
        String oldRoot = source.resolve("works").toString(), oldPrefix = source.resolve("other works").toString();
        Files.writeString(source.resolve("config/config.yaml"), "download.root-folder: " + json.writeValueAsString(oldRoot) + "\n",
                StandardOpenOption.APPEND);
        var endpoint = DatabaseProviderTestFixtures.sqlite(source.resolve("main.db")).migrationEndpoint();
        try (var connection = endpoint.target().openConnection(top.sywyar.pixivdownload.plugin.api.database.DatabaseConnectionTarget.OpenMode.READ_WRITE)) {
            endpoint.target().applySchema(connection, new top.sywyar.pixivdownload.plugin.api.database.migration.MigrationSchema(1,
                    top.sywyar.pixivdownload.core.db.schema.contribution.CoreSchemaContribution.CONTRIBUTION.tables(), List.of(), List.of()), true);
            try (var insert = connection.prepareStatement("INSERT INTO path_prefixes(id,path) VALUES(7,?)")) {
                insert.setString(1, oldPrefix); insert.executeUpdate();
            }
            try (var insert = connection.prepareStatement("INSERT INTO external_work_files VALUES(?, 'ARTWORK', ?, 0, ?, ?, 1)")) {
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
        assertRun(target, "prepare-paths", archive);
        var preview = json.readTree(target.resolve("preview.json").toFile());
        assertThat(preview.path("databasePaths")).hasSize(2);
        assertRun(target, "run", archive);
        assertThat(tag(target.resolve("main.db"))).isEqualTo(oldPrefix);
        try (var connection = DatabaseProviderTestFixtures.openExisting(target.resolve("main.db")); var sql = connection.createStatement()) {
            try (var rows = sql.executeQuery("SELECT path FROM path_prefixes WHERE id=7")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).isEqualTo(target.resolve("mapped-prefix").toString());
            }
            try (var rows = sql.executeQuery("SELECT root_path,file_path FROM external_work_files ORDER BY reference_id")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).isEqualTo(target.resolve("mapped-root").toString());
                assertThat(rows.getString(2)).isEqualTo(target.resolve("mapped-root") + "/zero.jpg");
                assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).isEqualTo("{7}");
                assertThat(rows.getString(2)).isEqualTo("{7}/seven.jpg");
                assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).isEqualTo(target.resolve("mapped-root").toString());
                assertThat(rows.getString(2)).isEqualTo("{7}/bare.jpg");
                assertThat(rows.next()).isFalse();
            }
        }
        try (var connection = DatabaseProviderTestFixtures.openExisting(source.resolve("main.db")); var sql = connection.createStatement();
                var row = sql.executeQuery("SELECT root_path FROM external_work_files WHERE reference_id='ref0'")) {
            assertThat(row.next()).isTrue(); assertThat(row.getString(1)).isEqualTo("{0}");
        }
    }

    @Test @DisplayName("源库与目标库仍有文件恢复意图时拒绝迁移，不发布归档或覆盖目标")
    void rejectsPendingFileRecovery() throws Exception {
        Path source = setup("source", "source", 8111), target = setup("target", "old", 8222);
        pendingFileOperation(target);
        Path archive = root.resolve("environment.pdbackup");
        assertRun(source, "export", archive);
        assertRun(target, "preview", archive);
        assertThat(json.readTree(target.resolve("preview.json").toFile()).path("databaseFailure").asText())
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
    private void assertRun(Path directory, String action, Path archive) throws Exception {
        int exit = MigrationProcessHarness.run(directory, DatabaseTransferTestEntry.class, action, action,
                archive.toString(), directory.resolve("preview.json").toString());
        assertThat(exit).as(Files.readString(directory.resolve(action + ".log"))).isZero();
    }
    private String tag(Path file) throws Exception {
        try (var connection = DatabaseProviderTestFixtures.openExisting(file); var sql = connection.createStatement();
             var row = sql.executeQuery("SELECT name FROM tags WHERE tag_id=9007199254740993")) {
            assertThat(row.next()).isTrue(); return row.getString(1);
        }
    }
}
