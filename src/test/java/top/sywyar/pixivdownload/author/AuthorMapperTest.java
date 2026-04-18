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
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

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
        authorMapper.createAuthorsTable();
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
}
