package top.sywyar.pixivdownload.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import top.sywyar.pixivdownload.core.db.DatabaseTarget;
import top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures;
import top.sywyar.pixivdownload.plugin.api.storage.relational.DatabaseSession;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@DisplayName("作品回填数据库")
class ArtworksBackFillDatabaseTest {

    @Test
    @DisplayName("回填保留附加对象且不修补无关的核心表")
    void ignoresUnrelatedSchema(@TempDir Path directory) throws Exception {
        var target = DatabaseProviderTestFixtures.sqlite(directory.resolve("unrelated.db"));
        try (var connection = target.openConnection(); var sql = connection.createStatement()) {
            sql.execute(legacyArtworks());
            sql.execute("INSERT INTO artworks VALUES(1,'fixture','folder',1,'png',1)");
            sql.execute("ALTER TABLE artworks ADD COLUMN extra TEXT");
            sql.execute("UPDATE artworks SET extra='keep extra'");
            sql.execute("CREATE INDEX auxiliary ON artworks(lower(title)) WHERE artwork_id>0");
            sql.execute("CREATE TABLE statistics(id TEXT PRIMARY KEY,private_data TEXT)");
            sql.execute("INSERT INTO statistics VALUES('legacy','keep unrelated')");
            sql.execute("CREATE TABLE absent_plugin(id INTEGER PRIMARY KEY,body TEXT)");
            sql.execute("INSERT INTO absent_plugin VALUES(2,'plugin data')");
        }
        var unreachable = ArtworksBackFillUnreachableStore.load(directory.resolve("unreachable.json"), new ObjectMapper());
        try (var database = ArtworksBackFillDatabase.open(target)) {
            assertThat(database.findCandidates(1, unreachable).candidates()).hasSize(1);
        }
        try (var connection = target.openConnection(); var sql = connection.createStatement()) {
            assertThat(queryString(sql, "SELECT extra FROM artworks")).isEqualTo("keep extra");
            assertThat(queryString(sql, "SELECT private_data FROM statistics")).isEqualTo("keep unrelated");
            assertThat(queryString(sql, "SELECT body FROM absent_plugin")).isEqualTo("plugin data");
            assertThat(queryInt(sql, "SELECT count(*) FROM pragma_table_info('statistics')")).isEqualTo(2);
            assertThat(queryInt(sql, "SELECT count(*) FROM sqlite_schema WHERE name='auxiliary'")).isEqualTo(1);
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource(value = {
            "NULL,NULL,7,3,true", "7,NULL,7,3,true", "8,NULL,8,NULL,false",
            "0,NULL,0,NULL,false", "7,9,7,9,true", "8,9,8,9,false"
    }, nullValues = "NULL")
    @DisplayName("候选读取后并发改变系列身份时拒绝旧系列投影，保留其他补缺与同系列正常更新")
    void guardsSeriesIdentityAtWrite(Long concurrentSeries, Long concurrentOrder, long expectedSeries,
            Long expectedOrder, boolean acceptsSeries, @TempDir Path directory) throws Exception {
        var target = DatabaseProviderTestFixtures.sqlite(directory.resolve("series-race.db"));
        try (var connection = target.openConnection(); var sql = connection.createStatement()) {
            top.sywyar.pixivdownload.core.db.schema.DatabaseInitializer.prepareOffline(connection, target);
            sql.executeUpdate("INSERT INTO artworks(artwork_id,title,folder,count,extensions,time,r18) "
                    + "VALUES(2,'fixture','folder',1,'png',2,1)");
            sql.executeUpdate("INSERT INTO manga_series(series_id,title,updated_time) VALUES(8,'current-series',1)");
        }
        var unreachable = ArtworksBackFillUnreachableStore.load(directory.resolve("unreachable.json"), new ObjectMapper());
        var result = ArtworksBackFillPixivClient.parsePayload(new ObjectMapper().readTree("""
                {"userId":42,"description":"filled","xRestrict":0,
                 "seriesNavData":{"seriesId":7,"order":3,"title":"old-series"}}
                """), null, false);
        try (var database = ArtworksBackFillDatabase.open(target)) {
            var candidate = database.findCandidates(1, unreachable).candidates().get(0);
            assertThat(candidate.seriesId()).isNull();
            try (var other = target.openConnection();
                 var update = other.prepareStatement("UPDATE artworks SET series_id=?,series_order=? WHERE artwork_id=2")) {
                update.setObject(1, concurrentSeries);
                update.setObject(2, concurrentOrder);
                update.executeUpdate();
            }
            database.applyUpdates(candidate, result, false, true, false, true, false, true);
            database.applyUpdates(candidate, result, false, true, false, true, false, true);
        }
        try (var connection = target.openConnection(); var sql = connection.createStatement()) {
            try (var row = sql.executeQuery("SELECT series_id,series_order,description,r18 FROM artworks WHERE artwork_id=2")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getLong(1)).isEqualTo(expectedSeries);
                assertThat(row.getObject(2) == null ? null : row.getLong(2)).isEqualTo(expectedOrder);
                assertThat(row.getString(3)).isEqualTo("filled");
                assertThat(row.getInt(4)).isEqualTo(1);
            }
            assertThat(queryInt(sql, "SELECT count(*) FROM manga_series WHERE series_id=7")).isEqualTo(acceptsSeries ? 1 : 0);
            assertThat(queryString(sql, "SELECT title FROM manga_series WHERE series_id=8")).isEqualTo("current-series");
            if (acceptsSeries) assertThat(queryString(sql, "SELECT title FROM manga_series WHERE series_id=7")).isEqualTo("old-series");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("回填普通失败后的回滚致命错误不被原错误掩盖")
    void fatalRollbackEscapesOrdinaryFailure(boolean nested, @TempDir Path directory) throws Exception {
        var target = spy(DatabaseProviderTestFixtures.sqlite(directory.resolve("rollback.db")));
        try (var raw = target.openConnection()) {
            top.sywyar.pixivdownload.core.db.schema.DatabaseInitializer.prepareOffline(raw, target);
            var connection = mock(Connection.class, org.mockito.AdditionalAnswers.delegatesTo(raw));
            var source = mock(javax.sql.DataSource.class);
            when(source.getConnection()).thenReturn(connection);
            try (var database = ArtworksBackFillDatabase.open(source, target)) {
                if (nested) connection.setAutoCommit(false);
                var failure = new java.sql.SQLException("controlled storage failure");
                var cleanup = new ThreadDeath();
                doAnswer(ignored -> {
                    if (nested) doThrow(cleanup).when(connection).rollback(any(java.sql.Savepoint.class));
                    else doThrow(cleanup).when(connection).rollback();
                    throw failure;
                }).when(target).storageSession(eq(connection), any());
                var candidate = new ArtworksBackFillDatabase.Candidate(1, true, true, true, true, true, true);
                var result = ArtworksBackFillPixivClient.LookupResult.found(
                        42, "author", 1, true, "description", List.of(), 0, 0, null);

                assertThatThrownBy(() -> database.applyUpdates(candidate, result, true, false, false, false, false, false))
                        .isSameAs(cleanup).hasSuppressedException(failure);
                assertThat(connection.getAutoCommit()).isFalse();
            }
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"target, false", "pooled-schema, false",
            "independent-schema, false", "target, true", "pooled-schema, true", "independent-schema, true"})
    @DisplayName("目标或结构校验失败关闭已取得的连接，清理致命错误仍传播")
    void closesConnectionWhenPreparationFails(String stage, boolean cleanupFatal) throws Exception {
        var connection = mock(Connection.class);
        var target = mock(DatabaseTarget.class);
        var pool = mock(javax.sql.DataSource.class);
        Throwable failure = cleanupFatal ? new java.sql.SQLException("controlled failure")
                : new OutOfMemoryError("controlled failure");
        var cleanup = new OutOfMemoryError("controlled close failure");
        if (cleanupFatal) doThrow(cleanup).when(connection).close();
        when(pool.getConnection()).thenReturn(connection);
        when(target.openConnection()).thenReturn(connection);
        if (stage.equals("target")) doThrow(failure).when(target).requireConnectionTarget(connection);
        else when(target.readSchema(connection)).thenThrow(failure);

        assertThatThrownBy(() -> {
            if (stage.equals("independent-schema")) ArtworksBackFillDatabase.open(target);
            else ArtworksBackFillDatabase.open(pool, target);
        }).isSameAs(cleanupFatal ? cleanup : failure);
        if (cleanupFatal) assertThat(cleanup.getSuppressed()).containsExactly(failure);
        verify(connection).close();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("回填中途致命错误撤回本作品写入并保留外层事务")
    void rollsBackPartialWritesOnFatalFailure(boolean nested, @TempDir Path directory) throws Exception {
        exerciseFatalRollback(DatabaseProviderTestFixtures.sqlite(directory.resolve("atomic.db")), nested);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "PIXIV_TEST_POSTGRES_URL", matches = ".+")
    @DisplayName("PostgreSQL 回填致命失败撤回本作品写入并保留外层事务")
    void postgresRollsBackPartialWritesOnFatalFailure(boolean nested) throws Exception {
        try (var database = new top.sywyar.pixivdownload.core.db.PostgresTestDatabase()) {
            exerciseFatalRollback(DatabaseProviderTestFixtures.resolve(database.properties(), "unused"), nested);
        }
    }

    private void exerciseFatalRollback(DatabaseTarget configured, boolean nested) throws Exception {
        var target = spy(configured);
        try (var connection = target.openConnection()) {
            top.sywyar.pixivdownload.core.db.schema.DatabaseInitializer.prepareOffline(connection, target);
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO artworks(artwork_id,title,folder,count,extensions,time)"
                        + " VALUES(1,'original','folder',1,'png',1)");
            }
        }
        try (var connection = target.openConnection()) {
            try (var database = ArtworksBackFillDatabase.open(new SingleConnectionDataSource(connection, true), target)) {
                if (nested) {
                    connection.setAutoCommit(false);
                    try (var statement = connection.createStatement()) {
                        statement.executeUpdate("INSERT INTO artworks(artwork_id,title,folder,count,extensions,time)"
                                + " VALUES(2,'outer','folder',1,'png',2)");
                    }
                }
                var failure = new OutOfMemoryError("controlled failure after write");
                doAnswer(invocation -> {
                    DatabaseSession real = (DatabaseSession) invocation.callRealMethod();
                    var session = mock(DatabaseSession.class, org.mockito.AdditionalAnswers.delegatesTo(real));
                    doAnswer(update -> {
                        real.update(update.getArgument(0), update.getArgument(1), update.getArgument(2));
                        throw failure;
                    }).when(session).update(eq("artworks"), anyMap(), any());
                    return session;
                }).when(target).storageSession(any(), any());
                var candidate = new ArtworksBackFillDatabase.Candidate(1, true, true, true, true, true, true);
                var result = ArtworksBackFillPixivClient.LookupResult.found(
                        42, "author", 1, true, "new description", List.of(), 0, 0, null);

                assertThatThrownBy(() -> database.applyUpdates(candidate, result, true, true, true, true, false, false))
                        .isSameAs(failure);
                try (var statement = connection.createStatement()) {
                    assertThat(queryString(statement, "SELECT description FROM artworks WHERE artwork_id=1")).isNull();
                    assertThat(connection.getAutoCommit()).isEqualTo(!nested);
                    if (nested) {
                        assertThat(queryString(statement, "SELECT title FROM artworks WHERE artwork_id=2")).isEqualTo("outer");
                        connection.commit();
                    }
                }
            }
        }
        try (var connection = target.openConnection(); var statement = connection.createStatement()) {
            assertThat(queryString(statement, "SELECT description FROM artworks WHERE artwork_id=1")).isNull();
        }
    }

    @Test
    @DisplayName("过滤不可达作品并持久化回填元数据")
    void filtersCandidatesAndPersistsMetadata(@TempDir Path tempDir) throws Exception {
        Path databasePath = tempDir.resolve("backfill.db");
        String jdbcUrl = "jdbc:sqlite:" + databasePath;
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(legacyArtworks());
            for (int id = 1; id <= 4; id++) statement.executeUpdate("INSERT INTO artworks"
                    + "(artwork_id,title,folder,count,extensions,time) VALUES(" + id + ",'fixture','folder',1,'png'," + id + ")");
        }
        try (ArtworksBackFillDatabase ignored = ArtworksBackFillDatabase.open(top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.sqlite(databasePath))) {
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

        try (ArtworksBackFillDatabase database = ArtworksBackFillDatabase.open(top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.sqlite(databasePath))) {
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

    @Test
    @DisplayName("复用池化数据源并拒绝与活动数据库不一致的路径")
    void usesPooledDataSourceAndRejectsDifferentDatabasePath(@TempDir Path tempDir) throws Exception {
        Path databasePath = tempDir.resolve("pooled.db");
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + databasePath);
        config.setMaximumPoolSize(1);

        try (HikariDataSource dataSource = new HikariDataSource(config)) {
            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.executeUpdate(legacyArtworks());
            }

            try (ArtworksBackFillDatabase ignored = ArtworksBackFillDatabase.open(
                    dataSource,
                    top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.sqlite(databasePath)
            )) {
                // close 只归还借用连接，不关闭宿主池。
            }
            try (Connection ignored = dataSource.getConnection()) {
                assertTrue(ignored.isValid(1));
            }

            assertThrows(
                    java.sql.SQLException.class,
                    () -> ArtworksBackFillDatabase.open(dataSource, top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.sqlite(tempDir.resolve("other.db")))
            );
        }
    }

    private static String queryString(Statement statement, String sql) throws Exception {
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            assertTrue(resultSet.next());
            return resultSet.getString(1);
        }
    }

    private static String legacyArtworks() {
        return "CREATE TABLE artworks (artwork_id INTEGER PRIMARY KEY, title TEXT NOT NULL, folder TEXT NOT NULL,"
                + " count INTEGER NOT NULL, extensions TEXT NOT NULL, time INTEGER NOT NULL UNIQUE)";
    }

    @Test
    @DisplayName("无法解释的缺损主库拒绝回填且保留既有行")
    void rejectsIncompleteMainSchema(@TempDir Path directory) throws Exception {
        var target = top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.sqlite(directory.resolve("incomplete.db"));
        try (var connection = target.openConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE artworks (artwork_id INTEGER PRIMARY KEY)");
            statement.execute("INSERT INTO artworks VALUES (7)");
        }
        assertThrows(java.sql.SQLException.class, () -> ArtworksBackFillDatabase.open(target));
        try (var connection = target.openConnection(); var statement = connection.createStatement()) {
            assertEquals(7, queryInt(statement, "SELECT artwork_id FROM artworks"));
            assertEquals(1, queryInt(statement, "SELECT count(*) FROM pragma_table_info('artworks')"));
        }
    }

    private static int queryInt(Statement statement, String sql) throws Exception {
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            assertTrue(resultSet.next());
            return resultSet.getInt(1);
        }
    }
}
