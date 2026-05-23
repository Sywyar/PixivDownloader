package top.sywyar.pixivdownload.novel.db;

import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("小说正文全文检索（FTS5 trigram）")
class NovelFtsSearchTest {

    private SingleConnectionDataSource dataSource;
    private SqlSession sqlSession;
    private NovelMapper mapper;

    @BeforeEach
    void setUp() {
        dataSource = new SingleConnectionDataSource();
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setUrl("jdbc:sqlite::memory:");
        dataSource.setSuppressClose(true);

        Environment env = new Environment("test", new JdbcTransactionFactory(), dataSource);
        Configuration config = new Configuration(env);
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(NovelMapper.class);

        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(config);
        sqlSession = factory.openSession(true);
        mapper = sqlSession.getMapper(NovelMapper.class);
        mapper.createNovelsTable();
        mapper.createNovelFtsTable();
    }

    @AfterEach
    void tearDown() {
        sqlSession.close();
        dataSource.destroy();
    }

    private void insertNovel(long id, String rawContent) {
        mapper.insertOrReplace(id, "title-" + id, "/tmp", 0, "txt", id,
                0, false, 100L, "desc", 1L, null, null, null,
                0, 0, 0, 0, false, "ja", rawContent, null);
    }

    @Test
    @DisplayName("backfill 后能命中中文正文关键词")
    void shouldMatchCjkKeywordAfterBackfill() {
        insertNovel(1L, "这是第一章的正文，主角踏上了冒险旅程。");
        insertNovel(2L, "完全无关的另一篇小说内容。");
        mapper.backfillNovelFts();

        List<Long> hits = mapper.searchNovelFtsIds("\"冒险旅程\"");

        assertThat(hits).containsExactly(1L);
    }

    @Test
    @DisplayName("insert/delete 能增量维护索引")
    void shouldMaintainIndexIncrementally() {
        insertNovel(10L, "keyword apple banana cherry");
        mapper.insertNovelFts(10L, "keyword apple banana cherry");

        assertThat(mapper.searchNovelFtsIds("\"banana\"")).containsExactly(10L);

        mapper.deleteNovelFts(10L);
        assertThat(mapper.searchNovelFtsIds("\"banana\"")).isEmpty();
    }

    @Test
    @DisplayName("短关键词回退到 raw_content LIKE 扫描")
    void shouldFallBackToLikeForShortTerms() {
        insertNovel(20L, "猫と犬");
        insertNovel(21L, "鳥のうた");

        List<Long> hits = mapper.findNovelIdsByContentLike("%猫%");

        assertThat(hits).containsExactly(20L);
    }

    @Test
    @DisplayName("短关键词 LIKE 回退按字面量匹配通配符")
    void shouldEscapeLikeWildcardsForShortTerms() {
        insertNovel(30L, "100% literal");
        insertNovel(31L, "100 percent");
        insertNovel(32L, "A_B literal");
        insertNovel(33L, "ACB literal");
        NovelDatabase database = new NovelDatabase(mapper, null, null);

        assertThat(database.searchNovelContentIds("%")).containsExactly(30L);
        assertThat(database.searchNovelContentIds("_")).containsExactly(32L);
    }
}
