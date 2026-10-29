package top.sywyar.pixivdownload.core.metadata;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import top.sywyar.pixivdownload.core.database.DatabaseUnavailableException;
import top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures;
import top.sywyar.pixivdownload.core.db.MainDatabase;
import top.sywyar.pixivdownload.core.metadata.sidecar.WorkMetaCurator;
import top.sywyar.pixivdownload.core.pixiv.PixivDescriptionHtml;
import top.sywyar.pixivdownload.core.work.model.WorkType;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.maintenance.MaintenanceCoordinator;
import top.sywyar.pixivdownload.maintenance.MaintenanceProperties;
import top.sywyar.pixivdownload.plugin.api.maintenance.MaintenanceContext;
import top.sywyar.pixivdownload.plugin.registry.schema.DatabaseSchemaRegistry;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("历史作品说明按原始快照修复")
class WorkDescriptionRepairTaskTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();
    private SingleConnectionDataSource source;
    private JdbcTemplate jdbc;
    private MainDatabase database;
    private WorkDescriptionRepairTask task;
    private final MaintenanceContext context = new MaintenanceContext("manual", 0);
    private static final String RAW = "A&#39;s note&#44; &quot;quoted&quot; &amp;#39;";
    private static final String ONCE = "A&amp;#39;s note&amp;#44; &amp;quot;quoted&amp;quot; &amp;amp;#39;";
    private static final String TWICE = "A&amp;amp;#39;s note&amp;amp;#44; &amp;amp;quot;quoted&amp;amp;quot; &amp;amp;amp;#39;";
    private static final String FIXED = "A's note, \"quoted\" &amp;#39;";

    @BeforeEach void setUp() {
        source = new SingleConnectionDataSource("jdbc:sqlite:" + directory.resolve("fixture.db"), true);
        jdbc = new JdbcTemplate(source);
        var registry = DatabaseSchemaRegistry.forBuiltInPlugins();
        DatabaseProviderTestFixtures.initializer(jdbc, registry.contributions(), registry.mergedSchema(),
                TestI18nBeans.appMessages(), event -> {}).initialize();
        database = DatabaseProviderTestFixtures.main(source);
        task = new WorkDescriptionRepairTask(database, mapper);
    }

    @AfterEach void close() { source.destroy(); }

    @ParameterizedTest
    @EnumSource(WorkType.class)
    @DisplayName("预览不写库，一次和两次转义均可恢复且重复维护无副作用")
    void previewsAndRepairsPersistedDescriptions(WorkType type) throws Exception {
        seed(type, 1, RAW, ONCE);
        seed(type, 2, RAW, TWICE);
        String snapshot = jdbc.queryForObject("SELECT metadata_json FROM " + table(type) + " WHERE " + key(type) + "=1", String.class);
        assertThat(task.preview()).isEqualTo(2);
        assertThat(description(type, 1)).isEqualTo(ONCE);
        assertThat(description(type, 2)).isEqualTo(TWICE);
        task.execute(context);
        assertThat(description(type, 1)).isEqualTo(FIXED);
        assertThat(description(type, 2)).isEqualTo(FIXED);
        assertThat(jdbc.queryForObject("SELECT metadata_json FROM " + table(type) + " WHERE " + key(type) + "=1", String.class)).isEqualTo(snapshot);
        assertThat(jdbc.queryForObject("SELECT title FROM " + table(type) + " WHERE " + key(type) + "=1", String.class)).isEqualTo("Title &#39;");
        assertThat(task.preview()).isZero();
        task.execute(context);
        try (var reopened = java.sql.DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("fixture.db"));
             var query = reopened.createStatement(); var rows = query.executeQuery("SELECT description FROM " + table(type))) {
            while (rows.next()) assertThat(rows.getString(1)).isEqualTo(FIXED);
        }
    }

    @Test
    @DisplayName("保留被编辑、无快照、损坏、身份不符、已删除与已修复的记录")
    void skipsUnprovenRecords() throws Exception {
        for (int id = 1; id <= 10; id++) seed(WorkType.ARTWORK, id, RAW, TWICE);
        jdbc.update("UPDATE artworks SET description='edited &amp;#39;' WHERE artwork_id=1");
        jdbc.update("UPDATE artworks SET metadata_json=NULL WHERE artwork_id=2");
        jdbc.update("UPDATE artworks SET metadata_json='{' WHERE artwork_id=3");
        jdbc.update("UPDATE artworks SET deleted=1 WHERE artwork_id=4");
        jdbc.update("UPDATE artworks SET description=? WHERE artwork_id=5", FIXED);
        jdbc.update("UPDATE artworks SET metadata_json=? WHERE artwork_id=6", "{\"schemaVersion\":1,\"workType\":\"NOVEL\",\"workId\":6,\"raw\":{\"description\":\"x\"}}");
        jdbc.update("UPDATE artworks SET metadata_json=? WHERE artwork_id=7", snapshot(WorkType.ARTWORK, 8, RAW));
        jdbc.update("UPDATE artworks SET metadata_json=? WHERE artwork_id=8", snapshot(WorkType.ARTWORK, 8, RAW) + " {}");
        jdbc.update("UPDATE artworks SET metadata_json=? WHERE artwork_id=9", snapshot(WorkType.ARTWORK, 9, RAW).replace("\"schemaVersion\":1", "\"schemaVersion\":4294967297"));
        jdbc.update("UPDATE artworks SET metadata_json=? WHERE artwork_id=10", snapshot(WorkType.ARTWORK, 10, RAW) + " ".repeat(262144));
        var before = jdbc.queryForList("SELECT * FROM artworks ORDER BY artwork_id");
        assertThat(task.preview()).isZero();
        task.execute(context);
        assertThat(jdbc.queryForList("SELECT * FROM artworks ORDER BY artwork_id")).isEqualTo(before);
    }

    @Test
    @DisplayName("历史链接参数恢复并继续移除危险标签")
    void repairsLinksUsingExactHistoricalOutput() throws Exception {
        String raw = "<a href=\"https://example.com/?a=1&b=2\">link</a><img src=x onerror=bad>";
        String old = "<a href=\"https://example.com/?a=1&amp;amp;b=2\" target=\"_blank\" rel=\"noopener noreferrer\">link</a>";
        assertThat(LegacyDescriptionHtml.normalizeLinks(raw)).isEqualTo(old);
        seed(WorkType.ARTWORK, 1, raw, old);
        task.execute(context);
        assertThat(description(WorkType.ARTWORK, 1)).isEqualTo(
                "<a href=\"https://example.com/?a=1&amp;b=2\" target=\"_blank\" rel=\"noopener noreferrer\">link</a>");
    }

    @Test
    @DisplayName("分页不会因前页已修复而漏掉后页")
    void repairsAcrossPageBoundaries() throws Exception {
        for (int id = 1; id <= 102; id++) seed(WorkType.ARTWORK, id, RAW, TWICE);
        assertThat(task.preview()).isEqualTo(102);
        task.execute(context);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM artworks WHERE description=?", Integer.class, FIXED)).isEqualTo(102);
    }

    @ParameterizedTest
    @EnumSource(WorkType.class)
    @DisplayName("修复历史说明时持久化路径、查询、片段及跳转链接的正确目标")
    void persistsUsableUrlComponents(WorkType type) throws Exception {
        String[] hrefs = {
                "https://example.com/?q=hello world&x=1",
                "https://example.com/?q=&quot;hello&quot;&x=1",
                "/jump.php?https%3A%2F%2Fexample.com%2F%3Fq%3Dhello%20world%26x%3D1",
                "https://example.com/a b?x=1&y=2",
                "https://example.com/?x=1&y=2#section one",
                "/jump.php?https%3A%2F%2Fexample.com%2Fa%20b",
                "/a b?x=1&y=2#section one",
                "https://example.com/a&quot;b?x=1&y=2#section&quot;one",
                "https://example.com/a%20b?x=1&y=2#section%20one",
                "/a\u00a0b?x=1&y=2#e😀f",
                "https://example.com/#/works/1#notes",
                "https://example.com/#/works/1%23notes",
                "/#/works/1#notes",
                "/#/works/1%23notes",
                "#/works/1#notes",
                "#/works/1%23notes",
                "/jump.php?https%3A%2F%2Fexample.com%2F%23%2Fworks%2F1%23notes",
                "/jump.php?https%3A%2F%2Fexample.com%2F%23%2Fworks%2F1%2523notes"
        };
        String[] targets = {
                "https://example.com/?q=hello%20world&amp;x=1",
                "https://example.com/?q=%22hello%22&amp;x=1",
                "https://example.com/?q=hello%20world&amp;x=1",
                "https://example.com/a%20b?x=1&amp;y=2",
                "https://example.com/?x=1&amp;y=2#section%20one",
                "https://example.com/a%20b",
                "https://www.pixiv.net/a%20b?x=1&amp;y=2#section%20one",
                "https://example.com/a%22b?x=1&amp;y=2#section%22one",
                "https://example.com/a%20b?x=1&amp;y=2#section%20one",
                "https://www.pixiv.net/a%C2%A0b?x=1&amp;y=2#e%F0%9F%98%80f",
                "https://example.com/#/works/1#notes",
                "https://example.com/#/works/1%23notes",
                "https://www.pixiv.net/#/works/1#notes",
                "https://www.pixiv.net/#/works/1%23notes",
                "#/works/1#notes",
                "#/works/1%23notes",
                "https://example.com/#/works/1#notes",
                "https://example.com/#/works/1%23notes"
        };
        for (int i = 0; i < hrefs.length; i++) {
            String raw = "&amp; <a href=\"" + hrefs[i] + "\">link</a>";
            seed(type, i + 1, raw, LegacyDescriptionHtml.normalizeLinks(raw));
            seed(type, i + 1 + hrefs.length, raw,
                    LegacyDescriptionHtml.normalizeLinks(LegacyDescriptionHtml.normalizeLinks(raw)));
        }
        var before = jdbc.queryForList("SELECT * FROM " + table(type));
        assertThat(task.preview()).isEqualTo(hrefs.length * 2);
        assertThat(jdbc.queryForList("SELECT * FROM " + table(type))).isEqualTo(before);
        task.execute(context);
        try (var reopened = java.sql.DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("fixture.db"));
             var query = reopened.createStatement();
             var rows = query.executeQuery("SELECT description FROM " + table(type) + " ORDER BY " + key(type))) {
            for (int i = 0; i < targets.length * 2; i++) {
                assertThat(rows.next()).isTrue();
                String expected = "&amp; <a href=\"" + targets[i % targets.length]
                        + "\" target=\"_blank\" rel=\"noopener noreferrer\">link</a>";
                assertThat(rows.getString(1)).isEqualTo(expected);
                assertThat(PixivDescriptionHtml.normalizeLinks(rows.getString(1))).isEqualTo(expected);
            }
            assertThat(rows.next()).isFalse();
        }
        assertThat(task.preview()).isZero();
    }

    @Test
    @Timeout(5)
    @DisplayName("异常长说明可预览和修复，旧输出匹配不反复扫描未闭合标签")
    void repairsUnclosedTagsWithinMaintenanceWindow() throws Exception {
        String raw = "&amp;" + "<a ".repeat(16000);
        String old = "&amp;amp;" + "&lt;a ".repeat(16000);
        assertThat(LegacyDescriptionHtml.normalizeLinks(raw)).isEqualTo(old);
        seed(WorkType.ARTWORK, 1, raw, old);
        seed(WorkType.NOVEL, 2, raw, LegacyDescriptionHtml.normalizeLinks(old));
        assertThat(task.preview()).isEqualTo(2);
        assertThat(description(WorkType.ARTWORK, 1)).isEqualTo(old);
        var coordinator = new MaintenanceCoordinator(java.util.List.of(task), new MaintenanceProperties());
        assertThat(coordinator.runManually()).isTrue();
        assertThat(coordinator.isPaused()).isFalse();
        String expected = "&amp;" + "&lt;a ".repeat(16000);
        assertThat(description(WorkType.ARTWORK, 1)).isEqualTo(expected);
        assertThat(description(WorkType.NOVEL, 2)).isEqualTo(expected);
        assertThat(task.preview()).isZero();
    }

    @Test
    @Timeout(5)
    @DisplayName("历史清理保留未闭合标签、嵌套起点和引号跨标签的原匹配语义")
    void preservesLegacyMalformedMarkupSemantics() {
        assertThat(LegacyDescriptionHtml.normalizeLinks("<a href=/users/1"))
                .isEqualTo("&lt;a href=\"https://www.pixiv.net/users/1\"");
        assertThat(LegacyDescriptionHtml.normalizeLinks("<a title=\">\" href=/users/1>"))
                .isEqualTo("<a href=\"\" target=\"_blank\" rel=\"noopener noreferrer\">\" href=/users/1&gt;");
        assertThat(LegacyDescriptionHtml.normalizeLinks("<a href=\"https://x/?v=>&x=1\">tail</a>"))
                .isEqualTo("<a href=\"https://x/?v=&amp;gt;&amp;amp;x=1\" target=\"_blank\" rel=\"noopener noreferrer\">tail</a>");
        assertThat(LegacyDescriptionHtml.normalizeLinks("<a <a href=/users/1>tail</a>"))
                .isEqualTo("<a href=\"https://www.pixiv.net/users/1\" target=\"_blank\" rel=\"noopener noreferrer\">tail</a>");
        assertThat(LegacyDescriptionHtml.normalizeLinks("<a title=\" href='/users/2'\" href=/users/1>tail</a>"))
                .isEqualTo("<a href=\"https://www.pixiv.net/users/2\" target=\"_blank\" rel=\"noopener noreferrer\">tail</a>");
        assertThat(LegacyDescriptionHtml.normalizeLinks("&amp;" + "<a href=x ".repeat(16000)))
                .isEqualTo("&amp;amp;" + "&lt;a href=\"https://www.pixiv.net/x\" ".repeat(16000));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"description", "metadata_json", "deleted"})
    @DisplayName("扫描后被编辑、换快照或删除的记录不会被修复覆盖")
    void doesNotOverwriteConcurrentChanges(String column) throws Exception {
        seed(WorkType.ARTWORK, 1, RAW, TWICE);
        var racing = spy(database);
        doAnswer(invocation -> {
            jdbc.update("UPDATE artworks SET " + column + "=? WHERE artwork_id=1", column.equals("deleted") ? 1 : "changed");
            return invocation.callRealMethod();
        }).when(racing).transaction(any());
        new WorkDescriptionRepairTask(racing, mapper).execute(context);
        assertThat(description(WorkType.ARTWORK, 1)).isEqualTo(column.equals("description") ? "changed" : TWICE);
    }

    @Test
    @DisplayName("写入失败抛回维护协调器，原记录保留并可重试")
    void preservesFailedWrites() throws Exception {
        seed(WorkType.ARTWORK, 1, RAW, TWICE);
        jdbc.execute("CREATE TRIGGER reject_repair BEFORE UPDATE OF description ON artworks BEGIN SELECT RAISE(ABORT, 'fixture'); END");
        assertThatThrownBy(() -> task.execute(context)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(description(WorkType.ARTWORK, 1)).isEqualTo(TWICE);
        jdbc.execute("DROP TRIGGER reject_repair");
        task.execute(context);
        assertThat(description(WorkType.ARTWORK, 1)).isEqualTo(FIXED);
    }

    @Test
    @DisplayName("数据库不可用与线程中断明确终止修复")
    void stopsOnDatabaseLossOrInterruption() throws Exception {
        var unavailable = mock(MainDatabase.class);
        when(unavailable.read(any())).thenThrow(new DatabaseUnavailableException());
        assertThatThrownBy(() -> new WorkDescriptionRepairTask(unavailable, mapper).execute(context))
                .isInstanceOf(DatabaseUnavailableException.class);
        verify(unavailable, never()).transaction(any());
        seed(WorkType.ARTWORK, 1, RAW, TWICE);
        Thread.currentThread().interrupt();
        try { assertThatThrownBy(() -> task.execute(context)).isInstanceOf(InterruptedException.class); }
        finally { Thread.interrupted(); }
        assertThat(description(WorkType.ARTWORK, 1)).isEqualTo(TWICE);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    @DisplayName("读取记录后中断会终止预览或执行，保留中断标记且不写入")
    void stopsAfterInterruptedRead(boolean preview) throws Exception {
        seed(WorkType.ARTWORK, 1, RAW, TWICE);
        var interrupted = spy(database);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            if (result instanceof java.util.List<?> rows && !rows.isEmpty()
                    && rows.get(0) instanceof java.util.Map<?, ?> row && row.containsKey("description")) {
                Thread.currentThread().interrupt();
            }
            return result;
        }).when(interrupted).read(any());
        var repair = new WorkDescriptionRepairTask(interrupted, mapper);
        try {
            assertThatThrownBy(() -> {
                if (preview) repair.preview();
                else repair.execute(context);
            }).isInstanceOf(InterruptedException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        verify(interrupted, never()).transaction(any());
        assertThat(description(WorkType.ARTWORK, 1)).isEqualTo(TWICE);
    }

    @Test
    @DisplayName("修复被中断后协调器释放维护窗口，原记录保留且可重试")
    void releasesWindowAfterInterruptedRepair() throws Exception {
        seed(WorkType.ARTWORK, 1, RAW, TWICE);
        var coordinator = new MaintenanceCoordinator(java.util.List.of(task), new MaintenanceProperties());
        Thread.currentThread().interrupt();
        try {
            assertThat(coordinator.runManually()).isTrue();
            assertThat(coordinator.isPaused()).isFalse();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        assertThat(description(WorkType.ARTWORK, 1)).isEqualTo(TWICE);
        assertThat(coordinator.runManually()).isTrue();
        assertThat(coordinator.isPaused()).isFalse();
        assertThat(description(WorkType.ARTWORK, 1)).isEqualTo(FIXED);
    }

    @Test
    @DisplayName("取元数据与下载保存重复清理后，数据库回读只需一次 HTML 解析")
    void preservesFetchSaveReadRenderSemantics() throws Exception {
        String fetched = PixivDescriptionHtml.normalizeLinks(RAW);
        String saved = PixivDescriptionHtml.normalizeLinks(fetched);
        seed(WorkType.ARTWORK, 1, RAW, saved);
        StringBuilder rendered = new StringBuilder();
        new javax.swing.text.html.parser.ParserDelegator().parse(new java.io.StringReader(description(WorkType.ARTWORK, 1)),
                new javax.swing.text.html.HTMLEditorKit.ParserCallback() {
                    @Override public void handleText(char[] data, int pos) { rendered.append(data); }
                }, true);
        assertThat(rendered.toString()).isEqualTo("A's note, \"quoted\" &#39;");
        assertThat(task.preview()).isZero();
    }

    private void seed(WorkType type, long id, String raw, String current) {
        jdbc.update("INSERT INTO " + table(type) + "(" + key(type) + ", title, folder, count, extensions, time, description, metadata_json)"
                + " VALUES(?, 'Title &#39;', ?, 1, 'jpg', ?, ?, ?)", id, directory.toString(), id, current, snapshot(type, id, raw));
    }

    private String snapshot(WorkType type, long id, String raw) {
        var body = mapper.createObjectNode().put("description", raw);
        var curator = new WorkMetaCurator(mapper);
        var meta = type == WorkType.ARTWORK ? curator.curateArtwork(id, body, null, "forward") : curator.curateNovel(id, body, "forward");
        return meta.hasDocument() ? meta.document().toString() : "{}";
    }

    private String description(WorkType type, long id) {
        return jdbc.queryForObject("SELECT description FROM " + table(type) + " WHERE " + key(type) + "=?", String.class, id);
    }

    private static String table(WorkType type) { return type == WorkType.ARTWORK ? "artworks" : "novels"; }
    private static String key(WorkType type) { return type == WorkType.ARTWORK ? "artwork_id" : "novel_id"; }
}
