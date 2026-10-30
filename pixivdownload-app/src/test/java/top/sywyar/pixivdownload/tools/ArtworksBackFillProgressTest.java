package top.sywyar.pixivdownload.tools;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.config.RuntimeFiles;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("作品回填进度和取消")
class ArtworksBackFillProgressTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "false, complete", "true, complete", "false, missing-ai", "true, missing-ai",
            "false, local-description", "true, local-description", "false, concurrent-r18", "false, soft-deleted"
    })
    @DisplayName("受限作品按实际或预演补入结果报告剩余缺项，保留未知字段与并发值")
    void reportsRestrictedWorkRemaining(boolean dryRun, String scenario, @TempDir Path directory) throws Exception {
        var path = directory.resolve("restricted.db");
        var target = top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.sqlite(path);
        var configuration = new HikariConfig();
        configuration.setJdbcUrl("jdbc:sqlite:" + path);
        configuration.setMaximumPoolSize(2);
        boolean missingAi = scenario.equals("missing-ai");
        boolean localDescription = scenario.equals("local-description");
        boolean concurrentR18 = scenario.equals("concurrent-r18");
        boolean softDeleted = scenario.equals("soft-deleted");
        var client = mock(ArtworksBackFillPixivClient.class);
        try (var pool = new HikariDataSource(configuration);
             var files = mockStatic(RuntimeFiles.class);
             var clients = mockStatic(ArtworksBackFillPixivClient.class, invocation ->
                     invocation.getMethod().getName().equals("open") ? client : invocation.callRealMethod())) {
            var unreachable = directory.resolve("unreachable.json");
            files.when(RuntimeFiles::resolveBackfillUnreachablePath).thenReturn(unreachable);
            try (var connection = pool.getConnection()) {
                top.sywyar.pixivdownload.core.db.schema.DatabaseInitializer.prepareOffline(connection, target);
                try (var statement = connection.createStatement()) {
                    statement.executeUpdate("INSERT INTO artworks(artwork_id,title,folder,count,extensions,time,author_id,is_ai,description,series_id,upload_time)"
                            + " VALUES(1,'fixture','{0}',1,'jpg',1,0,0,'known',0,1234)");
                    statement.executeUpdate("INSERT INTO tags(tag_id,name) VALUES(1,'fixture')");
                    statement.executeUpdate("INSERT INTO artwork_tags(artwork_id,tag_id) VALUES(1,1)");
                    if (missingAi) statement.executeUpdate("UPDATE artworks SET is_ai=NULL WHERE artwork_id=1");
                }
                if (localDescription) {
                    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    var metadata = new top.sywyar.pixivdownload.core.metadata.sidecar.WorkMetaCurator(mapper)
                            .curateArtwork(1, mapper.readTree("{\"id\":1,\"description\":\"local\"}"), null, "forward");
                    try (var statement = connection.prepareStatement("UPDATE artworks SET description=NULL,metadata_json=? WHERE artwork_id=1")) {
                        statement.setString(1, metadata.document().toString());
                        statement.executeUpdate();
                    }
                }
            }
            when(client.query(1)).thenAnswer(ignored -> {
                if (concurrentR18 || softDeleted) {
                    try (var connection = pool.getConnection(); var statement = connection.createStatement()) {
                        statement.executeUpdate(concurrentR18 ? "UPDATE artworks SET r18=0 WHERE artwork_id=1"
                                : "UPDATE artworks SET deleted=1 WHERE artwork_id=1");
                    }
                }
                return ArtworksBackFillPixivClient.LookupResult.r18Only("restricted");
            });
            var progress = new java.util.ArrayList<ArtworksBackFill.Progress>();
            var options = new ArtworksBackFill.Options(path.toString(), "", 0, false, 0, 1, dryRun, target)
                    .withSources(directory.toString(), false);
            var result = ArtworksBackFill.runObserved(options, pool, progress::add);
            var last = progress.get(progress.size() - 1);
            assertEquals(ArtworksBackFill.Phase.FINISHED, last.phase());
            assertEquals(1, last.total());
            assertEquals(1, last.processed());
            assertEquals(concurrentR18 || softDeleted ? 0 : 1, result.filledR18());
            assertEquals(concurrentR18 || softDeleted ? 0 : 1, last.changed());
            assertEquals(localDescription ? 1 : 0, result.filledDescription());
            assertEquals(0, result.filledAi());
            assertEquals(missingAi ? 1 : 0, last.remaining());
            verify(client).query(1);
            try (var database = ArtworksBackFillDatabase.open(pool, target)) {
                assertEquals(dryRun || missingAi, database.currentCandidate(1).hasMissing());
            }
            try (var connection = pool.getConnection(); var statement = connection.createStatement();
                 var row = statement.executeQuery("SELECT r18,is_ai,description,deleted FROM artworks WHERE artwork_id=1")) {
                assertTrue(row.next());
                if (dryRun || softDeleted) assertNull(row.getObject("r18"));
                else assertEquals(concurrentR18 ? 0 : 1, row.getInt("r18"));
                if (missingAi) assertNull(row.getObject("is_ai"));
                else assertEquals(0, row.getInt("is_ai"));
                assertEquals(localDescription ? (dryRun ? null : "local") : "known", row.getString("description"));
                assertEquals(softDeleted ? 1 : 0, row.getInt("deleted"));
            }
            if (dryRun) assertFalse(java.nio.file.Files.exists(unreachable));
            assertEquals(0, pool.getHikariPoolMXBean().getActiveConnections());
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    @DisplayName("本地小说按上限发布真实阶段和作品计数，试运行保持数据库不变")
    void reportsLocalNovelProgress(boolean dryRun, @TempDir Path directory) throws Exception {
        var path = directory.resolve("progress.db");
        var target = top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.sqlite(path);
        var configuration = new HikariConfig();
        configuration.setJdbcUrl("jdbc:sqlite:" + path);
        configuration.setMaximumPoolSize(1);
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var curator = new top.sywyar.pixivdownload.core.metadata.sidecar.WorkMetaCurator(mapper);
        var client = mock(ArtworksBackFillPixivClient.class);
        try (var pool = new HikariDataSource(configuration);
             var files = mockStatic(RuntimeFiles.class);
             var clients = mockStatic(ArtworksBackFillPixivClient.class, invocation ->
                     invocation.getMethod().getName().equals("open") ? client : invocation.callRealMethod())) {
            files.when(RuntimeFiles::resolveBackfillUnreachablePath).thenReturn(directory.resolve("unreachable.json"));
            clients.when(() -> ArtworksBackFillPixivClient.open(any(), any())).thenReturn(client);
            try (var connection = pool.getConnection()) {
                top.sywyar.pixivdownload.core.db.schema.DatabaseInitializer.prepareOffline(connection, target);
                try (var statement = connection.prepareStatement("INSERT INTO novels(novel_id,title,folder,count,extensions,time,metadata_json) VALUES(?,'fixture','{0}',1,'txt',?,?)")) {
                    for (int id = 1; id <= 3; id++) {
                        var metadata = curator.curateNovel(id, mapper.readTree("{\"id\":" + id + ",\"uploadDate\":\"2020-02-03T12:00:00+09:00\"}"), "forward");
                        statement.setInt(1, id);
                        statement.setInt(2, id);
                        statement.setString(3, metadata.document().toString());
                        statement.executeUpdate();
                    }
                }
            }
            var progress = new java.util.ArrayList<ArtworksBackFill.Progress>();
            var options = new ArtworksBackFill.Options(path.toString(), "", 0, false, 0, 1, dryRun, target)
                    .withSources(directory.toString(), false);
            var result = ArtworksBackFill.runObserved(options, pool, progress::add);
            assertEquals(ArtworksBackFill.Phase.IMPORTING, progress.get(0).phase());
            assertNull(progress.get(0).total());
            assertTrue(progress.stream().anyMatch(value -> value.phase() == ArtworksBackFill.Phase.SCANNING && value.total() == null));
            assertTrue(progress.stream().noneMatch(value -> value.phase() == ArtworksBackFill.Phase.NETWORK));
            var last = progress.get(progress.size() - 1);
            assertEquals(1, last.total());
            assertEquals(1, last.processed());
            assertEquals(1, last.changed());
            assertEquals(0, last.remaining());
            assertEquals(1, result.filledUploadTime());
            verify(client, never()).queryNovel(anyLong());
            try (var connection = pool.getConnection(); var statement = connection.createStatement();
                 var rows = statement.executeQuery("SELECT COUNT(*) FROM novels WHERE upload_time IS NOT NULL")) {
                assertTrue(rows.next());
                assertEquals(dryRun ? 0 : 1, rows.getInt(1));
            }
            assertEquals(0, pool.getHikariPoolMXBean().getActiveConnections());
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"before-query", "dry-run", "deleted", "skip"})
    @DisplayName("原数据库租约失效后回填停止网络和不可达状态写入")
    void stopsSideEffectsAfterDatabaseLeaseLoss(String scenario, @TempDir Path directory) throws Exception {
        Path database = directory.resolve("lease-loss.db");
        Path unreachable = directory.resolve("unreachable.json");
        var config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + database);
        config.setMaximumPoolSize(1);
        var available = new AtomicBoolean(true);
        var client = mock(ArtworksBackFillPixivClient.class);
        try (var pool = new HikariDataSource(config);
             var files = mockStatic(RuntimeFiles.class);
             var clients = mockStatic(ArtworksBackFillPixivClient.class)) {
            files.when(RuntimeFiles::resolveBackfillUnreachablePath).thenReturn(unreachable);
            clients.when(() -> ArtworksBackFillPixivClient.open(any(), any())).thenReturn(client);
            try (var connection = pool.getConnection(); var statement = connection.createStatement()) {
                top.sywyar.pixivdownload.core.db.schema.DatabaseInitializer.prepareOffline(connection,
                        top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.sqlite(database));
                statement.executeUpdate("INSERT INTO artworks(artwork_id,title,folder,count,extensions,time)"
                        + " VALUES (1,'first','folder',1,'jpg',1), (2,'second','folder',1,'jpg',2)");
            }
            var leases = new org.springframework.jdbc.datasource.DelegatingDataSource(pool) {
                @Override public java.sql.Connection getConnection() throws java.sql.SQLException {
                    return top.sywyar.pixivdownload.core.db.provider.DatabaseConnectionLease.wrap(
                            super.getConnection(), available::get);
                }
            };
            if (!scenario.equals("before-query")) {
                when(client.query(org.mockito.ArgumentMatchers.anyLong())).thenAnswer(ignored -> {
                    available.set(false);
                    return switch (scenario) {
                        case "deleted" -> ArtworksBackFillPixivClient.LookupResult.deleted("fixture");
                        case "skip" -> ArtworksBackFillPixivClient.LookupResult.skip("fixture");
                        default -> ArtworksBackFillPixivClient.LookupResult.found(42L, "author", 0, false,
                                "description", List.of(), 0, 0, "");
                    };
                });
            }
            var options = new ArtworksBackFill.Options(database.toString(), "localhost", 1, false,
                    0, 0, scenario.equals("dry-run"), top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.sqlite(database)).withSources(database.getParent().toString(), false);

            var failure = assertThrows(java.sql.SQLNonTransientConnectionException.class,
                    () -> ArtworksBackFill.run(options, leases, (processed, total) -> {
                        if (scenario.equals("before-query")) available.set(false);
                    }));

            assertEquals("08003", failure.getSQLState());
            verify(client, times(scenario.equals("before-query") ? 0 : 1)).query(org.mockito.ArgumentMatchers.anyLong());
            assertFalse(java.nio.file.Files.exists(unreachable));
            assertEquals(0, pool.getHikariPoolMXBean().getActiveConnections());
            try (var connection = pool.getConnection(); var statement = connection.createStatement();
                 var row = statement.executeQuery("SELECT COUNT(*) FROM artworks WHERE author_id IS NULL")) {
                assertTrue(row.next());
                assertEquals(2, row.getInt(1));
            }
        }
    }

    @Test
    @DisplayName("真实代理返回 502 时停止整批，不继续请求或写入空元数据")
    void failedProxyResponseDoesNotFillMetadata(@TempDir Path directory) throws Exception {
        var database = directory.resolve("backfill.db");
        var config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + database);
        config.setMaximumPoolSize(1);
        try (var pool = new HikariDataSource(config);
             var proxy = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress());
             var files = mockStatic(RuntimeFiles.class)) {
            files.when(RuntimeFiles::resolveBackfillUnreachablePath).thenReturn(directory.resolve("unreachable.json"));
            try (var connection = pool.getConnection(); var statement = connection.createStatement()) {
                statement.executeUpdate("CREATE TABLE artworks (artwork_id INTEGER PRIMARY KEY, title TEXT NOT NULL,"
                        + " folder TEXT NOT NULL, count INTEGER NOT NULL, extensions TEXT NOT NULL, time INTEGER NOT NULL UNIQUE)");
                statement.executeUpdate("INSERT INTO artworks VALUES (1,'first','folder',1,'jpg',1),"
                        + " (2,'second','folder',1,'jpg',2), (3,'third','folder',1,'jpg',3)");
            }
            var request = new java.util.concurrent.FutureTask<String>(() -> {
                proxy.setSoTimeout(5000);
                try (var socket = proxy.accept()) {
                    socket.setSoTimeout(5000);
                    var reader = new java.io.BufferedReader(new java.io.InputStreamReader(
                            socket.getInputStream(), java.nio.charset.StandardCharsets.US_ASCII));
                    String firstLine = reader.readLine();
                    for (String line; (line = reader.readLine()) != null && !line.isEmpty();) { }
                    socket.getOutputStream().write(("HTTP/1.1 502 Bad Gateway\r\n"
                            + "Content-Type: application/json\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}")
                            .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                    return firstLine;
                }
            });
            var worker = new Thread(request, "backfill-test-proxy");
            worker.setDaemon(true);
            var options = new ArtworksBackFill.Options(database.toString(),
                    proxy.getInetAddress().getHostAddress(), proxy.getLocalPort(), true, 0, 0, false,
                    top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.sqlite(database)).withSources(database.getParent().toString(), false);
            var progress = new java.util.ArrayList<Integer>();
            assertThrows(java.io.IOException.class,
                    () -> ArtworksBackFill.run(options, pool, (processed, total) -> {
                        progress.add(processed);
                        if (worker.getState() == Thread.State.NEW) worker.start();
                    }));
            assertEquals("CONNECT www.pixiv.net:443 HTTP/1.1", request.get(5, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(progress.stream().allMatch(count -> count == 0));
            proxy.setSoTimeout(200);
            assertThrows(java.net.SocketTimeoutException.class, proxy::accept);
            assertFalse(java.nio.file.Files.exists(directory.resolve("unreachable.json")));
            try (var connection = pool.getConnection(); var statement = connection.createStatement();
                 var row = statement.executeQuery("SELECT author_id, R18, is_ai, description FROM artworks WHERE artwork_id=1")) {
                assertTrue(row.next());
                for (int column = 1; column <= 4; column++) assertNull(row.getObject(column));
            }
        }
    }

    @Test
    @DisplayName("请求完成后取消不再写入该作品，正常执行发布已完成计数")
    void cancelsBeforeWritingAndReportsCommittedProgress(@TempDir Path directory) throws Exception {
        var database = directory.resolve("backfill.db");
        var config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + database);
        config.setMaximumPoolSize(1);
        var queried = new AtomicBoolean();
        var client = mock(ArtworksBackFillPixivClient.class);
        when(client.query(1)).thenAnswer(ignored -> {
            queried.set(true);
            return ArtworksBackFillPixivClient.LookupResult.found(42L, "author", 0, false,
                    "description", List.of(), 0, 0, "");
        });
        try (var pool = new HikariDataSource(config);
             var files = mockStatic(RuntimeFiles.class);
             var clients = mockStatic(ArtworksBackFillPixivClient.class)) {
            files.when(RuntimeFiles::resolveBackfillUnreachablePath).thenReturn(directory.resolve("unreachable.json"));
            clients.when(() -> ArtworksBackFillPixivClient.open(any(), any())).thenReturn(client);
            try (var connection = pool.getConnection(); var statement = connection.createStatement()) {
                top.sywyar.pixivdownload.core.db.schema.DatabaseInitializer.prepareOffline(connection,
                        top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.sqlite(database));
                statement.executeUpdate("INSERT INTO artworks(artwork_id,title,folder,count,extensions,time)"
                        + " VALUES (1,'first','folder',1,'jpg',1)");
            }
            var options = new ArtworksBackFill.Options(database.toString(), "localhost", 1, false, 0, 0, false, top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.sqlite(database)).withSources(database.getParent().toString(), false);
            assertThrows(CancellationException.class, () -> ArtworksBackFill.run(options, pool, (processed, total) -> {
                assertEquals(1, total);
                assertEquals(0, processed);
                if (queried.get()) throw new CancellationException();
            }));
            try (var connection = pool.getConnection(); var statement = connection.createStatement();
                 var row = statement.executeQuery("SELECT author_id FROM artworks WHERE artwork_id=1")) {
                assertTrue(row.next());
                assertNull(row.getObject(1));
            }
            var counts = new java.util.ArrayList<Integer>();
            var result = ArtworksBackFill.run(options, pool, (processed, total) -> counts.add(processed));
            assertEquals(1, result.processed());
            assertEquals(1, counts.get(counts.size() - 1));
            try (var connection = pool.getConnection(); var statement = connection.createStatement();
                 var row = statement.executeQuery("SELECT author_id FROM artworks WHERE artwork_id=1")) {
                assertTrue(row.next());
                assertEquals(42, row.getLong(1));
            }
        }
    }
}
