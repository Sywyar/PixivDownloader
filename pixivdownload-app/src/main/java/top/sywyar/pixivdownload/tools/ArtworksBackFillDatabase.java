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
            backfillSchema();

    private static top.sywyar.pixivdownload.plugin.api.database.migration.MigrationSchema backfillSchema() {
        var tables = java.util.Set.of("artworks", "authors", "tags", "artwork_tags", "manga_series", "path_prefixes", "novels");
        var declared = CoreMigrationSchema.create(DatabaseSchemaRegistry.forBuiltInPlugins(), true);
        return new top.sywyar.pixivdownload.plugin.api.database.migration.MigrationSchema(
                declared.formatVersion(), declared.tables().stream().filter(table -> tables.contains(table.name())).toList(),
                List.of(), List.of());
    }
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
        } catch (SQLException | RuntimeException | Error e) {
            try {
                connection.close();
            } catch (VirtualMachineError | ThreadDeath fatal) {
                if (fatal != e) fatal.addSuppressed(e);
                throw fatal;
            } catch (SQLException | RuntimeException | Error closeFailure) {
                if (closeFailure != e) e.addSuppressed(closeFailure);
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
        } catch (SQLException | RuntimeException | Error e) {
            try {
                connection.close();
            } catch (VirtualMachineError | ThreadDeath fatal) {
                if (fatal != e) fatal.addSuppressed(e);
                throw fatal;
            } catch (SQLException | RuntimeException | Error closeFailure) {
                if (closeFailure != e) e.addSuppressed(closeFailure);
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
        return findCandidates(limit, unreachable, null);
    }

    FilteredCandidates findCandidates(int limit, ArtworksBackFillUnreachableStore unreachable, String rootFolder)
            throws SQLException {
        List<Candidate> candidates = new ArrayList<>();
        int[] skippedUnreachable = {0};
        long afterId = 0;
        while (limit <= 0 || candidates.size() < limit) {
            Candidate[] localCheck = {null};
            // 先关闭候选游标，再单独读取快照；不把整批 JSON 留在查询结果或候选列表中。
            try (var session = target.storageSession(connection, SCHEMA)) {
                session.scan(candidateQuery(call(GREATER, column("a", "artwork_id"), value(afterId))), row -> {
                    var candidate = candidateFromRow(row);
                    if (unreachable.contains(candidate.artworkId())) {
                        if (rootFolder != null) {
                            localCheck[0] = candidate;
                            return false;
                        }
                        skippedUnreachable[0]++;
                    } else candidates.add(candidate);
                    return limit <= 0 || candidates.size() < limit;
                });
            }
            if (localCheck[0] == null) break;
            var candidate = localCheck[0];
            afterId = candidate.artworkId();
            var local = localMetadata(candidate.artworkId(), rootFolder);
            if (local != null && candidate.availableFields(local).hasMissing()) candidates.add(candidate);
            else skippedUnreachable[0]++;
        }
        return new FilteredCandidates(candidates, skippedUnreachable[0]);
    }

    Candidate currentCandidate(long artworkId) throws SQLException {
        try (var session = target.storageSession(connection, SCHEMA)) {
            var rows = session.query(candidateQuery(identity("a", "artwork_id", artworkId)));
            return rows.isEmpty() ? new Candidate(artworkId, false, false, false, false, false, false, false)
                    : candidateFromRow(rows.get(0));
        }
    }

    private static Query candidateQuery(Expression filter) {
        var tags = new Expression.Exists(new Query(new Query.Table("artwork_tags", "t"), List.of(),
                Map.of("present", value(1)), call(EQUAL, column("t", "artwork_id"), column("a", "artwork_id")),
                List.of(), null, List.of(), false, 1, 0));
        var fields = new LinkedHashMap<String, Expression>();
        var authorName = new Expression.Exists(new Query("authors", Map.of("present", value(1)),
                call(AND, call(EQUAL, column("authors", "author_id"), column("a", "author_id")),
                        call(NOT, call(IS_NULL, column("authors", "name")))), List.of(), List.of(), 1, 0));
        var seriesTitle = new Expression.Exists(new Query("manga_series", Map.of("present", value(1)),
                call(AND, call(EQUAL, column("manga_series", "series_id"), column("a", "series_id")),
                        call(NOT, call(IS_NULL, column("manga_series", "title")))), List.of(), List.of(), 1, 0));
        for (String name : List.of("artwork_id", "author_id", "r18", "is_ai", "description", "series_id", "series_order", "upload_time"))
            fields.put(name, column("a", name));
        fields.put("has_tags", tags);
        fields.put("has_author_name", authorName);
        fields.put("has_series_title", seriesTitle);
        var missing = new ArrayList<Expression>();
        for (String name : List.of("author_id", "r18", "is_ai", "description", "series_id", "upload_time"))
            missing.add(call(IS_NULL, column("a", name)));
        missing.add(call(NOT, tags));
        missing.add(call(AND, call(GREATER, column("a", "author_id"), value(0)), call(NOT, authorName)));
        missing.add(call(AND, call(GREATER, column("a", "series_id"), value(0)),
                call(OR, call(IS_NULL, column("a", "series_order")), call(NOT, seriesTitle))));
        return new Query(new Query.Table("artworks", "a"), List.of(), fields,
                call(AND, filter, call(EQUAL, column("a", "deleted"), value(0)), call(OR, missing.toArray(Expression[]::new))),
                List.of(), null, List.of(new Query.Order(column("a", "artwork_id"), false, true)), false, Integer.MAX_VALUE, 0);
    }

    private static Candidate candidateFromRow(Map<String, Object> row) {
        long id = (Long) row.get("artwork_id");
        Long authorId = (Long) row.get("author_id"), seriesId = (Long) row.get("series_id");
        boolean nameMissing = !Long.valueOf(1).equals(row.get("has_author_name"));
        boolean orderMissing = seriesId == null || (seriesId > 0 && row.get("series_order") == null);
        boolean titleMissing = seriesId == null || (seriesId > 0 && !Long.valueOf(1).equals(row.get("has_series_title")));
        return new Candidate(id, authorId == null || (authorId > 0 && nameMissing), row.get("r18") == null,
                row.get("is_ai") == null, row.get("description") == null,
                Long.valueOf(0).equals(row.get("has_tags")), seriesId == null || orderMissing || titleMissing,
                row.get("upload_time") == null, authorId, seriesId, nameMissing, orderMissing, titleMissing);
    }

    UpdateResult applyUpdates(
            Candidate candidate,
            ArtworksBackFillPixivClient.LookupResult result,
            boolean updateAuthor,
            boolean updateR18,
            boolean updateAi,
            boolean updateDescription,
            boolean updateTags,
            boolean updateSeries
    ) throws SQLException {
        return applyUpdates(candidate, result, updateAuthor, updateR18, updateAi, updateDescription, updateTags, updateSeries, false);
    }

    record UpdateResult(boolean author, boolean series) {}

    /** 返回作者实际补入结果及系列身份条件是否命中；所有字段只补缺失。 */
    UpdateResult applyUpdates(Candidate candidate, ArtworksBackFillPixivClient.LookupResult result,
            boolean updateAuthor, boolean updateR18, boolean updateAi, boolean updateDescription,
            boolean updateTags, boolean updateSeries, boolean updateUploadTime) throws SQLException {
        var transaction = target.beginToolTransaction(connection);
        Throwable failure = null;
        try {
            var applied = applyUpdatesInTransaction(candidate, result, updateAuthor, updateR18, updateAi,
                    updateDescription, updateTags, updateSeries, updateUploadTime);
            transaction.commit();
            return applied;
        } catch (SQLException | RuntimeException | Error e) {
            failure = e;
            throw e;
        } finally {
            transaction.finish(failure);
        }
    }

    private UpdateResult applyUpdatesInTransaction(Candidate candidate, ArtworksBackFillPixivClient.LookupResult result,
            boolean updateAuthor, boolean updateR18, boolean updateAi, boolean updateDescription,
            boolean updateTags, boolean updateSeries, boolean updateUploadTime) throws SQLException {
        var changes = new LinkedHashMap<String, Expression>();
        if (updateR18) changes.put("r18", value(result.xRestrict));
        if (updateAi) changes.put("is_ai", value(result.isAi ? 1 : 0));
        if (updateDescription) changes.put("description", value(result.description));
        if (updateUploadTime) changes.put("upload_time", value(result.uploadTime));
        // 候选预览后仍可能有新写入；SQL 内只填 NULL，避免覆盖并发保存的值。
        changes.replaceAll((name, expression) -> call(COALESCE, column("artworks", name), expression));
        try (var session = target.storageSession(connection, SCHEMA)) {
            var alive = call(AND, identity("artworks", "artwork_id", candidate.artworkId()),
                    call(EQUAL, column("artworks", "deleted"), value(0)));
            if (session.query(Query.count("artworks", alive)).get(0).get("count").equals(0L)) return new UpdateResult(false, false);
            if (!changes.isEmpty()) session.update("artworks", changes, alive);
            boolean authorWritten = false;
            if (updateAuthor) {
                authorWritten = session.update("artworks", Map.of("author_id", value(result.authorId)),
                        call(AND, alive, call(IS_NULL, column("artworks", "author_id")))) > 0;
                var sameAuthor = call(AND, alive, call(EQUAL, column("artworks", "author_id"), value(result.authorId)));
                if (result.authorId > 0 && result.authorName != null
                        && session.query(Query.count("artworks", sameAuthor)).get(0).get("count").equals(1L))
                    authorWritten = upsertAuthor(session, result.authorId, result.authorName) || authorWritten;
            }
            if (updateTags && result.tags != null && !result.tags.isEmpty()
                    && session.query(Query.count("artwork_tags", identity("artwork_tags", "artwork_id", candidate.artworkId())))
                    .get(0).get("count").equals(0L)) saveTags(session, candidate.artworkId(), result.tags);
            if (updateSeries) {
                var seriesChanges = new LinkedHashMap<String, Expression>();
                seriesChanges.put("series_id", call(COALESCE, column("artworks", "series_id"), value(result.seriesId)));
                if (result.seriesOrder != null)
                    seriesChanges.put("series_order", call(COALESCE, column("artworks", "series_order"), value(result.seriesOrder)));
                // 身份条件与章节顺序同语句生效；命中后由同一写事务保护关联资料。
                int changed = session.update("artworks", seriesChanges, call(AND, alive,
                        call(OR, call(IS_NULL, column("artworks", "series_id")),
                                call(EQUAL, column("artworks", "series_id"), value(result.seriesId)))));
                if (changed > 0 && result.seriesId > 0 && result.seriesTitle != null)
                    upsertSeries(session, result.seriesId, result.seriesTitle, result.authorId > 0 ? result.authorId : null);
                return new UpdateResult(authorWritten, changed > 0);
            }
            return new UpdateResult(authorWritten, false);
        }
    }

    void applyR18Only(long artworkId) throws SQLException {
        try (var session = target.storageSession(connection, SCHEMA)) {
            session.update("artworks", Map.of("r18", call(COALESCE, column("artworks", "r18"), value(1))),
                    call(AND, identity("artworks", "artwork_id", artworkId), call(EQUAL, column("artworks", "deleted"), value(0))));
        }
    }

    void importMetadata(String rootFolder, boolean dryRun, java.util.function.Consumer<String> reporter)
            throws SQLException, InterruptedException {
        top.sywyar.pixivdownload.migration.WorkMetadataImport.run(connection, target, rootFolder, dryRun, false, reporter);
    }

    ArtworksBackFillPixivClient.LookupResult localMetadata(long id, String rootFolder) throws SQLException {
        try (var session = target.storageSession(connection, SCHEMA)) {
            var rows = session.query(new Query("artworks", Map.of("metadata_json", column("artworks", "metadata_json")),
                    identity("artworks", "artwork_id", id), List.of(), List.of(), 1, 0));
            if (rows.isEmpty()) return null;
            try {
                var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                var meta = rows.get(0).get("metadata_json") == null
                        ? top.sywyar.pixivdownload.migration.WorkMetadataImport.readRegisteredSnapshot(connection, target, rootFolder, id)
                        : new top.sywyar.pixivdownload.core.metadata.sidecar.WorkMetaCurator(mapper)
                        .restore(top.sywyar.pixivdownload.core.work.model.WorkType.ARTWORK, id,
                                mapper.readTree((String) rows.get(0).get("metadata_json")));
                if (meta == null) return null;
                return ArtworksBackFillPixivClient.parsePayload(meta.document().path("raw"), meta.uploadTime(), false);
            } catch (java.io.IOException | IllegalArgumentException invalid) {
                return null;
            }
        }
    }

    private void ensureSchema() throws SQLException {
        if (!target.readSchema(connection).containsKey("artworks")) throw new SQLException("DATABASE_EXISTING_MAIN_REQUIRED", "42P01");
        DatabaseInitializer.prepareOffline(connection, target, SCHEMA);
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
        var changed = call(IS_NULL, column("manga_series", "title"));
        if (authorId != null) changed = call(OR, changed, call(IS_NULL, column("manga_series", "author_id")));
        session.update("manga_series", Map.of("title", call(COALESCE, column("manga_series", "title"), value(title)),
                        "updated_time", value(now),
                        "author_id", call(COALESCE, column("manga_series", "author_id"), value(authorId))),
                call(AND, identity("manga_series", "series_id", seriesId), changed));
    }

    private static boolean upsertAuthor(DatabaseSession session, long authorId, String authorName) throws SQLException {
        long now = System.currentTimeMillis();
        int inserted = session.insert("authors", Map.of("author_id", authorId, "name", authorName, "updated_time", now), DatabaseSession.Conflict.ignore());
        int updated = session.update("authors", Map.of("name", value(authorName), "updated_time", value(now)),
                call(AND, identity("authors", "author_id", authorId), call(IS_NULL, column("authors", "name"))));
        return inserted > 0 || updated > 0;
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
            boolean seriesMissing,
            boolean uploadTimeMissing,
            Long authorId,
            Long seriesId,
            boolean authorNameMissing,
            boolean seriesOrderMissing,
            boolean seriesTitleMissing
    ) {
        Candidate(long id, boolean author, boolean r18, boolean ai, boolean description, boolean tags, boolean series, boolean uploadTime) {
            this(id, author, r18, ai, description, tags, series, uploadTime, null, null, author, series, series);
        }
        Candidate(long id, boolean author, boolean r18, boolean ai, boolean description, boolean tags, boolean series) {
            this(id, author, r18, ai, description, tags, series, false);
        }
        boolean hasMissing() {
            return authorMissing || r18Missing || aiMissing || descriptionMissing || tagsMissing || seriesMissing || uploadTimeMissing;
        }

        Candidate availableFields(ArtworksBackFillPixivClient.LookupResult result) {
            boolean author = authorMissing && result.authorId > 0
                    && (authorId == null || authorId == result.authorId)
                    && (authorId == null || (authorNameMissing && result.authorName != null));
            boolean series = seriesMissing && result.seriesKnown
                    && (seriesId == null || seriesId == result.seriesId)
                    && (seriesId == null || (seriesOrderMissing && result.seriesOrder != null)
                        || (seriesTitleMissing && result.seriesTitle != null));
            return new Candidate(artworkId, author, r18Missing && result.xRestrict != null,
                    aiMissing && result.isAi != null, descriptionMissing && result.description != null,
                    tagsMissing && result.tags != null && !result.tags.isEmpty(), series,
                    uploadTimeMissing && result.uploadTime != null && result.uploadTime > 0);
        }
    }
}
