package top.sywyar.pixivdownload.novel.db;

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
import top.sywyar.pixivdownload.core.db.schema.DatabaseInitializer;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.plugin.registry.DatabaseSchemaRegistry;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("小说有效 / 软删除批量状态查询")
class NovelDownloadedStatusTest {

    private SingleConnectionDataSource dataSource;
    private SqlSession sqlSession;
    private NovelMapper mapper;
    private JdbcTemplate jdbcTemplate;

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
        jdbcTemplate = new JdbcTemplate(dataSource);

        DatabaseSchemaRegistry registry = DatabaseSchemaRegistry.forBuiltInPlugins();
        new DatabaseInitializer(
                jdbcTemplate,
                registry.contributions(),
                registry.mergedSchema(),
                TestI18nBeans.appMessages(),
                event -> {})
                .initialize();
    }

    @AfterEach
    void tearDown() {
        sqlSession.close();
        dataSource.destroy();
    }

    @Test
    @DisplayName("两类查询严格按 deleted 标记拆分且忽略不存在的 ID")
    void separatesActiveAndDeletedIds() {
        insertNovel(1L);
        insertNovel(2L);
        jdbcTemplate.update("UPDATE novels SET deleted = 1 WHERE novel_id = ?", 2L);

        List<Long> requested = List.of(1L, 2L, 3L);

        assertThat(mapper.findDownloadedStatuses(requested))
                .containsExactlyInAnyOrder(
                        new NovelDownloadedStatusRow(1L, false),
                        new NovelDownloadedStatusRow(2L, true));
    }

    private void insertNovel(long id) {
        mapper.insertOrReplace(id, "title-" + id, "/tmp", 0, "txt", id,
                0, false, 100L, "desc", 1L, null, null, null,
                0, 0, 0, 0, false, "ja", "content", null);
    }
}
