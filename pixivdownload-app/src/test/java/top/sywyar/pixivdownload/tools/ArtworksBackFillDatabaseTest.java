package top.sywyar.pixivdownload.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("作品回填数据库")
class ArtworksBackFillDatabaseTest {

    @Test
    @DisplayName("过滤不可达作品并持久化回填元数据")
    void filtersCandidatesAndPersistsMetadata(@TempDir Path tempDir) throws Exception {
        Path databasePath = tempDir.resolve("backfill.db");
        String jdbcUrl = "jdbc:sqlite:" + databasePath;
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE artworks (artwork_id INTEGER PRIMARY KEY)");
            statement.executeUpdate("INSERT INTO artworks(artwork_id) VALUES(1), (2), (3), (4)");
        }
        try (ArtworksBackFillDatabase ignored = ArtworksBackFillDatabase.open(databasePath.toString())) {
            // 初始化工具兼容的 schema。
        }
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE artworks SET author_id = 3, \"R18\" = 0, is_ai = 0,"
                    + " description = 'complete', series_id = 30 WHERE artwork_id = 3");
            statement.executeUpdate("INSERT INTO tags(name) VALUES('existing')");
            statement.executeUpdate("INSERT INTO artwork_tags(artwork_id, tag_id)"
                    + " SELECT 3, tag_id FROM tags WHERE name = 'existing'");
        }

        ArtworksBackFillUnreachableStore unreachable = ArtworksBackFillUnreachableStore.load(
                tempDir.resolve("unreachable.json"),
                new ObjectMapper()
        );
        unreachable.record(1, "HTTP 404");

        try (ArtworksBackFillDatabase database = ArtworksBackFillDatabase.open(databasePath.toString())) {
            ArtworksBackFillDatabase.FilteredCandidates filtered = database.findCandidates(1, unreachable);
            assertEquals(1, filtered.skippedUnreachable());
            assertEquals(1, filtered.candidates().size());
            ArtworksBackFillDatabase.Candidate candidate = filtered.candidates().get(0);
            assertEquals(2L, candidate.artworkId());
            assertTrue(candidate.authorMissing());
            assertTrue(candidate.tagsMissing());

            ArtworksBackFillPixivClient.LookupResult result = ArtworksBackFillPixivClient.LookupResult.found(
                    42L,
                    "author",
                    1,
                    true,
                    "description",
                    List.of(new ArtworksBackFillPixivClient.TagEntry("new-tag", "New Tag")),
                    7L,
                    4L,
                    "series"
            );
            database.applyUpdates(candidate, result, true, true, true, true, true, true);
            database.applyR18Only(4L);
        }

        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             Statement statement = connection.createStatement()) {
            try (ResultSet resultSet = statement.executeQuery(
                    "SELECT author_id, \"R18\", is_ai, description, series_id, series_order"
                            + " FROM artworks WHERE artwork_id = 2")) {
                assertTrue(resultSet.next());
                assertEquals(42L, resultSet.getLong("author_id"));
                assertEquals(1, resultSet.getInt("R18"));
                assertEquals(1, resultSet.getInt("is_ai"));
                assertEquals("description", resultSet.getString("description"));
                assertEquals(7L, resultSet.getLong("series_id"));
                assertEquals(4L, resultSet.getLong("series_order"));
            }
            assertEquals("author", queryString(statement, "SELECT name FROM authors WHERE author_id = 42"));
            assertEquals("series", queryString(statement, "SELECT title FROM manga_series WHERE series_id = 7"));
            assertEquals("new-tag", queryString(statement,
                    "SELECT t.name FROM tags t JOIN artwork_tags at ON at.tag_id = t.tag_id"
                            + " WHERE at.artwork_id = 2"));
            assertEquals(1, queryInt(statement, "SELECT \"R18\" FROM artworks WHERE artwork_id = 4"));
        }
    }

    private static String queryString(Statement statement, String sql) throws Exception {
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            assertTrue(resultSet.next());
            return resultSet.getString(1);
        }
    }

    private static int queryInt(Statement statement, String sql) throws Exception {
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            assertTrue(resultSet.next());
            return resultSet.getInt(1);
        }
    }
}
