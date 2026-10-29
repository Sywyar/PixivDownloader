package top.sywyar.pixivdownload.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures;
import top.sywyar.pixivdownload.core.db.schema.DatabaseInitializer;
import top.sywyar.pixivdownload.core.metadata.sidecar.WorkMetaCurator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("历史作品元数据导入")
class WorkMetadataImportTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("回填预导入完整投影小说，并保留插画强制联网的取值边界")
    void preimportProjectsNovelsOnlyAndFormalImportRepairsArtwork() throws Exception {
        var target = DatabaseProviderTestFixtures.sqlite(directory.resolve("preimport.db"));
        var curator = new WorkMetaCurator(mapper);
        var raw = mapper.readTree("{\"id\":3,\"uploadDate\":\"2020-02-03T12:00:00+09:00\"}");
        var novel = curator.curateNovel(3, raw, "forward").document();
        Files.writeString(directory.resolve("3.meta.json"), novel.toString());
        Files.writeString(directory.resolve("4.meta.json"), curator.curateArtwork(4,
                mapper.readTree("{\"id\":4,\"uploadDate\":\"2020-02-03T12:00:00+09:00\"}"), null, "forward").document().toString());
        try (var connection = target.openConnection(); var sql = connection.createStatement()) {
            DatabaseInitializer.prepareOffline(connection, target);
            sql.executeUpdate("INSERT INTO novels(novel_id,title,folder,count,extensions,time) VALUES(3,'novel','{0}',1,'txt',1)");
            sql.executeUpdate("INSERT INTO artworks(artwork_id,title,folder,count,extensions,time) VALUES(4,'art','{0}',1,'jpg',1)");
        }
        try (var connection = target.openConnection()) {
            assertThat(WorkMetadataImport.run(connection, target, directory.toString(), false, false, null).imported()).isEqualTo(2);
            try (var sql = connection.createStatement(); var row = sql.executeQuery("SELECT upload_time,metadata_json FROM novels")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getLong(1)).isEqualTo(1580698800000L);
                assertThat(mapper.readTree(row.getString(2))).isEqualTo(mapper.readTree(novel.toString()));
            }
            try (var sql = connection.createStatement(); var row = sql.executeQuery("SELECT upload_time FROM artworks")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getObject(1)).isNull();
            }
            assertThat(WorkMetadataImport.run(connection, target, directory.toString(), false, true, null).imported()).isEqualTo(1);
            assertThat(WorkMetadataImport.run(connection, target, directory.toString(), false, true, null).imported()).isZero();
        }
        assertThat(directory.resolve("3.meta.json")).hasContent(novel.toString());
    }

    @Test
    @DisplayName("已有小说快照无源文件也可补投影，预览只读且保留快照来源时间与已有列")
    void repairsStoredNovelProjectionWithoutSource() throws Exception {
        var target = DatabaseProviderTestFixtures.sqlite(directory.resolve("retry.db"));
        var document = new WorkMetaCurator(mapper).curateNovel(3,
                mapper.readTree("{\"id\":3,\"uploadDate\":\"2020-02-03T12:00:00+09:00\"}"), "forward").document();
        document.put("fetchedAt", "2020-02-03T03:00:01Z");
        String snapshot = document.toPrettyString();
        try (var connection = target.openConnection(); var sql = connection.createStatement()) {
            DatabaseInitializer.prepareOffline(connection, target);
            sql.executeUpdate("INSERT INTO novels(novel_id,title,folder,count,extensions,time) VALUES(3,'novel','{0}',1,'txt',1)");
            try (var insert = connection.prepareStatement("UPDATE novels SET metadata_json=?")) {
                insert.setString(1, snapshot);
                insert.executeUpdate();
            }
        }
        try (var connection = target.openConnection()) {
            assertThat(WorkMetadataImport.run(connection, target, directory.toString(), true, null).imported()).isEqualTo(1);
            try (var sql = connection.createStatement(); var row = sql.executeQuery("SELECT upload_time FROM novels")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getObject(1)).isNull();
            }
            assertThat(WorkMetadataImport.run(connection, target, directory.toString(), false, null).imported()).isEqualTo(1);
            try (var sql = connection.createStatement(); var row = sql.executeQuery("SELECT upload_time,metadata_json FROM novels")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getLong(1)).isEqualTo(1580698800000L);
                assertThat(row.getString(2)).isEqualTo(snapshot);
            }
            try (var sql = connection.createStatement()) { sql.executeUpdate("UPDATE novels SET upload_time=1234"); }
            assertThat(WorkMetadataImport.run(connection, target, directory.toString(), false, null).imported()).isZero();
            try (var sql = connection.createStatement(); var row = sql.executeQuery("SELECT upload_time FROM novels")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getLong(1)).isEqualTo(1234L);
            }
        }
    }

    @Test
    @DisplayName("小说快照写入失败时回滚字段并保留文件，重试后投影上传时间")
    void novelImportRollsBackAndCanBeRetried() throws Exception {
        var target = DatabaseProviderTestFixtures.sqlite(directory.resolve("rollback.db"));
        var document = new WorkMetaCurator(mapper).curateNovel(3,
                mapper.readTree("{\"id\":\"3\",\"uploadDate\":\"2020-02-03T12:00:00+09:00\"}"), "forward").document();
        var source = directory.resolve("3.meta.json");
        Files.writeString(source, document.toString());
        try (var connection = target.openConnection(); var sql = connection.createStatement()) {
            DatabaseInitializer.prepareOffline(connection, target);
            sql.executeUpdate("INSERT INTO novels(novel_id,title,folder,count,extensions,time) VALUES(3,'novel','{0}',1,'txt',1)");
            sql.executeUpdate("CREATE TRIGGER fail_metadata BEFORE UPDATE OF metadata_json ON novels BEGIN SELECT RAISE(ABORT,'fixture'); END");
        }
        try (var connection = target.openConnection()) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> WorkMetadataImport.run(connection, target,
                    directory.toString(), false, null)).isInstanceOf(java.sql.SQLException.class);
        }
        try (var connection = target.openConnection(); var sql = connection.createStatement()) {
            try (var row = sql.executeQuery("SELECT metadata_json,upload_time FROM novels")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString(1)).isNull();
                assertThat(row.getObject(2)).isNull();
            }
            sql.executeUpdate("DROP TRIGGER fail_metadata");
        }
        assertThat(source).exists();
        try (var connection = target.openConnection()) {
            assertThat(WorkMetadataImport.run(connection, target, directory.toString(), false, null).imported()).isEqualTo(1);
        }
        try (var connection = target.openConnection(); var sql = connection.createStatement();
             var row = sql.executeQuery("SELECT upload_time FROM novels")) {
            assertThat(row.next()).isTrue();
            assertThat(row.getLong(1)).isEqualTo(1580698800000L);
        }
    }

    @Test
    @DisplayName("无旧下载历史时按移动后的登记路径导入并保留来源和已有列，重复导入不覆盖")
    void importsRegisteredSnapshotsWithoutHistory() throws Exception {
        var target = DatabaseProviderTestFixtures.sqlite(directory.resolve("works.db"));
        var folder = Files.createDirectory(directory.resolve("moved"));
        var document = new WorkMetaCurator(mapper).curateArtwork(1, mapper.readTree("""
                {"id":"1","title":"作品","userId":"42","uploadDate":"2020-02-03T12:00:00+09:00"}
                """), null, "forward").document();
        document.put("fetchedAt", "2020-02-03T03:00:01Z");
        var source = folder.resolve("1.meta.json");
        Files.writeString(source, document.toPrettyString());
        try (var connection = target.openConnection(); var sql = connection.createStatement()) {
            DatabaseInitializer.prepareOffline(connection, target);
            sql.executeUpdate("INSERT INTO artworks(artwork_id,title,folder,count,extensions,time,moved,move_folder,upload_time) "
                    + "VALUES(1,'existing','absent',1,'jpg',1,1,'{0}/moved',1234)");
        }
        var options = new JsonToSqliteMigration.Options(target.displayName(), directory.toString(), target);
        assertThat(JsonToSqliteMigration.countCandidates(options, null)).isEqualTo(1);
        assertThat(JsonToSqliteMigration.run(options, null).migrated()).isEqualTo(1);
        assertThat(JsonToSqliteMigration.run(options, null).migrated()).isZero();
        assertThat(source).exists();
        try (var connection = target.openConnection(); var sql = connection.createStatement();
             var row = sql.executeQuery("SELECT metadata_json,upload_time,title FROM artworks")) {
            assertThat(row.next()).isTrue();
            assertThat(mapper.readTree(row.getString(1))).isEqualTo(mapper.readTree(document.toString()));
            assertThat(row.getLong(2)).isEqualTo(1234);
            assertThat(row.getString(3)).isEqualTo("existing");
        }
    }

    @Test
    @DisplayName("只读预览不写库，错误身份和超限文件保留且不会自动登记孤立文件")
    void rejectsInvalidAndUnregisteredSnapshots() throws Exception {
        var target = DatabaseProviderTestFixtures.sqlite(directory.resolve("validation.db"));
        var valid = new WorkMetaCurator(mapper).curateArtwork(1, mapper.readTree("{\"id\":\"1\",\"title\":\"one\"}"), null, "forward").document();
        Files.writeString(directory.resolve("1.meta.json"), valid.toString());
        Files.writeString(directory.resolve("2.meta.json"), valid.toString());
        Files.writeString(directory.resolve("3.meta.json"), " ".repeat(WorkMetadataImport.MAX_SOURCE_BYTES + 1));
        Files.writeString(directory.resolve("99.meta.json"), valid.toString());
        try (var connection = target.openConnection(); var sql = connection.createStatement()) {
            DatabaseInitializer.prepareOffline(connection, target);
            for (int id : List.of(1, 2, 3)) sql.executeUpdate("INSERT INTO artworks(artwork_id,title,folder,count,extensions,time) "
                    + "VALUES(" + id + ",'fixture','{0}',1,'jpg'," + id + ")");
            var preview = WorkMetadataImport.run(connection, target, directory.toString(), true, null);
            assertThat(preview.imported()).isEqualTo(1);
            assertThat(preview.failed()).isEqualTo(2);
            try (var row = sql.executeQuery("SELECT count(*) FROM artworks WHERE metadata_json IS NOT NULL")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getInt(1)).isZero();
            }
        }
        try (var connection = target.openConnection()) {
            assertThat(WorkMetadataImport.run(connection, target, directory.toString(), false, null).imported()).isEqualTo(1);
        }
        try (var connection = target.openConnection(); var sql = connection.createStatement()) {
            try (var row = sql.executeQuery("SELECT count(*) FROM artworks")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getInt(1)).isEqualTo(3);
            }
        }
        assertThat(directory.resolve("2.meta.json")).exists();
        assertThat(directory.resolve("3.meta.json")).exists();
    }
}
