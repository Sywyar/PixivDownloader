package top.sywyar.pixivdownload.author;

import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import top.sywyar.pixivdownload.core.db.DatabaseInitializer;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.plugin.DatabaseSchemaRegistry;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AuthorMapper 集成测试")
class AuthorMapperTest {

    private SingleConnectionDataSource dataSource;
    private SqlSession sqlSession;
    private AuthorMapper authorMapper;

    @BeforeEach
    void setUp() {
        dataSource = new SingleConnectionDataSource();
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setUrl("jdbc:sqlite::memory:");
        dataSource.setSuppressClose(true);

        Environment env = new Environment("test", new JdbcTransactionFactory(), dataSource);
        Configuration config = new Configuration(env);
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(AuthorMapper.class);

        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(config);
        sqlSession = factory.openSession(true);
        authorMapper = sqlSession.getMapper(AuthorMapper.class);

        // 建表 / 补列 / 索引统一由 DatabaseInitializer 执行（含 PagedAuthorsTests 用的 artworks 表）
        DatabaseSchemaRegistry registry = DatabaseSchemaRegistry.forBuiltInPlugins();
        new DatabaseInitializer(new JdbcTemplate(dataSource),
                registry.contributions(), registry.mergedSchema(),
                TestI18nBeans.appMessages(), event -> {}).initialize();
    }

    @AfterEach
    void tearDown() {
        sqlSession.close();
        dataSource.destroy();
    }

    @Test
    @DisplayName("应插入并查询作者")
    void shouldInsertAndFindAuthor() {
        int inserted = authorMapper.insertIfAbsent(1001L, "Alice", 100L);

        Author author = authorMapper.findById(1001L);
        List<Author> authors = authorMapper.findAll();

        assertThat(inserted).isEqualTo(1);
        assertThat(author).isEqualTo(new Author(1001L, "Alice", 100L));
        assertThat(authors).containsExactly(new Author(1001L, "Alice", 100L));
    }

    @Test
    @DisplayName("更新作者名时应推进 updatedTime")
    void shouldUpdateAuthorNameAndTimestamp() {
        authorMapper.insertIfAbsent(1001L, "Alice", 100L);

        int updated = authorMapper.updateName(1001L, "AliceNew", 200L);

        assertThat(updated).isEqualTo(1);
        assertThat(authorMapper.findById(1001L))
                .isEqualTo(new Author(1001L, "AliceNew", 200L));
    }

    @Nested
    @DisplayName("findAuthorsWithArtworks 分页/排序/搜索")
    class PagedAuthorsTests {

        private JdbcTemplate jdbc;

        @BeforeEach
        void seedArtworks() {
            // artworks 表已由 setUp 的 DatabaseInitializer 按受管 schema 建好
            jdbc = new JdbcTemplate(dataSource);

            // 已知作者
            authorMapper.insertIfAbsent(2001L, "Alice", 100L);
            authorMapper.insertIfAbsent(2002L, "Bob", 100L);
            authorMapper.insertIfAbsent(2003L, "charlie", 100L);

            // Alice: 3 件作品 / Bob: 1 件 / charlie: 2 件 / 9999(无作者表条目): 2 件
            insertArtwork(1L, 2001L);
            insertArtwork(2L, 2001L);
            insertArtwork(3L, 2001L);
            insertArtwork(4L, 2002L);
            insertArtwork(5L, 2003L);
            insertArtwork(6L, 2003L);
            insertArtwork(7L, 9999L);
            insertArtwork(8L, 9999L);
            // 没有作者的作品，应被过滤掉（author_id IS NULL）
            insertArtwork(9L, null);
        }

        private void insertArtwork(long id, Long authorId) {
            jdbc.update("INSERT INTO artworks(artwork_id, title, folder, count, extensions, time, author_id)"
                            + " VALUES(?, ?, ?, ?, ?, ?, ?)",
                    id, "title-" + id, "/f", 1, "jpg", id, authorId);
        }

        @Test
        @DisplayName("name 排序应按 LOWER(name) 升序，并保留 fallback 占位的作者")
        void shouldSortByLowercaseName() {
            List<AuthorSummary> rows = authorMapper.findAuthorsWithArtworks("%", "name", 100, 0);

            // 缺失作者名时 fallback 为作者 ID 文本（"9999"），ASCII 数字小于字母，所以 "9999" 排最前
            assertThat(rows).extracting(AuthorSummary::name)
                    .containsExactly("9999", "Alice", "Bob", "charlie");
        }

        @Test
        @DisplayName("artworks 排序应按作品数降序")
        void shouldSortByArtworkCountDescending() {
            List<AuthorSummary> rows = authorMapper.findAuthorsWithArtworks("%", "artworks", 100, 0);

            assertThat(rows).element(0)
                    .matches(s -> s.authorId() == 2001L && s.artworkCount() == 3);
            // 第二位是 charlie 或 9999（都是 2 件），第三位另一个，第四位 Bob（1 件）
            assertThat(rows.get(rows.size() - 1).artworkCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("authorId 排序应按 author_id 升序")
        void shouldSortByAuthorIdAscending() {
            List<AuthorSummary> rows = authorMapper.findAuthorsWithArtworks("%", "authorId", 100, 0);

            assertThat(rows).extracting(AuthorSummary::authorId)
                    .containsExactly(2001L, 2002L, 2003L, 9999L);
        }

        @Test
        @DisplayName("缺失作者名时 fallback 为 author_id 文本")
        void shouldFallbackToAuthorIdTextWhenNameMissing() {
            List<AuthorSummary> rows = authorMapper.findAuthorsWithArtworks("%", "authorId", 100, 0);

            assertThat(rows).filteredOn(s -> s.authorId() == 9999L)
                    .singleElement()
                    .matches(s -> "9999".equals(s.name()) && s.artworkCount() == 2);
        }

        @Test
        @DisplayName("按作者名搜索应支持模糊匹配")
        void shouldSearchByAuthorName() {
            List<AuthorSummary> rows = authorMapper.findAuthorsWithArtworks("%lic%", "name", 100, 0);

            assertThat(rows).extracting(AuthorSummary::authorId).containsExactly(2001L);
        }

        @Test
        @DisplayName("按 author_id 文本搜索应命中")
        void shouldSearchByAuthorIdText() {
            List<AuthorSummary> rows = authorMapper.findAuthorsWithArtworks("%999%", "name", 100, 0);

            assertThat(rows).extracting(AuthorSummary::authorId).containsExactly(9999L);
        }

        @Test
        @DisplayName("分页应只返回当前页的行")
        void shouldHonourLimitAndOffset() {
            List<AuthorSummary> rows = authorMapper.findAuthorsWithArtworks("%", "authorId", 2, 1);

            assertThat(rows).extracting(AuthorSummary::authorId)
                    .containsExactly(2002L, 2003L);
        }

        @Test
        @DisplayName("countAuthorsWithArtworks 应统计有作品的作者数（不含 author_id IS NULL）")
        void shouldCountAuthorsWithArtworks() {
            long all = authorMapper.countAuthorsWithArtworks("%");
            long matching = authorMapper.countAuthorsWithArtworks("%lic%");

            assertThat(all).isEqualTo(4);
            assertThat(matching).isEqualTo(1);
        }
    }
}
