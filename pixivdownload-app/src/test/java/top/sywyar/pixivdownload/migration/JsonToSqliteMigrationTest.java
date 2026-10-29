package top.sywyar.pixivdownload.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("旧 JSON 离线迁移")
class JsonToSqliteMigrationTest {
    @TempDir Path directory;

    @org.junit.jupiter.api.AfterEach
    void cleanDirectoryAfterResourcesClose() throws java.io.IOException {
        top.sywyar.pixivdownload.testsupport.TestDirectoryCleanup.delete(directory);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    @DisplayName("预览不存在的数据库不创建文件，仍统计旧 JSON")
    void previewDoesNotCreateDatabase(boolean historyExists) throws Exception {
        var file = directory.resolve("absent.db");
        var target = top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.sqlite(file);
        if (historyExists) Files.writeString(directory.resolve(JsonToSqliteMigration.DOWNLOAD_HISTORY_FILE),
                "{\"downloaded\":{\"1\":{}}}", StandardCharsets.UTF_8);
        var options = new JsonToSqliteMigration.Options(target.displayName(), directory.toString(), target);
        assertThat(JsonToSqliteMigration.countCandidates(options, null)).isEqualTo(historyExists ? 1 : 0);
        assertThat(file).doesNotExist();
    }

    @Test
    @DisplayName("已有库的旧快照预览使用只读连接，内容和结构字节不变")
    void previewExistingDatabaseIsReadOnly() throws Exception {
        var file = directory.resolve("existing.db");
        var target = org.mockito.Mockito.spy(top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.sqlite(file));
        try (var connection = target.openConnection(); var sql = connection.createStatement()) {
            top.sywyar.pixivdownload.core.db.schema.DatabaseInitializer.prepareOffline(connection, target);
            sql.execute("INSERT INTO artworks(artwork_id,title,folder,count,extensions,time) VALUES(1,'keep','{0}',1,'jpg',1)");
        }
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        Files.writeString(directory.resolve("1.meta.json"), new top.sywyar.pixivdownload.core.metadata.sidecar.WorkMetaCurator(mapper)
                .curateArtwork(1, mapper.readTree("{\"id\":1,\"userId\":42}"), null, "forward").document().toString(),
                StandardCharsets.UTF_8);
        byte[] before = Files.readAllBytes(file);
        var options = new JsonToSqliteMigration.Options(target.displayName(), directory.toString(), target);
        assertThat(JsonToSqliteMigration.countCandidates(options, null)).isEqualTo(1);
        org.mockito.Mockito.verify(target).openConnection(
                top.sywyar.pixivdownload.plugin.api.database.DatabaseConnectionTarget.OpenMode.READ_ONLY);
        assertThat(Files.readAllBytes(file)).isEqualTo(before);
        assertThat(directory.resolve("1.meta.json")).exists();
    }

    @Test
    @DisplayName("在线预览仍核验借用连接的目标身份并释放错误目标连接")
    void previewRejectsWrongPooledTarget() throws Exception {
        var requested = top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.sqlite(directory.resolve("requested.db"));
        var other = top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.sqlite(directory.resolve("other.db"));
        var source = org.mockito.Mockito.mock(javax.sql.DataSource.class);
        try (var connection = other.openConnection()) {
            org.mockito.Mockito.when(source.getConnection()).thenReturn(connection);
            var options = new JsonToSqliteMigration.Options(requested.displayName(), directory.toString(), requested);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> JsonToSqliteMigration.countCandidates(options, source))
                    .isInstanceOf(java.sql.SQLException.class).hasMessageContaining("DATABASE_TARGET_MISMATCH");
            assertThat(connection.isClosed()).isTrue();
        }
        assertThat(directory.resolve("requested.db")).doesNotExist();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    @DisplayName("混合与全部失败均返回结构化失败数量，保留成功写入和原文件")
    void reportsSnapshotFailures(boolean mixed) throws Exception {
        var target = top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.sqlite(directory.resolve("metadata.db"));
        Files.writeString(directory.resolve("3.meta.json"), "{\"invalid\":true}");
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        if (mixed) Files.writeString(directory.resolve("4.meta.json"),
                new top.sywyar.pixivdownload.core.metadata.sidecar.WorkMetaCurator(mapper)
                        .curateNovel(4, mapper.readTree("{\"id\":4}"), "forward").document().toString());
        try (var connection = target.openConnection(); var sql = connection.createStatement()) {
            top.sywyar.pixivdownload.core.db.schema.DatabaseInitializer.prepareOffline(connection, target);
            sql.executeUpdate("INSERT INTO novels(novel_id,title,folder,count,extensions,time) VALUES(3,'bad','{0}',1,'txt',1)");
            if (mixed) sql.executeUpdate("INSERT INTO novels(novel_id,title,folder,count,extensions,time) VALUES(4,'good','{0}',1,'txt',2)");
        }
        var options = new JsonToSqliteMigration.Options(target.displayName(), directory.toString(), target);
        var summary = JsonToSqliteMigration.run(options, null);
        assertThat(summary.failed()).isEqualTo(1);
        assertThat(summary.totalCandidates()).isEqualTo(mixed ? 2 : 1);
        assertThat(summary.migrated()).isEqualTo(mixed ? 1 : 0);
        assertThat(summary.skipped()).isZero();
        assertThat(summary.historyFileMissing()).isFalse();
        var retry = JsonToSqliteMigration.run(options, null);
        assertThat(retry.failed()).isEqualTo(1);
        assertThat(retry.migrated()).isZero();
        assertThat(retry.skipped()).isEqualTo(mixed ? 1 : 0);
        try (var connection = target.openConnection(); var sql = connection.createStatement();
             var rows = sql.executeQuery("SELECT count(*) FROM novels WHERE metadata_json IS NOT NULL")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getInt(1)).isEqualTo(mixed ? 1 : 0);
        }
        assertThat(directory.resolve("3.meta.json")).hasContent("{\"invalid\":true}");
        if (mixed) assertThat(directory.resolve("4.meta.json")).exists();
    }

    @Test
    @DisplayName("JSON 导入普通失败后的回滚致命错误优先传播且关闭连接")
    void fatalRollbackEscapesOrdinaryFailure() throws Exception {
        var target = org.mockito.Mockito.spy(
                top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.sqlite(directory.resolve("rollback.db")));
        Files.writeString(directory.resolve(JsonToSqliteMigration.DOWNLOAD_HISTORY_FILE),
                "{\"downloaded\":{}}", StandardCharsets.UTF_8);
        try (var raw = target.openConnection()) {
            var connection = org.mockito.Mockito.mock(java.sql.Connection.class,
                    org.mockito.AdditionalAnswers.delegatesTo(raw));
            var failure = new java.sql.SQLException("controlled storage failure");
            var cleanup = new ThreadDeath();
            org.mockito.Mockito.doAnswer(ignored -> {
                org.mockito.Mockito.doThrow(cleanup).when(connection).rollback();
                throw failure;
            }).when(target).storageSession(org.mockito.ArgumentMatchers.eq(connection), org.mockito.ArgumentMatchers.any());
            var options = new JsonToSqliteMigration.Options(target.displayName(), directory.toString(), target);
            var source = org.mockito.Mockito.mock(javax.sql.DataSource.class);
            org.mockito.Mockito.when(source.getConnection()).thenReturn(connection);

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> JsonToSqliteMigration.run(options, null, source))
                    .isSameAs(cleanup).hasSuppressedException(failure);
            assertThat(connection.isClosed()).isTrue();
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    @DisplayName("导入报告或存储会话关闭致命失败时撤回作品和统计")
    void fatalFailureRollsBackImportedRows(boolean failOnClose) throws Exception {
        var target = org.mockito.Mockito.spy(
                top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.sqlite(directory.resolve("fatal.db")));
        Files.writeString(directory.resolve(JsonToSqliteMigration.DOWNLOAD_HISTORY_FILE), """
                {"downloaded":{"1":{"title":"fixture","folder":"folder","count":1,"extensions":"png","time":1}}}
                """, StandardCharsets.UTF_8);
        Files.writeString(directory.resolve(JsonToSqliteMigration.STATISTICS_FILE),
                "{\"totalArtworks\":1,\"totalImages\":1,\"totalMoved\":0}", StandardCharsets.UTF_8);
        var failure = new OutOfMemoryError("controlled reporter failure");
        if (failOnClose) {
            org.mockito.Mockito.doAnswer(invocation -> {
                var real = (top.sywyar.pixivdownload.plugin.api.storage.relational.DatabaseSession) invocation.callRealMethod();
                var session = org.mockito.Mockito.mock(top.sywyar.pixivdownload.plugin.api.storage.relational.DatabaseSession.class,
                        org.mockito.AdditionalAnswers.delegatesTo(real));
                org.mockito.Mockito.doAnswer(ignored -> { real.close(); throw failure; }).when(session).close();
                return session;
            }).when(target).storageSession(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        }
        var reports = new java.util.concurrent.atomic.AtomicInteger();
        var options = new JsonToSqliteMigration.Options(target.displayName(), directory.toString(), target);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> JsonToSqliteMigration.run(options, ignored -> {
            if (!failOnClose && reports.incrementAndGet() == 2) throw failure;
        })).isSameAs(failure);
        try (var connection = target.openConnection(); var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("SELECT count(*) FROM artworks")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isZero();
            }
            try (var rows = statement.executeQuery("SELECT count(*) FROM statistics")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isZero();
            }
        }
    }

    @Test
    @DisplayName("离线迁移写入指定 SQLite，重跑保留已有作品与统计")
    void importsIntoRequestedDatabaseWithoutOverwritingOnRetry() throws Exception {
        Path database = directory.resolve("existing.db");
        Path downloads = Files.createDirectories(directory.resolve("downloads"));
        Files.writeString(downloads.resolve(JsonToSqliteMigration.DOWNLOAD_HISTORY_FILE), """
                {"downloaded":{"5000000000":{"title":"原始标题","folder":"/works/one/",
                "count":2,"extensions":"jpg","time":1700000000,"moved":true,
                "moveFolder":"/moved/one/","moveTime":1700000001}}}
                """, StandardCharsets.UTF_8);
        Files.writeString(downloads.resolve(JsonToSqliteMigration.STATISTICS_FILE),
                "{\"totalArtworks\":1,\"totalImages\":2,\"totalMoved\":1}", StandardCharsets.UTF_8);
        var options = new JsonToSqliteMigration.Options(database.toString(), downloads.toString(), top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.sqlite(database));

        assertThat(JsonToSqliteMigration.countCandidates(options)).isEqualTo(1);
        assertThat(JsonToSqliteMigration.run(options, null).migrated()).isEqualTo(1);
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE artworks SET title = '保留修改'");
            statement.executeUpdate("UPDATE statistics SET total_images = 9");
        }
        var retry = JsonToSqliteMigration.run(options, null);
        assertThat(retry.migrated()).isZero();
        assertThat(retry.skipped()).isEqualTo(1);
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) {
            try (var row = statement.executeQuery("SELECT * FROM artworks")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getLong("artwork_id")).isEqualTo(5000000000L);
                assertThat(row.getString("title")).isEqualTo("保留修改");
                assertThat(row.getString("folder")).isEqualTo("/works/one");
                assertThat(row.getLong("time")).isEqualTo(1700000000000L);
                assertThat(row.getInt("moved")).isEqualTo(1);
                assertThat(row.getString("move_folder")).isEqualTo("/moved/one");
                assertThat(row.getLong("move_time")).isEqualTo(1700000001000L);
                assertThat(row.next()).isFalse();
            }
            try (var row = statement.executeQuery("SELECT total_images FROM statistics")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getInt(1)).isEqualTo(9);
            }
            try (var row = statement.executeQuery("PRAGMA journal_mode")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString(1)).isEqualTo("wal");
            }
        }
    }
}
