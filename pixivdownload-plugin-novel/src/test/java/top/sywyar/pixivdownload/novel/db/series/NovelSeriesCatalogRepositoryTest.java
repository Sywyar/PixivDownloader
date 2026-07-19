package top.sywyar.pixivdownload.novel.db.series;

import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import top.sywyar.pixivdownload.novel.db.NovelMapper;
import top.sywyar.pixivdownload.core.work.model.WorkTag;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("小说系列目录持久化投影")
class NovelSeriesCatalogRepositoryTest {

    private SingleConnectionDataSource dataSource;
    private SqlSession sqlSession;
    private JdbcTemplate jdbc;
    private NovelSeriesCatalogRepository repository;

    @BeforeEach
    void setUp() {
        dataSource = new SingleConnectionDataSource();
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setUrl("jdbc:sqlite::memory:");
        dataSource.setSuppressClose(true);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE novels (novel_id INTEGER PRIMARY KEY, series_id INTEGER, deleted INTEGER NOT NULL)");
        jdbc.execute("CREATE TABLE novel_series (series_id INTEGER PRIMARY KEY, title TEXT, author_id INTEGER, cover_ext TEXT)");
        jdbc.execute("CREATE TABLE tags (tag_id INTEGER PRIMARY KEY, name TEXT, translated_name TEXT)");
        jdbc.execute("CREATE TABLE novel_series_tags (series_id INTEGER, tag_id INTEGER)");

        Configuration configuration = new Configuration(new Environment(
                "test", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(NovelMapper.class);
        sqlSession = new SqlSessionFactoryBuilder().build(configuration).openSession(true);
        repository = new NovelSeriesCatalogRepository(sqlSession.getMapper(NovelMapper.class));
    }

    @AfterEach
    void tearDown() {
        sqlSession.close();
        dataSource.destroy();
    }

    @Test
    @DisplayName("目录只统计未删除小说并为缺失系列行回退 id 标题")
    void listsActiveSeriesWithOwnedDecorations() {
        jdbc.update("INSERT INTO novel_series(series_id, title, author_id, cover_ext) VALUES (?, ?, ?, ?)",
                700L, "系列甲", 801L, "png");
        jdbc.update("INSERT INTO novels(novel_id, series_id, deleted) VALUES (?, ?, ?)", 1L, 700L, 0);
        jdbc.update("INSERT INTO novels(novel_id, series_id, deleted) VALUES (?, ?, ?)", 2L, 700L, 0);
        jdbc.update("INSERT INTO novels(novel_id, series_id, deleted) VALUES (?, ?, ?)", 3L, 700L, 1);
        jdbc.update("INSERT INTO novels(novel_id, series_id, deleted) VALUES (?, ?, ?)", 4L, 701L, 0);
        jdbc.update("INSERT INTO novels(novel_id, series_id, deleted) VALUES (?, ?, ?)", 5L, null, 0);
        jdbc.update("INSERT INTO novel_series(series_id, title, author_id, cover_ext) VALUES (?, ?, ?, ?)",
                702L, null, 802L, null);
        jdbc.update("INSERT INTO novel_series(series_id, title, author_id, cover_ext) VALUES (?, ?, ?, ?)",
                703L, "", 803L, null);
        jdbc.update("INSERT INTO novels(novel_id, series_id, deleted) VALUES (?, ?, ?)", 6L, 702L, 0);
        jdbc.update("INSERT INTO novels(novel_id, series_id, deleted) VALUES (?, ?, ?)", 7L, 703L, 0);

        assertThat(repository.findAll()).containsExactly(
                new NovelSeriesCatalogRow(700L, "系列甲", 801L, 2L, "png"),
                new NovelSeriesCatalogRow(701L, "701", null, 1L, null),
                new NovelSeriesCatalogRow(702L, null, 802L, 1L, null),
                new NovelSeriesCatalogRow(703L, "", 803L, 1L, null));
    }

    @Test
    @DisplayName("系列标签使用单次批量查询并按系列分组")
    void groupsSeriesTagsInOneBatch() {
        jdbc.update("INSERT INTO tags(tag_id, name, translated_name) VALUES (?, ?, ?)",
                21L, "魔法", "magic");
        jdbc.update("INSERT INTO tags(tag_id, name, translated_name) VALUES (?, ?, ?)",
                22L, "冒险", null);
        jdbc.update("INSERT INTO novel_series_tags(series_id, tag_id) VALUES (?, ?)", 700L, 22L);
        jdbc.update("INSERT INTO novel_series_tags(series_id, tag_id) VALUES (?, ?)", 700L, 21L);

        Map<Long, List<WorkTag>> tags = repository.findTags(List.of(700L, 701L));

        assertThat(tags).containsOnlyKeys(700L);
        assertThat(tags.get(700L)).containsExactly(
                new WorkTag(21L, "魔法", "magic"),
                new WorkTag(22L, "冒险", null));
    }
}
