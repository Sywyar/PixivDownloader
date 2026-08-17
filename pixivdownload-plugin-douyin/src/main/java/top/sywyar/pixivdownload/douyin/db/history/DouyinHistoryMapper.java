package top.sywyar.pixivdownload.douyin.db.history;

import top.sywyar.pixivdownload.plugin.api.storage.PluginDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/** Plain-JDBC access to the Douyin plugin's private database. */
public class DouyinHistoryMapper {

    private static final String SELECT_WORK = "SELECT work_id, title, folder, count, extensions, time, deleted, kind,"
            + " source_url, canonical_url, thumbnail_url, author_id, author_name, description,"
            + " item_title, caption, publish_time, collection_id, collection_title, collection_order"
            + " FROM douyin_works";
    private static final String SELECT_FILE = "SELECT work_id, file_index, media_id, media_type, file_name,"
            + " extension, bytes, content_type, created_time FROM douyin_work_files";
    private static final List<String> SCHEMA = List.of(
            "CREATE TABLE IF NOT EXISTS douyin_works ("
                    + "work_id TEXT PRIMARY KEY, title TEXT NOT NULL, folder TEXT NOT NULL, count INTEGER NOT NULL,"
                    + " extensions TEXT NOT NULL, time INTEGER NOT NULL UNIQUE, deleted INTEGER NOT NULL DEFAULT 0,"
                    + " kind TEXT NOT NULL, source_url TEXT, canonical_url TEXT, thumbnail_url TEXT, author_id TEXT,"
                    + " author_name TEXT, description TEXT, item_title TEXT, caption TEXT, publish_time INTEGER,"
                    + " collection_id TEXT, collection_title TEXT, collection_order INTEGER)",
            "CREATE INDEX IF NOT EXISTS idx_douyin_works_author_time ON douyin_works(author_id, time)",
            "CREATE INDEX IF NOT EXISTS idx_douyin_works_collection_order"
                    + " ON douyin_works(collection_id, collection_order)",
            "CREATE TABLE IF NOT EXISTS douyin_work_files ("
                    + "work_id TEXT NOT NULL, file_index INTEGER NOT NULL, media_id TEXT, media_type TEXT NOT NULL,"
                    + " file_name TEXT NOT NULL, extension TEXT NOT NULL, bytes INTEGER, content_type TEXT,"
                    + " created_time INTEGER NOT NULL, PRIMARY KEY(work_id, file_index))",
            "CREATE INDEX IF NOT EXISTS idx_douyin_work_files_work_id ON douyin_work_files(work_id)",
            "CREATE TABLE IF NOT EXISTS douyin_work_relations ("
                    + "work_id TEXT NOT NULL, source_type TEXT NOT NULL, source_id TEXT NOT NULL, source_title TEXT,"
                    + " source_url TEXT, source_order INTEGER, discovered_time INTEGER NOT NULL,"
                    + " PRIMARY KEY(work_id, source_type, source_id))",
            "CREATE INDEX IF NOT EXISTS idx_douyin_work_relations_source"
                    + " ON douyin_work_relations(source_type, source_id, source_order)");

    private final PluginDataSource dataSource;
    private final ThreadLocal<Connection> transactionConnection = new ThreadLocal<>();

    public DouyinHistoryMapper(PluginDataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        initializeSchema();
    }

    public <T> T inTransaction(Supplier<T> action) {
        Objects.requireNonNull(action, "action");
        if (transactionConnection.get() != null) {
            return action.get();
        }
        try (Connection connection = dataSource.getConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            transactionConnection.set(connection);
            try {
                T result = action.get();
                connection.commit();
                return result;
            } catch (RuntimeException | Error failure) {
                rollback(connection, failure);
                throw failure;
            } finally {
                transactionConnection.remove();
                connection.setAutoCommit(originalAutoCommit);
            }
        } catch (SQLException e) {
            throw databaseFailure(e);
        }
    }

    public DouyinWorkRecord findActiveById(String workId) {
        return queryOne(SELECT_WORK + " WHERE work_id = ? AND deleted = 0", List.of(workId), this::readWork);
    }

    public DouyinWorkRecord findAnyById(String workId) {
        return queryOne(SELECT_WORK + " WHERE work_id = ?", List.of(workId), this::readWork);
    }

    public List<DouyinWorkFileRecord> findFilesByWorkId(String workId) {
        return queryList(SELECT_FILE + " WHERE work_id = ? ORDER BY file_index", List.of(workId), this::readFile);
    }

    public List<DouyinWorkRecord> findActivePage(DouyinHistoryQuery query) {
        List<Object> parameters = new ArrayList<>();
        StringBuilder sql = new StringBuilder(SELECT_WORK).append(" WHERE deleted = 0");
        appendFilters(sql, parameters, query);
        sql.append(" ORDER BY ").append(switch (query.sort()) {
            case "title" -> "LOWER(COALESCE(NULLIF(title, ''), work_id))";
            case "publishTime" -> "publish_time";
            case "authorName" -> "LOWER(COALESCE(NULLIF(author_name, ''), author_id, ''))";
            case "collectionOrder" -> "collection_order";
            default -> "time";
        }).append(" ").append("asc".equals(query.order()) ? "ASC" : "DESC")
                .append(", time DESC, work_id ASC LIMIT ? OFFSET ?");
        parameters.add(query.limit());
        parameters.add(query.offset());
        return queryList(sql.toString(), parameters, this::readWork);
    }

    public long countActive(DouyinHistoryQuery query) {
        List<Object> parameters = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM douyin_works WHERE deleted = 0");
        appendFilters(sql, parameters, query);
        Long count = queryOne(sql.toString(), parameters, result -> result.getLong(1));
        return count == null ? 0L : count;
    }

    public List<DouyinAuthorSummary> findAuthorFacets(DouyinHistoryQuery query) {
        List<Object> parameters = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT author_id,"
                + " COALESCE(NULLIF(TRIM(author_name), ''), author_id), COUNT(*)"
                + " FROM douyin_works WHERE deleted = 0"
                + " AND author_id IS NOT NULL AND TRIM(author_id) != ''");
        appendFilters(sql, parameters, query);
        sql.append(" GROUP BY author_id, COALESCE(NULLIF(TRIM(author_name), ''), author_id)"
                + " ORDER BY COUNT(*) DESC,"
                + " LOWER(COALESCE(NULLIF(TRIM(author_name), ''), author_id)) ASC, author_id ASC LIMIT ?");
        parameters.add(query.limit());
        return queryList(sql.toString(), parameters,
                result -> new DouyinAuthorSummary(result.getString(1), result.getString(2), result.getLong(3)));
    }

    public int insertWork(DouyinWorkRecord record) {
        return update("INSERT OR IGNORE INTO douyin_works"
                        + " (work_id, title, folder, count, extensions, time, deleted, kind, source_url, canonical_url,"
                        + " thumbnail_url, author_id, author_name, description, item_title, caption, publish_time,"
                        + " collection_id, collection_title, collection_order)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                workParameters(record));
    }

    public int updateActiveWork(DouyinWorkRecord record) {
        List<Object> parameters = new ArrayList<>(workParameters(record).subList(1, 20));
        parameters.add(record.workId());
        return update("UPDATE douyin_works SET title = ?, folder = ?, count = ?, extensions = ?, time = ?,"
                + " deleted = ?, kind = ?, source_url = ?, canonical_url = ?, thumbnail_url = ?, author_id = ?,"
                + " author_name = ?, description = ?, item_title = ?, caption = ?, publish_time = ?,"
                + " collection_id = ?, collection_title = ?, collection_order = ?"
                + " WHERE work_id = ? AND deleted = 0", parameters);
    }

    public int upsertFile(DouyinWorkFileRecord record) {
        return update("INSERT OR REPLACE INTO douyin_work_files"
                        + " (work_id, file_index, media_id, media_type, file_name, extension, bytes, content_type,"
                        + " created_time) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                List.of(record.workId(), record.fileIndex(), nullable(record.mediaId()), record.mediaType(),
                        record.fileName(), record.extension(), nullable(record.bytes()), nullable(record.contentType()),
                        record.createdTime()));
    }

    public int upsertRelation(DouyinSourceRelation relation) {
        return update("INSERT INTO douyin_work_relations"
                        + " (work_id, source_type, source_id, source_title, source_url, source_order, discovered_time)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)"
                        + " ON CONFLICT(work_id, source_type, source_id) DO UPDATE SET"
                        + " source_title = COALESCE(excluded.source_title, douyin_work_relations.source_title),"
                        + " source_url = COALESCE(excluded.source_url, douyin_work_relations.source_url),"
                        + " source_order = COALESCE(douyin_work_relations.source_order, excluded.source_order),"
                        + " discovered_time = MIN(douyin_work_relations.discovered_time, excluded.discovered_time)",
                List.of(relation.workId(), relation.sourceType(), relation.sourceId(), nullable(relation.sourceTitle()),
                        nullable(relation.sourceUrl()), nullable(relation.sourceOrder()), relation.discoveredTime()));
    }

    public List<DouyinSourceRelation> findRelationsByWorkId(String workId) {
        return queryList("SELECT work_id, source_type, source_id, source_title, source_url, source_order,"
                        + " discovered_time FROM douyin_work_relations"
                        + " WHERE work_id = ? ORDER BY discovered_time, source_type, source_id",
                List.of(workId), result -> new DouyinSourceRelation(
                        result.getString(1), result.getString(2), result.getString(3), result.getString(4),
                        result.getString(5), nullableInt(result, 6), result.getLong(7)));
    }

    public int backfillRelations() {
        return update("INSERT OR IGNORE INTO douyin_work_relations"
                + " (work_id, source_type, source_id, source_title, source_url, source_order, discovered_time)"
                + " SELECT work_id, CASE WHEN collection_id IS NOT NULL AND TRIM(collection_id) != ''"
                + " THEN 'douyin.collection' ELSE 'douyin.single' END,"
                + " COALESCE(NULLIF(TRIM(collection_id), ''), work_id),"
                + " COALESCE(NULLIF(TRIM(collection_title), ''), title),"
                + " COALESCE(NULLIF(TRIM(source_url), ''), canonical_url), collection_order, time"
                + " FROM douyin_works", List.of());
    }

    public int countById(String workId) {
        return count("SELECT COUNT(*) FROM douyin_works WHERE work_id = ?", workId);
    }

    public int countActiveById(String workId) {
        return count("SELECT COUNT(*) FROM douyin_works WHERE work_id = ? AND deleted = 0", workId);
    }

    public int countDeletedById(String workId) {
        return count("SELECT COUNT(*) FROM douyin_works WHERE work_id = ? AND deleted = 1", workId);
    }

    public int countByTime(long time) {
        return count("SELECT COUNT(*) FROM douyin_works WHERE time = ?", time);
    }

    public Long findMaxTime() {
        return queryOne("SELECT MAX(time) FROM douyin_works", List.of(), result -> nullableLong(result, 1));
    }

    public int markDeletedById(String workId) {
        return update("UPDATE douyin_works SET deleted = 1 WHERE work_id = ?", List.of(workId));
    }

    public int deleteFilesByWorkId(String workId) {
        return update("DELETE FROM douyin_work_files WHERE work_id = ?", List.of(workId));
    }

    public int deleteRelationsByWorkId(String workId) {
        return update("DELETE FROM douyin_work_relations WHERE work_id = ?", List.of(workId));
    }

    public int deleteFilesIfWorkMarkedDeleted(String workId) {
        return update("DELETE FROM douyin_work_files WHERE work_id IN"
                + " (SELECT work_id FROM douyin_works WHERE work_id = ? AND deleted = 1)", List.of(workId));
    }

    public int deleteRelationsIfWorkMarkedDeleted(String workId) {
        return update("DELETE FROM douyin_work_relations WHERE work_id IN"
                + " (SELECT work_id FROM douyin_works WHERE work_id = ? AND deleted = 1)", List.of(workId));
    }

    public int deleteWorkIfMarkedDeleted(String workId) {
        return update("DELETE FROM douyin_works WHERE work_id = ? AND deleted = 1", List.of(workId));
    }

    private void initializeSchema() {
        withConnection(connection -> {
            try (Statement statement = connection.createStatement()) {
                for (String sql : SCHEMA) {
                    statement.execute(sql);
                }
            }
            return null;
        });
    }

    private static void appendFilters(
            StringBuilder sql, List<Object> parameters, DouyinHistoryQuery query) {
        if (query.search() != null) {
            sql.append(" AND (work_id LIKE ? OR title LIKE ? OR item_title LIKE ? OR caption LIKE ?"
                    + " OR description LIKE ? OR author_name LIKE ?)");
            String value = "%" + query.search() + "%";
            for (int index = 0; index < 6; index++) {
                parameters.add(value);
            }
        }
        appendIn(sql, parameters, "author_id", query.authorIds());
        if (!query.requiredMediaTypes().isEmpty()) {
            sql.append(" AND EXISTS (SELECT 1 FROM douyin_work_files gallery_file"
                    + " WHERE gallery_file.work_id = douyin_works.work_id AND gallery_file.media_type IN (")
                    .append(placeholders(query.requiredMediaTypes().size())).append("))");
            parameters.addAll(query.requiredMediaTypes());
        }
    }

    private static void appendIn(
            StringBuilder sql, List<Object> parameters, String column, List<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        sql.append(" AND ").append(column).append(" IN (")
                .append(placeholders(values.size())).append(")");
        parameters.addAll(values);
    }

    private static String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }

    private int count(String sql, Object parameter) {
        Long result = queryOne(sql, List.of(parameter), row -> row.getLong(1));
        return result == null ? 0 : result.intValue();
    }

    private int update(String sql, List<?> parameters) {
        return withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                bind(statement, parameters);
                return statement.executeUpdate();
            }
        });
    }

    private <T> T queryOne(String sql, List<?> parameters, RowReader<T> reader) {
        List<T> rows = queryList(sql, parameters, reader);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private <T> List<T> queryList(String sql, List<?> parameters, RowReader<T> reader) {
        return withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                bind(statement, parameters);
                try (ResultSet result = statement.executeQuery()) {
                    List<T> rows = new ArrayList<>();
                    while (result.next()) {
                        rows.add(reader.read(result));
                    }
                    return Collections.unmodifiableList(rows);
                }
            }
        });
    }

    private <T> T withConnection(SqlAction<T> action) {
        Connection active = transactionConnection.get();
        if (active != null) {
            try {
                return action.run(active);
            } catch (SQLException e) {
                throw databaseFailure(e);
            }
        }
        try (Connection connection = dataSource.getConnection()) {
            return action.run(connection);
        } catch (SQLException e) {
            throw databaseFailure(e);
        }
    }

    private static void bind(PreparedStatement statement, List<?> parameters) throws SQLException {
        for (int index = 0; index < parameters.size(); index++) {
            Object value = parameters.get(index);
            if (value == NullValue.INSTANCE) {
                statement.setNull(index + 1, Types.NULL);
            } else {
                statement.setObject(index + 1, value);
            }
        }
    }

    private DouyinWorkRecord readWork(ResultSet result) throws SQLException {
        return new DouyinWorkRecord(
                result.getString(1), result.getString(2), result.getString(3), result.getInt(4),
                result.getString(5), result.getLong(6), result.getBoolean(7), result.getString(8),
                result.getString(9), result.getString(10), result.getString(11), result.getString(12),
                result.getString(13), result.getString(14), result.getString(15), result.getString(16),
                nullableLong(result, 17), result.getString(18), result.getString(19), nullableInt(result, 20));
    }

    private DouyinWorkFileRecord readFile(ResultSet result) throws SQLException {
        return new DouyinWorkFileRecord(
                result.getString(1), result.getInt(2), result.getString(3), result.getString(4),
                result.getString(5), result.getString(6), nullableLong(result, 7), result.getString(8),
                result.getLong(9));
    }

    private static List<Object> workParameters(DouyinWorkRecord record) {
        return List.of(
                record.workId(), record.title(), record.folder(), record.count(), record.extensions(), record.time(),
                record.deleted() ? 1 : 0, record.kind(), nullable(record.sourceUrl()), nullable(record.canonicalUrl()),
                nullable(record.thumbnailUrl()), nullable(record.authorId()), nullable(record.authorName()),
                nullable(record.description()), nullable(record.itemTitle()), nullable(record.caption()),
                nullable(record.publishTime()), nullable(record.collectionId()), nullable(record.collectionTitle()),
                nullable(record.collectionOrder()));
    }

    private static Object nullable(Object value) {
        return value == null ? NullValue.INSTANCE : value;
    }

    private static Long nullableLong(ResultSet result, int column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    private static Integer nullableInt(ResultSet result, int column) throws SQLException {
        int value = result.getInt(column);
        return result.wasNull() ? null : value;
    }

    private static void rollback(Connection connection, Throwable failure) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }

    private static IllegalStateException databaseFailure(SQLException failure) {
        return new IllegalStateException("Douyin private database operation failed", failure);
    }

    private enum NullValue { INSTANCE }

    @FunctionalInterface
    private interface RowReader<T> {
        T read(ResultSet result) throws SQLException;
    }

    @FunctionalInterface
    private interface SqlAction<T> {
        T run(Connection connection) throws SQLException;
    }
}
