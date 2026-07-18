package top.sywyar.pixivdownload.novel.metadata;

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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("小说插件自有详情 SQL 投影")
class NovelWorkDetailsSqlTest {

    private SingleConnectionDataSource dataSource;
    private SqlSession sqlSession;
    private NovelMapper mapper;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        dataSource = new SingleConnectionDataSource();
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setUrl("jdbc:sqlite::memory:");
        dataSource.setSuppressClose(true);

        Environment environment = new Environment("test", new JdbcTransactionFactory(), dataSource);
        Configuration configuration = new Configuration(environment);
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(NovelMapper.class);
        sqlSession = new SqlSessionFactoryBuilder().build(configuration).openSession(true);
        mapper = sqlSession.getMapper(NovelMapper.class);
        jdbc = new JdbcTemplate(dataSource);

        jdbc.execute("""
                CREATE TABLE novels (
                    novel_id INTEGER PRIMARY KEY,
                    word_count INTEGER,
                    text_length INTEGER,
                    reading_time_seconds INTEGER,
                    page_count INTEGER,
                    x_language TEXT,
                    cover_ext TEXT,
                    deleted INTEGER NOT NULL DEFAULT 0
                )
                """);
        jdbc.execute("""
                CREATE TABLE novel_images (
                    novel_id INTEGER NOT NULL,
                    image_id TEXT NOT NULL,
                    PRIMARY KEY (novel_id, image_id)
                )
                """);
        jdbc.execute("""
                CREATE TABLE novel_translations (
                    novel_id INTEGER NOT NULL,
                    lang_code TEXT NOT NULL,
                    PRIMARY KEY (novel_id, lang_code)
                )
                """);
    }

    @AfterEach
    void tearDown() {
        sqlSession.close();
        dataSource.destroy();
    }

    @Test
    @DisplayName("真实 MyBatis 查询映射全部详情列并排除软删除小说的派生行")
    void mapsOwnedDetailsAndFiltersSoftDeletedRows() {
        insertNovel(1L, false);
        insertNovel(2L, true);
        jdbc.batchUpdate(
                "INSERT INTO novel_images(novel_id, image_id) VALUES (?, ?)",
                List.of(new Object[]{1L, "img-b"}, new Object[]{1L, "img-a"},
                        new Object[]{2L, "deleted-image"}));
        jdbc.batchUpdate(
                "INSERT INTO novel_translations(novel_id, lang_code) VALUES (?, ?)",
                List.of(new Object[]{1L, "zh-CN"}, new Object[]{1L, "en"},
                        new Object[]{2L, "deleted-lang"}));

        NovelWorkDetails details = new NovelWorkDetailsRepository(mapper)
                .findAll(List.of(2L, 1L))
                .get(1L);

        assertThat(details).isEqualTo(new NovelWorkDetails(
                1L, 101, 201, 31, 4, "ja", "jpg",
                List.of("img-a", "img-b"), List.of("en", "zh-CN")));
        assertThat(mapper.findWorkDetailsByIds(List.of(1L, 2L)))
                .extracting(NovelMapper.NovelWorkDetailsRow::novelId)
                .containsExactly(1L);
        assertThat(mapper.findNovelImageIdsByIds(List.of(1L, 2L)))
                .extracting(NovelMapper.NovelWorkDetailValueRow::value)
                .containsExactly("img-a", "img-b");
        assertThat(mapper.findTranslationLangsByIds(List.of(1L, 2L)))
                .extracting(NovelMapper.NovelWorkDetailValueRow::value)
                .containsExactly("en", "zh-CN");
    }

    @Test
    @DisplayName("可空数值与文本详情列经真实 MyBatis 查询保持为 null")
    void mapsNullableOwnedDetailColumns() {
        jdbc.update("""
                        INSERT INTO novels(
                            novel_id, word_count, text_length, reading_time_seconds, page_count,
                            x_language, cover_ext, deleted
                        ) VALUES (?, NULL, NULL, NULL, NULL, NULL, NULL, 0)
                        """,
                3L);

        NovelWorkDetails details = new NovelWorkDetailsRepository(mapper)
                .findAll(List.of(3L))
                .get(3L);

        assertThat(details).isEqualTo(new NovelWorkDetails(
                3L, null, null, null, null, null, null, List.of(), List.of()));
    }

    private void insertNovel(long id, boolean deleted) {
        jdbc.update("""
                        INSERT INTO novels(
                            novel_id, word_count, text_length, reading_time_seconds, page_count,
                            x_language, cover_ext, deleted
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                id, 100 + id, 200 + id, 30 + id, 4, "ja", "jpg", deleted);
    }
}
