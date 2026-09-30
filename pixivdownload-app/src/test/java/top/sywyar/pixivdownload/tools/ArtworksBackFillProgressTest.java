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
                statement.executeUpdate("CREATE TABLE artworks (artwork_id INTEGER PRIMARY KEY)");
                statement.executeUpdate("INSERT INTO artworks VALUES (1)");
            }
            var options = new ArtworksBackFill.Options(database.toString(), "localhost", 1, false, 0, 0, false);
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
