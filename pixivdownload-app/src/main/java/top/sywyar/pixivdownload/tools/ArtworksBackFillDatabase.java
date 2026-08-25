package top.sywyar.pixivdownload.tools;

import org.sqlite.SQLiteConfig;
import top.sywyar.pixivdownload.i18n.MessageBundles;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 管理作品回填工具的 SQLite 连接、候选查询与元数据写入。
 */
final class ArtworksBackFillDatabase implements AutoCloseable {

    private final Connection connection;

    private ArtworksBackFillDatabase(Connection connection) {
        this.connection = connection;
    }

    static ArtworksBackFillDatabase open(String dbPath) throws SQLException {
        SQLiteConfig sqliteConfig = new SQLiteConfig();
        sqliteConfig.setBusyTimeout(5000);
        sqliteConfig.setJournalMode(SQLiteConfig.JournalMode.WAL);
        Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + dbPath,
                sqliteConfig.toProperties()
        );
        return prepare(connection);
    }

    static ArtworksBackFillDatabase open(DataSource dataSource, String expectedDbPath) throws SQLException {
        Connection connection = dataSource.getConnection();
        try {
            String prefix = "jdbc:sqlite:";
            String url = connection.getMetaData().getURL();
            Path expected = Path.of(expectedDbPath).toAbsolutePath().normalize();
            Path actual = url != null && url.startsWith(prefix)
                    ? Path.of(url.substring(prefix.length())).toAbsolutePath().normalize()
                    : null;
            if (!expected.equals(actual)) {
                throw new SQLException(MessageBundles.get(
                        "artworks-backfill.database.active-path-mismatch",
                        expected,
                        actual == null ? url : actual
                ));
            }
        } catch (SQLException | RuntimeException e) {
            try {
                connection.close();
            } catch (SQLException closeFailure) {
                e.addSuppressed(closeFailure);
            }
            throw e;
        }
        return prepare(connection);
    }

    private static ArtworksBackFillDatabase prepare(Connection connection) throws SQLException {
        try {
            ArtworksBackFillDatabase database = new ArtworksBackFillDatabase(connection);
            database.ensureSchema();
            return database;
        } catch (SQLException | RuntimeException e) {
            try {
                connection.close();
            } catch (SQLException closeFailure) {
                e.addSuppressed(closeFailure);
            }
            throw e;
        }
    }

    int countCandidates(int limit, ArtworksBackFillUnreachableStore unreachable) throws SQLException {
        return findCandidates(limit, unreachable).candidates().size();
    }

    FilteredCandidates findCandidates(
            int limit,
            ArtworksBackFillUnreachableStore unreachable
    ) throws SQLException {
        // 在内存中过滤已知不可达 ID，避免 SQL IN 受 SQLite 参数上限约束；为了在 limit 截断前剔除它们，这里不再下推 LIMIT。
        String sql = "SELECT a.artwork_id, a.author_id, a.\"R18\", a.is_ai, a.description,"
                + " a.series_id,"
                + " (SELECT 1 FROM artwork_tags t WHERE t.artwork_id = a.artwork_id LIMIT 1) AS has_tags"
                + " FROM artworks a"
                + " WHERE a.deleted = 0"
                + " AND (a.author_id IS NULL OR a.\"R18\" IS NULL OR a.is_ai IS NULL OR a.description IS NULL"
                + " OR a.series_id IS NULL"
                + " OR NOT EXISTS (SELECT 1 FROM artwork_tags t WHERE t.artwork_id = a.artwork_id))"
                + " ORDER BY a.artwork_id";

        List<Candidate> candidates = new ArrayList<>();
        int skippedUnreachable = 0;
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                long artworkId = resultSet.getLong(1);
                if (unreachable.contains(artworkId)) {
                    skippedUnreachable++;
                    continue;
                }
                candidates.add(new Candidate(
                        artworkId,
                        resultSet.getObject(2) == null,
                        resultSet.getObject(3) == null,
                        resultSet.getObject(4) == null,
                        resultSet.getObject(5) == null,
                        resultSet.getObject(7) == null,
                        resultSet.getObject(6) == null
                ));
                if (limit > 0 && candidates.size() >= limit) {
                    break;
                }
            }
        }
        return new FilteredCandidates(candidates, skippedUnreachable);
    }

    void applyUpdates(
            Candidate candidate,
            ArtworksBackFillPixivClient.LookupResult result,
            boolean updateAuthor,
            boolean updateR18,
            boolean updateAi,
            boolean updateDescription,
            boolean updateTags,
            boolean updateSeries
    ) throws SQLException {
        List<String> sets = new ArrayList<>(6);
        if (updateAuthor) sets.add("author_id = ?");
        if (updateR18) sets.add("\"R18\" = ?");
        if (updateAi) sets.add("is_ai = ?");
        if (updateDescription) sets.add("description = ?");
        if (updateSeries) {
            sets.add("series_id = ?");
            sets.add("series_order = ?");
        }

        if (!sets.isEmpty()) {
            String sql = "UPDATE artworks SET " + String.join(", ", sets) + " WHERE artwork_id = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                int index = 1;
                if (updateAuthor) statement.setLong(index++, result.authorId);
                if (updateR18) statement.setInt(index++, result.xRestrict);
                if (updateAi) statement.setInt(index++, result.isAi ? 1 : 0);
                if (updateDescription) statement.setString(index++, result.description);
                if (updateSeries) {
                    statement.setLong(index++, result.seriesId);
                    statement.setLong(index++, result.seriesOrder);
                }
                statement.setLong(index, candidate.artworkId());
                statement.executeUpdate();
            }
        }

        if (updateAuthor && result.authorId > 0) {
            upsertAuthor(result.authorId, result.authorName);
        }
        if (updateTags && result.tags != null && !result.tags.isEmpty()) {
            saveTags(candidate.artworkId(), result.tags);
        }
        if (updateSeries && result.seriesId > 0 && result.seriesTitle != null) {
            upsertSeries(
                    result.seriesId,
                    result.seriesTitle,
                    result.authorId > 0 ? result.authorId : null
            );
        }
    }

    void applyR18Only(long artworkId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE artworks SET \"R18\" = 1 WHERE artwork_id = ?")) {
            statement.setLong(1, artworkId);
            statement.executeUpdate();
        }
    }

    private void ensureSchema() throws SQLException {
        execute("CREATE TABLE IF NOT EXISTS authors ("
                + "author_id INTEGER PRIMARY KEY,"
                + "name TEXT NOT NULL,"
                + "updated_time INTEGER NOT NULL)");
        execute("CREATE TABLE IF NOT EXISTS tags ("
                + "tag_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "name TEXT NOT NULL UNIQUE,"
                + "translated_name TEXT)");
        execute("CREATE TABLE IF NOT EXISTS artwork_tags ("
                + "artwork_id INTEGER NOT NULL,"
                + "tag_id INTEGER NOT NULL,"
                + "PRIMARY KEY (artwork_id, tag_id))");
        execute("CREATE INDEX IF NOT EXISTS idx_artwork_tags_tag_id ON artwork_tags(tag_id)");
        execute("CREATE TABLE IF NOT EXISTS manga_series ("
                + "series_id INTEGER PRIMARY KEY,"
                + "title TEXT NOT NULL,"
                + "author_id INTEGER,"
                + "updated_time INTEGER NOT NULL)");
        addColumnIfMissing("ALTER TABLE artworks ADD COLUMN author_id INTEGER DEFAULT NULL");
        addColumnIfMissing("ALTER TABLE artworks ADD COLUMN \"R18\" INTEGER DEFAULT NULL");
        addColumnIfMissing("ALTER TABLE artworks ADD COLUMN is_ai INTEGER DEFAULT NULL");
        addColumnIfMissing("ALTER TABLE artworks ADD COLUMN description TEXT DEFAULT NULL");
        addColumnIfMissing("ALTER TABLE artworks ADD COLUMN series_id INTEGER DEFAULT NULL");
        addColumnIfMissing("ALTER TABLE artworks ADD COLUMN series_order INTEGER DEFAULT NULL");
        addColumnIfMissing("ALTER TABLE artworks ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0");
    }

    private void execute(String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
        }
    }

    private void addColumnIfMissing(String ddl) {
        try {
            execute(ddl);
        } catch (SQLException ignored) {
            // 列已存在时直接忽略，行为与运行时迁移保持一致。
        }
    }

    private void saveTags(
            long artworkId,
            List<ArtworksBackFillPixivClient.TagEntry> tags
    ) throws SQLException {
        try (PreparedStatement upsertTag = connection.prepareStatement(
                "INSERT INTO tags(name, translated_name) VALUES(?, ?)"
                        + " ON CONFLICT(name) DO UPDATE SET"
                        + " translated_name = COALESCE(tags.translated_name, excluded.translated_name)");
             PreparedStatement selectTag = connection.prepareStatement(
                     "SELECT tag_id FROM tags WHERE name = ?");
             PreparedStatement linkTag = connection.prepareStatement(
                     "INSERT OR IGNORE INTO artwork_tags(artwork_id, tag_id) VALUES(?, ?)")) {
            for (ArtworksBackFillPixivClient.TagEntry tag : tags) {
                upsertTag.setString(1, tag.name());
                if (tag.translatedName() == null) {
                    upsertTag.setNull(2, java.sql.Types.VARCHAR);
                } else {
                    upsertTag.setString(2, tag.translatedName());
                }
                upsertTag.executeUpdate();

                selectTag.setString(1, tag.name());
                try (ResultSet resultSet = selectTag.executeQuery()) {
                    if (!resultSet.next()) {
                        continue;
                    }
                    linkTag.setLong(1, artworkId);
                    linkTag.setLong(2, resultSet.getLong(1));
                    linkTag.executeUpdate();
                }
            }
        }
    }

    private void upsertSeries(long seriesId, String title, Long authorId) throws SQLException {
        long nowMillis = System.currentTimeMillis();
        // 与 MangaSeriesService.observe 对齐：title 或 author 任一变化都触发 update。
        // 之前 WHERE 含 `AND title <> ?` 会让仅 author 变化的场景无更新，导致回填工具落后于运行时。
        try (PreparedStatement insertSeries = connection.prepareStatement(
                "INSERT OR IGNORE INTO manga_series(series_id, title, author_id, updated_time) VALUES(?, ?, ?, ?)");
             PreparedStatement updateSeries = connection.prepareStatement(
                     "UPDATE manga_series SET title = ?, author_id = COALESCE(?, author_id),"
                             + " updated_time = ? WHERE series_id = ?"
                             + " AND (title <> ? OR (? IS NOT NULL AND (author_id IS NULL OR author_id <> ?)))")) {
            insertSeries.setLong(1, seriesId);
            insertSeries.setString(2, title);
            if (authorId == null) {
                insertSeries.setNull(3, java.sql.Types.INTEGER);
            } else {
                insertSeries.setLong(3, authorId);
            }
            insertSeries.setLong(4, nowMillis);
            insertSeries.executeUpdate();

            updateSeries.setString(1, title);
            if (authorId == null) {
                updateSeries.setNull(2, java.sql.Types.INTEGER);
                updateSeries.setNull(6, java.sql.Types.INTEGER);
                updateSeries.setNull(7, java.sql.Types.INTEGER);
            } else {
                updateSeries.setLong(2, authorId);
                updateSeries.setLong(6, authorId);
                updateSeries.setLong(7, authorId);
            }
            updateSeries.setLong(3, nowMillis);
            updateSeries.setLong(4, seriesId);
            updateSeries.setString(5, title);
            updateSeries.executeUpdate();
        }
    }

    private void upsertAuthor(long authorId, String authorName) throws SQLException {
        long nowMillis = System.currentTimeMillis();
        try (PreparedStatement insertAuthor = connection.prepareStatement(
                "INSERT OR IGNORE INTO authors(author_id, name, updated_time) VALUES(?, ?, ?)");
             PreparedStatement updateAuthor = connection.prepareStatement(
                     "UPDATE authors SET name = ?, updated_time = ? WHERE author_id = ? AND name <> ?")) {
            insertAuthor.setLong(1, authorId);
            insertAuthor.setString(2, authorName);
            insertAuthor.setLong(3, nowMillis);
            insertAuthor.executeUpdate();

            updateAuthor.setString(1, authorName);
            updateAuthor.setLong(2, nowMillis);
            updateAuthor.setLong(3, authorId);
            updateAuthor.setString(4, authorName);
            updateAuthor.executeUpdate();
        }
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }

    record FilteredCandidates(List<Candidate> candidates, int skippedUnreachable) {}

    record Candidate(
            long artworkId,
            boolean authorMissing,
            boolean r18Missing,
            boolean aiMissing,
            boolean descriptionMissing,
            boolean tagsMissing,
            boolean seriesMissing
    ) {}
}
