package top.sywyar.pixivdownload.tools;

import top.sywyar.pixivdownload.core.db.DatabaseTarget;
import top.sywyar.pixivdownload.core.db.schema.DatabaseInitializer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import top.sywyar.pixivdownload.plugin.api.storage.relational.*;
import top.sywyar.pixivdownload.core.db.schema.CoreMigrationSchema;
import top.sywyar.pixivdownload.plugin.registry.schema.DatabaseSchemaRegistry;
import static top.sywyar.pixivdownload.plugin.api.storage.relational.Expression.*;
import static top.sywyar.pixivdownload.plugin.api.storage.relational.Expression.Operator.*;

/**
 * 管理作品回填工具的主库连接、候选查询与元数据写入。
 */
final class ArtworksBackFillDatabase implements AutoCloseable {

    private static final top.sywyar.pixivdownload.plugin.api.database.migration.MigrationSchema SCHEMA =
            CoreMigrationSchema.create(DatabaseSchemaRegistry.forBuiltInPlugins(), true);
    private final Connection connection;
    private final DatabaseTarget target;

    private ArtworksBackFillDatabase(Connection connection, DatabaseTarget target) {
        this.connection = connection;
        this.target = target;
    }

    static ArtworksBackFillDatabase open(DatabaseTarget target) throws SQLException {
        return prepare(target.openConnection(), target);
    }

    static ArtworksBackFillDatabase open(DataSource dataSource, DatabaseTarget target) throws SQLException {
        Connection connection = dataSource.getConnection();
        try {
            target.requireConnectionTarget(connection);
        } catch (SQLException | RuntimeException e) {
            try {
                connection.close();
            } catch (SQLException closeFailure) {
                e.addSuppressed(closeFailure);
            }
            throw e;
        }
        return prepare(connection, target);
    }

    private static ArtworksBackFillDatabase prepare(Connection connection, DatabaseTarget target) throws SQLException {
        try {
            ArtworksBackFillDatabase database = new ArtworksBackFillDatabase(connection, target);
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

    /** 复核已经借出的连接租约，不新借连接或改投其它目标。 */
    void requireAvailable() throws SQLException {
        connection.getAutoCommit();
    }

    FilteredCandidates findCandidates(
            int limit,
            ArtworksBackFillUnreachableStore unreachable
    ) throws SQLException {
        var tags = new Expression.Exists(new Query(new Query.Table("artwork_tags", "t"), List.of(),
                Map.of("present", value(1)), call(EQUAL, column("t", "artwork_id"), column("a", "artwork_id")),
                List.of(), null, List.of(), false, 1, 0));
        var fields = new LinkedHashMap<String, Expression>();
        for (String name : List.of("artwork_id", "author_id", "r18", "is_ai", "description", "series_id"))
            fields.put(name, column("a", name));
        fields.put("has_tags", tags);
        var missing = new ArrayList<Expression>();
        for (String name : List.of("author_id", "r18", "is_ai", "description", "series_id"))
            missing.add(call(IS_NULL, column("a", name)));
        missing.add(call(NOT, tags));
        var query = new Query(new Query.Table("artworks", "a"), List.of(), fields,
                call(AND, call(EQUAL, column("a", "deleted"), value(0)), call(OR, missing.toArray(Expression[]::new))),
                List.of(), null, List.of(new Query.Order(column("a", "artwork_id"), false, true)), false, Integer.MAX_VALUE, 0);
        List<Candidate> candidates = new ArrayList<>();
        int[] skippedUnreachable = {0};
        // 逐行过滤后再应用用户上限，不累计所有候选，也不把不可达集合展开为数据库参数。
        try (var session = target.storageSession(connection, SCHEMA)) {
            session.scan(query, row -> {
                long id = (Long) row.get("artwork_id");
                if (unreachable.contains(id)) { skippedUnreachable[0]++; return true; }
                candidates.add(new Candidate(id, row.get("author_id") == null, row.get("r18") == null,
                        row.get("is_ai") == null, row.get("description") == null,
                        Long.valueOf(0).equals(row.get("has_tags")), row.get("series_id") == null));
                return limit <= 0 || candidates.size() < limit;
            });
        }
        return new FilteredCandidates(candidates, skippedUnreachable[0]);
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
        boolean autoCommit = connection.getAutoCommit();
        if (autoCommit) connection.setAutoCommit(false);
        var savepoint = autoCommit ? null : connection.setSavepoint();
        try {
            if (autoCommit) target.prepareTransaction(connection, false);
            applyUpdatesInTransaction(candidate, result, updateAuthor, updateR18, updateAi,
                    updateDescription, updateTags, updateSeries);
            if (autoCommit) connection.commit();
            else connection.releaseSavepoint(savepoint);
        } catch (SQLException | RuntimeException failure) {
            if (autoCommit) connection.rollback();
            else connection.rollback(savepoint);
            throw failure;
        } finally {
            if (autoCommit) connection.setAutoCommit(true);
        }
    }

    private void applyUpdatesInTransaction(Candidate candidate, ArtworksBackFillPixivClient.LookupResult result,
            boolean updateAuthor, boolean updateR18, boolean updateAi, boolean updateDescription,
            boolean updateTags, boolean updateSeries) throws SQLException {
        var changes = new LinkedHashMap<String, Expression>();
        if (updateAuthor) changes.put("author_id", value(result.authorId));
        if (updateR18) changes.put("r18", value(result.xRestrict));
        if (updateAi) changes.put("is_ai", value(result.isAi ? 1 : 0));
        if (updateDescription) changes.put("description", value(result.description));
        if (updateSeries) {
            changes.put("series_id", value(result.seriesId));
            changes.put("series_order", value(result.seriesOrder));
        }
        try (var session = target.storageSession(connection, SCHEMA)) {
            if (!changes.isEmpty()) session.update("artworks", changes, identity("artworks", "artwork_id", candidate.artworkId()));
            if (updateAuthor && result.authorId > 0) upsertAuthor(session, result.authorId, result.authorName);
            if (updateTags && result.tags != null && !result.tags.isEmpty()) saveTags(session, candidate.artworkId(), result.tags);
            if (updateSeries && result.seriesId > 0 && result.seriesTitle != null)
                upsertSeries(session, result.seriesId, result.seriesTitle, result.authorId > 0 ? result.authorId : null);
        }
    }

    void applyR18Only(long artworkId) throws SQLException {
        try (var session = target.storageSession(connection, SCHEMA)) {
            session.update("artworks", Map.of("r18", value(1)), identity("artworks", "artwork_id", artworkId));
        }
    }

    private void ensureSchema() throws SQLException {
        if (!target.readSchema(connection).containsKey("artworks")) throw new SQLException("DATABASE_EXISTING_MAIN_REQUIRED", "42P01");
        DatabaseInitializer.prepareOffline(connection, target);
    }

    private static Expression identity(String table, String key, long id) {
        return call(EQUAL, column(table, key), value(id));
    }

    private static void saveTags(DatabaseSession session, long artworkId,
            List<ArtworksBackFillPixivClient.TagEntry> tags) throws SQLException {
        for (var tag : tags) {
            var values = new LinkedHashMap<String, Object>();
            values.put("name", tag.name()); values.put("translated_name", tag.translatedName());
            session.insert("tags", values, new DatabaseSession.Conflict(DatabaseSession.Mode.UPDATE, List.of("name"),
                    Map.of("translated_name", call(COALESCE, column("tags", "translated_name"), new Incoming("translated_name")))));
            var rows = session.query(new Query("tags", Map.of("id", column("tags", "tag_id")),
                    call(EQUAL, column("tags", "name"), value(tag.name())), List.of(), List.of(), 1, 0));
            if (!rows.isEmpty()) session.insert("artwork_tags", Map.of("artwork_id", artworkId, "tag_id", rows.get(0).get("id")),
                    DatabaseSession.Conflict.ignore());
        }
    }

    private static void upsertSeries(DatabaseSession session, long seriesId, String title, Long authorId) throws SQLException {
        long now = System.currentTimeMillis();
        var values = new LinkedHashMap<String, Object>();
        values.put("series_id", seriesId); values.put("title", title); values.put("author_id", authorId); values.put("updated_time", now);
        session.insert("manga_series", values, DatabaseSession.Conflict.ignore());
        var changed = call(NOT_EQUAL, column("manga_series", "title"), value(title));
        if (authorId != null) changed = call(OR, changed, call(IS_NULL, column("manga_series", "author_id")),
                call(NOT_EQUAL, column("manga_series", "author_id"), value(authorId)));
        session.update("manga_series", Map.of("title", value(title), "updated_time", value(now),
                        "author_id", call(COALESCE, value(authorId), column("manga_series", "author_id"))),
                call(AND, identity("manga_series", "series_id", seriesId), changed));
    }

    private static void upsertAuthor(DatabaseSession session, long authorId, String authorName) throws SQLException {
        long now = System.currentTimeMillis();
        session.insert("authors", Map.of("author_id", authorId, "name", authorName, "updated_time", now), DatabaseSession.Conflict.ignore());
        session.update("authors", Map.of("name", value(authorName), "updated_time", value(now)),
                call(AND, identity("authors", "author_id", authorId), call(NOT_EQUAL, column("authors", "name"), value(authorName))));
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
