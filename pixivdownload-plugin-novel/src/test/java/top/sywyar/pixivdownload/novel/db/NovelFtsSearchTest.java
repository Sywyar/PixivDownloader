package top.sywyar.pixivdownload.novel.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import top.sywyar.pixivdownload.core.db.pathprefix.StoredPathCodec;
import top.sywyar.pixivdownload.core.work.service.WorkTagCatalog;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
@DisplayName("小说正文全文检索（FTS5 trigram）")
class NovelFtsSearchTest {

    private SingleConnectionDataSource dataSource;
    private NovelMapper mapper;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        dataSource = new SingleConnectionDataSource();
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setUrl("jdbc:sqlite::memory:");
        dataSource.setSuppressClose(true);

        jdbc = new JdbcTemplate(dataSource);
        NovelSqliteTestSchema.createNovelRows(jdbc);
        NovelSqliteTestSchema.createSoftDeleteDerivedTables(jdbc);
        mapper = NovelMapperTestFixture.mapper(dataSource);
        mapper.initializeContentSearch();
        mapper.createNovelSoftDeleteCleanupTrigger();
        mapper.cleanupExistingDeletedNovelState();
    }

    @AfterEach
    void tearDown() {
        dataSource.destroy();
    }

    private void insertNovel(long id, String rawContent) {
        jdbc.update(
                "INSERT INTO novels(novel_id, raw_content) VALUES (?, ?)",
                id,
                rawContent);
    }

    @Test
    @DisplayName("backfill 后能命中中文正文关键词")
    void shouldMatchCjkKeywordAfterBackfill() {
        insertNovel(1L, "这是第一章的正文，主角踏上了冒险旅程。");
        insertNovel(2L, "完全无关的另一篇小说内容。");
        mapper.refreshContentSearch(null);

        List<Long> hits = mapper.searchContent("冒险旅程");

        assertThat(hits).containsExactly(1L);
    }

    @Test
    @DisplayName("insert/delete 能增量维护索引")
    void shouldMaintainIndexIncrementally() {
        insertNovel(10L, "keyword apple banana cherry");
        mapper.refreshContentSearch(10L);

        assertThat(mapper.searchContent("banana")).containsExactly(10L);

        jdbc.update("UPDATE novels SET raw_content = ? WHERE novel_id = ?", "replacement content", 10L);
        mapper.refreshContentSearch(10L);
        assertThat(mapper.searchContent("banana")).isEmpty();
    }

    @Test
    @DisplayName("短关键词回退到 raw_content LIKE 扫描")
    void shouldFallBackToLikeForShortTerms() {
        insertNovel(20L, "猫と犬");
        insertNovel(21L, "鳥のうた");

        List<Long> hits = mapper.searchContent("猫");

        assertThat(hits).containsExactly(20L);
    }

    @Test
    @DisplayName("插件数据库正文搜索把 LIKE 通配符按字面量处理")
    void shouldEscapeLikeWildcardsInPluginOwnedSearch() {
        insertNovel(30L, "100% literal");
        insertNovel(31L, "100 percent");
        insertNovel(32L, "A_B literal");
        insertNovel(33L, "ACB literal");
        NovelDatabase database = new NovelDatabase(
                mapper, mock(WorkTagCatalog.class), mock(StoredPathCodec.class), () -> true);

        assertThat(database.searchNovelContentIds("%")).containsExactly(30L);
        assertThat(database.searchNovelContentIds("_")).containsExactly(32L);
    }

    @Test
    @DisplayName("软删除触发器只原子清理普通派生状态并由插件回收陈旧 FTS")
    void shouldCleanPluginOwnedDerivedStateOnSoftDelete() {
        insertNovel(40L, "需要从索引移除的正文");
        mapper.refreshContentSearch(40L);
        jdbc.update("INSERT INTO novel_tags(novel_id, tag_id) VALUES (?, ?)", 40L, 7L);
        jdbc.update("INSERT INTO novel_collections(collection_id, novel_id, added_time) VALUES (?, ?, ?)",
                9L, 40L, 1L);
        jdbc.update("INSERT INTO novel_images(novel_id, image_id, ext) VALUES (?, ?, ?)",
                40L, "image-a", "jpg");
        jdbc.update("INSERT INTO novel_translations(novel_id, lang_code, raw_content, created_time)"
                        + " VALUES (?, ?, ?, ?)",
                40L, "zh-CN", "译文", 1L);
        jdbc.update("INSERT INTO novel_narration_scripts("
                        + "novel_id, lang, cast_id, segment_size, analyzed_time, script_json)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                40L, "ja", 1L, 100, 1L, "{}");

        jdbc.update("UPDATE novels SET deleted = 1 WHERE novel_id = ?", 40L);

        assertThat(jdbc.queryForObject(
                "SELECT deleted FROM novels WHERE novel_id = 40", Integer.class)).isEqualTo(1);
        for (String table : List.of(
                "novel_tags", "novel_collections", "novel_images",
                "novel_translations", "novel_narration_scripts")) {
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM " + table + " WHERE novel_id = 40", Integer.class))
                    .as(table)
                    .isZero();
        }
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM novels_fts WHERE rowid = 40", Integer.class)).isEqualTo(1);
        assertThat(mapper.searchContent("需要从索引移除")).isEmpty();

        mapper.refreshContentSearch(null);

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM novels_fts WHERE rowid = 40", Integer.class)).isZero();
    }

    @Test
    @DisplayName("FTS 表缺失时主行软删除与普通派生清理仍能提交")
    void shouldKeepSoftDeleteAvailableWhenFtsTableIsMissing() {
        insertNovel(41L, "索引表缺失时仍需删除");
        jdbc.update("INSERT INTO novel_tags(novel_id, tag_id) VALUES (?, ?)", 41L, 8L);
        jdbc.execute("DROP TABLE novels_fts");

        assertThatCode(() -> jdbc.update(
                "UPDATE novels SET deleted = 1 WHERE novel_id = ?", 41L))
                .doesNotThrowAnyException();

        assertThat(jdbc.queryForObject(
                "SELECT deleted FROM novels WHERE novel_id = 41", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM novel_tags WHERE novel_id = 41", Integer.class)).isZero();
    }

    @Test
    @DisplayName("启动创建或清理 FTS 失败时仍继续完成其它初始化")
    void shouldTreatDeletedFtsCleanupAsBestEffortDuringInitialization() {
        NovelMapper failingMapper = mock(NovelMapper.class);
        doThrow(new IllegalStateException("broken fts ddl"))
                .when(failingMapper).initializeContentSearch();
        doThrow(new IllegalStateException("broken fts"))
                .when(failingMapper).refreshContentSearch(null);
        NovelDatabase database = new NovelDatabase(
                failingMapper, mock(WorkTagCatalog.class), mock(StoredPathCodec.class), () -> true);

        assertThatCode(database::init).doesNotThrowAnyException();

        verify(failingMapper).createNovelSoftDeleteCleanupTrigger();
        verify(failingMapper).refreshContentSearch(null);
    }

}
