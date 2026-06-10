package top.sywyar.pixivdownload.duplicate;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ImageHashMapper 集成测试")
class ImageHashMapperTest {

    private SingleConnectionDataSource dataSource;
    private SqlSession sqlSession;
    private JdbcTemplate jdbc;
    private ImageHashMapper mapper;

    @BeforeEach
    void setUp() {
        dataSource = new SingleConnectionDataSource();
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setUrl("jdbc:sqlite::memory:");
        dataSource.setSuppressClose(true);

        Environment env = new Environment("test", new JdbcTransactionFactory(), dataSource);
        Configuration config = new Configuration(env);
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(ImageHashMapper.class);

        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(config);
        sqlSession = factory.openSession(true);
        mapper = sqlSession.getMapper(ImageHashMapper.class);
        jdbc = new JdbcTemplate(dataSource);
        createTables();
    }

    @AfterEach
    void tearDown() {
        sqlSession.close();
        dataSource.destroy();
    }

    @Test
    @DisplayName("应 upsert 哈希并映射作品与作者字段")
    void shouldUpsertAndMapHashRows() {
        insertAuthor(9001L, "Alice");
        insertArtwork(1001L, "Artwork A", 9001L, 1, 200L);
        insertArtwork(1002L, "Artwork B", null, 0, 100L);

        mapper.upsert(1001L, 0, "jpg", 42L, 7L, 123L);
        mapper.upsert(1001L, 0, "png", 43L, null, 124L);

        List<ImageHashRow> rows = mapper.findAll();

        assertThat(rows).singleElement()
                .satisfies(row -> {
                    assertThat(row.artworkId()).isEqualTo(1001L);
                    assertThat(row.page()).isZero();
                    assertThat(row.ext()).isEqualTo("png");
                    assertThat(row.dHash()).isEqualTo(43L);
                    assertThat(row.aHash()).isNull();
                    assertThat(row.createdTime()).isEqualTo(124L);
                    assertThat(row.title()).isEqualTo("Artwork A");
                    assertThat(row.authorId()).isEqualTo(9001L);
                    assertThat(row.authorName()).isEqualTo("Alice");
                    assertThat(row.xRestrict()).isEqualTo(1);
                });
        assertThat(mapper.countAllHashRows()).isEqualTo(1);
        assertThat(mapper.maxCreatedTime()).isEqualTo(124L);
        assertThat(mapper.countArtworksMissingHashes()).isEqualTo(1);
        assertThat(mapper.artworkIdsMissingHashes(10)).containsExactly(1002L);
    }

    @Test
    @DisplayName("markNoHash 标记的作品应不再计入缺哈希，且不污染分组查询")
    void shouldMarkNoHashWithoutPollutingGroups() {
        insertArtwork(1001L, "Artwork A", null, 0, 200L);
        insertArtwork(1002L, "Artwork B", null, 0, 100L);

        mapper.upsert(1001L, 0, "jpg", 42L, null, 123L);
        mapper.markNoHash(1002L, 130L);

        // 哨兵行（page=-1）不应出现在分组查询里
        assertThat(mapper.findAll()).singleElement()
                .satisfies(row -> assertThat(row.artworkId()).isEqualTo(1001L));
        // 两个作品都已「尝试过」，缺哈希计数为 0
        assertThat(mapper.countArtworksMissingHashes()).isZero();
        assertThat(mapper.artworkIdsMissingHashes(10)).isEmpty();
        // deleteByArtwork 应连同哨兵行一起清除
        mapper.deleteByArtwork(1002L);
        assertThat(mapper.countArtworksMissingHashes()).isEqualTo(1);
        assertThat(mapper.artworkIdsMissingHashes(10)).containsExactly(1002L);
    }

    @Test
    @DisplayName("缺哈希查询应按页判断并允许失败页哨兵跳过重试")
    void shouldDetectMissingPagesAndRespectPageNoHashMarker() {
        insertArtwork(1001L, "Artwork A", null, 0, 200L, 3);

        mapper.upsert(1001L, 0, "jpg", 42L, null, 123L);

        assertThat(mapper.countArtworksMissingHashes()).isEqualTo(1);
        assertThat(mapper.artworkIdsMissingHashes(10)).containsExactly(1001L);

        mapper.markPageNoHash(1001L, 1, 124L);
        assertThat(mapper.countArtworksMissingHashes()).isEqualTo(1);
        assertThat(mapper.artworkIdsMissingHashes(10)).containsExactly(1001L);

        mapper.markPageNoHash(1001L, 2, 125L);
        assertThat(mapper.countArtworksMissingHashes()).isZero();
        assertThat(mapper.artworkIdsMissingHashes(10)).isEmpty();
        assertThat(mapper.findAll()).singleElement()
                .satisfies(row -> assertThat(row.page()).isZero());
    }

    private void createTables() {
        jdbc.execute("""
                CREATE TABLE artwork_image_hashes (
                    artwork_id INTEGER NOT NULL,
                    page INTEGER NOT NULL,
                    ext TEXT NOT NULL,
                    dhash INTEGER NOT NULL,
                    ahash INTEGER,
                    created_time INTEGER NOT NULL,
                    PRIMARY KEY (artwork_id, page)
                )
                """);
        jdbc.execute("""
                CREATE TABLE artworks (
                    artwork_id INTEGER PRIMARY KEY,
                    title TEXT NOT NULL,
                    count INTEGER NOT NULL,
                    time INTEGER NOT NULL,
                    author_id INTEGER,
                    "R18" INTEGER,
                    deleted INTEGER NOT NULL DEFAULT 0
                )
                """);
        jdbc.execute("""
                CREATE TABLE authors (
                    author_id INTEGER PRIMARY KEY,
                    name TEXT NOT NULL,
                    updated_time INTEGER NOT NULL
                )
                """);
    }

    private void insertAuthor(long authorId, String name) {
        jdbc.update("INSERT INTO authors(author_id, name, updated_time) VALUES(?, ?, ?)",
                authorId, name, 1L);
    }

    private void insertArtwork(long artworkId, String title, Long authorId, int xRestrict, long time) {
        insertArtwork(artworkId, title, authorId, xRestrict, time, 1);
    }

    private void insertArtwork(long artworkId, String title, Long authorId, int xRestrict, long time, int count) {
        jdbc.update("INSERT INTO artworks(artwork_id, title, count, time, author_id, \"R18\") VALUES(?, ?, ?, ?, ?, ?)",
                artworkId, title, count, time, authorId, xRestrict);
    }
}
