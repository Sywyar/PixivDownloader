package top.sywyar.pixivdownload.stats;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("StatsRepository 聚合查询")
class StatsRepositoryTest {

    private SingleConnectionDataSource dataSource;
    private StatsRepository repository;

    @BeforeEach
    void setUp() {
        dataSource = new SingleConnectionDataSource();
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setUrl("jdbc:sqlite::memory:");
        dataSource.setSuppressClose(true);

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE statistics (id INTEGER PRIMARY KEY, total_artworks INTEGER, total_images INTEGER, total_moved INTEGER)");
        jdbc.execute("INSERT INTO statistics(id, total_artworks, total_images, total_moved) VALUES (1, 5, 20, 2)");
        jdbc.execute("CREATE TABLE authors (author_id INTEGER PRIMARY KEY, name TEXT, updated_time INTEGER)");
        jdbc.execute("INSERT INTO authors(author_id, name, updated_time) VALUES (100, 'Alice', 0), (200, 'Bob', 0)");
        jdbc.execute("CREATE TABLE artworks (artwork_id INTEGER PRIMARY KEY, author_id INTEGER, series_id INTEGER, time INTEGER, count INTEGER)");
        // Alice 有 3 件，Bob 1 件，1 件无作者
        long ts = 1714521600000L; // 2024-05 (UTC)
        jdbc.update("INSERT INTO artworks(artwork_id, author_id, series_id, time, count) VALUES (?,?,?,?,?)", 1, 100, 9, ts, 2);
        jdbc.update("INSERT INTO artworks(artwork_id, author_id, series_id, time, count) VALUES (?,?,?,?,?)", 2, 100, 9, ts + 1000, 1);
        jdbc.update("INSERT INTO artworks(artwork_id, author_id, series_id, time, count) VALUES (?,?,?,?,?)", 3, 100, null, ts + 2000, 3);
        jdbc.update("INSERT INTO artworks(artwork_id, author_id, series_id, time, count) VALUES (?,?,?,?,?)", 4, 200, null, ts + 3000, 1);
        jdbc.update("INSERT INTO artworks(artwork_id, author_id, series_id, time, count) VALUES (?,?,?,?,?)", 5, null, null, ts + 4000, 1);
        jdbc.execute("CREATE TABLE tags (tag_id INTEGER PRIMARY KEY, name TEXT, translated_name TEXT)");
        jdbc.execute("INSERT INTO tags(tag_id, name, translated_name) VALUES (1, 'tagA', '甲'), (2, 'tagB', NULL)");
        jdbc.execute("CREATE TABLE artwork_tags (artwork_id INTEGER, tag_id INTEGER)");
        jdbc.execute("INSERT INTO artwork_tags(artwork_id, tag_id) VALUES (1,1),(2,1),(3,1),(4,2)");
        jdbc.execute("CREATE TABLE novels (novel_id INTEGER PRIMARY KEY, raw_content TEXT)");
        jdbc.execute("INSERT INTO novels(novel_id, raw_content) VALUES (1000, 'x'), (1001, 'y')");

        repository = new StatsRepository(dataSource);
    }

    @AfterEach
    void tearDown() {
        dataSource.destroy();
    }

    @Test
    @DisplayName("overview 合并 statistics 单行与实时计数")
    void shouldBuildOverview() {
        StatsDto.Overview o = repository.overview();
        assertThat(o.totalArtworks()).isEqualTo(5);
        assertThat(o.totalImages()).isEqualTo(20);
        assertThat(o.totalMoved()).isEqualTo(2);
        assertThat(o.totalNovels()).isEqualTo(2);
        assertThat(o.totalAuthors()).isEqualTo(2);
        assertThat(o.totalTags()).isEqualTo(2);
        assertThat(o.totalSeries()).isEqualTo(1);
    }

    @Test
    @DisplayName("topAuthors 按作品数降序")
    void shouldRankAuthors() {
        List<StatsDto.AuthorStat> authors = repository.topAuthors(10);
        assertThat(authors).hasSize(2);
        assertThat(authors.get(0).authorId()).isEqualTo(100);
        assertThat(authors.get(0).count()).isEqualTo(3);
        assertThat(authors.get(1).authorId()).isEqualTo(200);
        assertThat(authors.get(1).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("topTags 按使用量降序")
    void shouldRankTags() {
        List<StatsDto.TagStat> tags = repository.topTags(10);
        assertThat(tags).hasSize(2);
        assertThat(tags.get(0).tagId()).isEqualTo(1);
        assertThat(tags.get(0).count()).isEqualTo(3);
    }

    @Test
    @DisplayName("monthly 各月计数之和等于作品总数")
    void shouldGroupByMonth() {
        List<StatsDto.MonthlyStat> monthly = repository.monthlyArtworkCounts();
        assertThat(monthly).isNotEmpty();
        long sum = monthly.stream().mapToLong(StatsDto.MonthlyStat::count).sum();
        assertThat(sum).isEqualTo(5);
        // 月份格式 YYYY-MM 且升序
        assertThat(monthly).allSatisfy(m -> assertThat(m.month()).matches("\\d{4}-\\d{2}"));
    }
}
