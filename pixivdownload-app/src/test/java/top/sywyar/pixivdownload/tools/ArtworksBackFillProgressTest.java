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
                    0, 0, scenario.equals("dry-run"), top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.sqlite(database));

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
            var options = new ArtworksBackFill.Options(database.toString(), "localhost", 1, false, 0, 0, false, top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.sqlite(database));
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
