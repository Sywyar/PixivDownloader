package top.sywyar.pixivdownload.novel.translation;

import org.junit.jupiter.api.*;
import top.sywyar.pixivdownload.databasesqlite.SqliteDatabasePlugin;
import top.sywyar.pixivdownload.novel.db.*;
import top.sywyar.pixivdownload.plugin.api.database.*;
import top.sywyar.pixivdownload.plugin.api.database.migration.MigrationSchema;
import top.sywyar.pixivdownload.plugin.api.schema.*;
import top.sywyar.pixivdownload.plugin.api.storage.PluginMainDatabase;
import top.sywyar.pixivdownload.plugin.api.storage.relational.*;
import top.sywyar.pixivdownload.translation.api.*;
import top.sywyar.pixivdownload.translation.api.NovelTranslationDocuments.*;
import java.sql.*;
import java.nio.file.Path;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static top.sywyar.pixivdownload.plugin.api.storage.relational.Expression.*;
import static top.sywyar.pixivdownload.plugin.api.storage.relational.Expression.Operator.*;

@DisplayName("完整译文与发布收据的同事务提交")
class TranslationPublicationsTest {
    Connection connection;
    DatabaseConnectionTarget target;
    MigrationSchema schema;
    DatabaseSession nested;
    boolean failCommit;
    PluginMainDatabase main;
    NovelDatabase novels;
    NovelRecord original;
    TranslationPublications publications;
    String postgresSchema;
    @BeforeEach void setup() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        target = new SqliteDatabasePlugin().resolve(new DatabaseConnectionSettings("", "", "", Path.of("unused")));
        if ("postgresql".equals(System.getProperty("translation.test.database"))) {
            connection.close();
            postgresSchema = "translation_publication_" + UUID.randomUUID().toString().replace("-", "");
            String url = Objects.requireNonNull(System.getenv("PIXIV_TEST_POSTGRES_URL"));
            String user = Objects.requireNonNull(System.getenv("PIXIV_TEST_POSTGRES_USER"));
            String password = Objects.requireNonNull(System.getenv("PIXIV_TEST_POSTGRES_PASSWORD"));
            connection = DriverManager.getConnection(url, user, password);
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE SCHEMA " + postgresSchema);
                statement.execute("SET search_path TO " + postgresSchema);
            }
            target = new top.sywyar.pixivdownload.databasepostgresql.PostgresDatabasePlugin().resolve(
                    new DatabaseConnectionSettings(url + "?currentSchema=" + postgresSchema, user, password, Path.of("unused")));
        }
        List<TableSpec> tables = new ArrayList<>(TranslationPublications.schema().tables());
        tables.add(new TableSpec("novels", List.of(new ColumnSpec("novel_id", "INTEGER", true, null, 1),
                new ColumnSpec("deleted", "INTEGER", true, "0", 0)), List.of()));
        tables.add(new TableSpec("novel_translations", List.of(new ColumnSpec("variant", "TEXT", true, null, 1),
                new ColumnSpec("body", "TEXT", false, null, 0)), List.of()));
        schema = new MigrationSchema(1, tables, List.of(), List.of());
        target.initializeStorage(connection, schema);
        main = new PluginMainDatabase() {
            public boolean available() { return true; }
            public <T> T read(DatabaseSession.Work<T> work) { return transaction(work); }
            public <T> T transaction(DatabaseSession.Work<T> work) {
                try {
                    if (nested != null) return work.run(nested);
                    connection.setAutoCommit(false);
                    try (var session = target.storageSession(connection, schema)) {
                        nested = session;
                        T result = work.run(session);
                        if (failCommit || session.rollbackOnly()) throw new SQLException("fixture-commit-failed");
                        connection.commit(); return result;
                    } catch (SQLException | RuntimeException failure) { connection.rollback(); throw failure; }
                    finally { nested = null; connection.setAutoCommit(true); }
                } catch (SQLException e) { throw new IllegalStateException("fixture-database-failed", e); }
            }
        };
        main.transaction(s -> s.insert("novels", Map.of("novel_id", 7L, "deleted", 0L), DatabaseSession.Conflict.error()));
        novels = mock(NovelDatabase.class); original = mock(NovelRecord.class);
        when(original.rawContent()).thenReturn("source");
        when(original.title()).thenReturn("title");
        when(novels.getNovel(7)).thenReturn(original);
        when(novels.getTranslationContent(eq(7L), anyString())).thenAnswer(call -> body(call.getArgument(1)));
        doAnswer(call -> {
            main.transaction(s -> s.insert("novel_translations", Map.of("variant", call.getArgument(1), "body", call.getArgument(2)),
                    new DatabaseSession.Conflict(DatabaseSession.Mode.UPDATE, List.of("variant"), Map.of("body", new Incoming("body")))));
            return null;
        }).when(novels).saveTranslation(eq(7L), anyString(), anyString(), any(), any());
        publications = new TranslationPublications(main, novels);
    }
    @AfterEach void close() throws Exception {
        if (connection == null) return;
        try {
            if (postgresSchema != null) try (var statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA " + postgresSchema + " CASCADE");
            }
        } finally { connection.close(); }
    }
    String body(String variant) {
        var rows = main.read(s -> s.query(new Query("novel_translations", Map.of("body", column("novel_translations", "body")),
                call(EQUAL, column("novel_translations", "variant"), value(variant)), List.of(), List.of(), 1, 0)));
        return rows.isEmpty() ? null : (String) rows.get(0).get("body");
    }
    Publication publication(String previous, String output) {
        return new Publication(UUID.randomUUID(), TranslationDigest.of("source", "title", null),
                TranslationDigest.of(previous, null, null), TranslationDigest.of(output, null, null));
    }
    @Test @DisplayName("提交失败同时回滚译文与收据，重放确认不重复覆写")
    void rollsBackBothAndReplaysReceipt() {
        var publication = publication(null, "translated");
        failCommit = true;
        assertThatThrownBy(() -> publications.publish(Kind.NOVEL, 7, "zh-CN@youdao", "translated", null, null, publication))
                .hasMessage("fixture-database-failed");
        failCommit = false;
        assertThat(body("zh-CN@youdao")).isNull();
        assertThat(publications.published(publication.id())).isFalse();
        publications.publish(Kind.NOVEL, 7, "zh-CN@youdao", "translated", null, null, publication);
        assertThat(body("zh-CN@youdao")).isEqualTo("translated");
        assertThat(publications.published(publication.id())).isTrue();
        publications.publish(Kind.NOVEL, 7, "zh-CN@youdao", "translated", null, null, publication);
        verify(novels, times(2)).saveTranslation(7, "zh-CN@youdao", "translated", null, null);
    }
    @Test @DisplayName("原文、目标译文或发布内容不匹配时拒绝覆盖，其他引擎不受影响")
    void rejectsConflictsAndKeepsOtherEngine() {
        publications.publish(Kind.NOVEL, 7, "zh-CN@youdao", "first", null, null, publication(null, "first"));
        publications.publish(Kind.NOVEL, 7, "zh-CN@ai", "ai", null, null, publication(null, "ai"));
        assertThat(publications.publish(Kind.NOVEL, 7, "zh-CN@youdao", "late", null, null, publication(null, "late")))
                .isEqualTo(PublicationResult.TARGET_CHANGED);
        when(original.rawContent()).thenReturn("changed");
        assertThat(publications.publish(Kind.NOVEL, 7, "zh-CN@youdao", "late", null, null, publication("first", "late")))
                .isEqualTo(PublicationResult.SOURCE_CHANGED);
        assertThat(body("zh-CN@youdao")).isEqualTo("first");
        assertThat(body("zh-CN@ai")).isEqualTo("ai");
    }
}
