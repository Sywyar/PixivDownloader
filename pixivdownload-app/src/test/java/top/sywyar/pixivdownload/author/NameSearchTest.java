package top.sywyar.pixivdownload.author;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.plugin.registry.schema.DatabaseSchemaRegistry;
import top.sywyar.pixivdownload.series.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@DisplayName("作者与漫画系列的名称搜索和分页")
class NameSearchTest {
    @ParameterizedTest
    @DisplayName("分页保留名称匹配和可见性筛选，超大页码返回空页")
    @CsvSource({"BOOM!,1 4,0", "BOOM!%,1 4,0", "BOOM!_,4,0", "BOOM%,1 2 3 4,0",
            "BOOM_,1 2 3 4,0", "plain,5,0", "BOOM%,1 2 3 4,1073741824", "BOOM%,1 2 3 4,2147483647"})
    void searchesNamesThroughServicesAndRealSqlite(String search, String expected, int page) {
        var source = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        try {
            var registry = DatabaseSchemaRegistry.forBuiltInPlugins();
            var jdbc = new JdbcTemplate(source);
            DatabaseProviderTestFixtures.initializer(jdbc, registry.contributions(), registry.mergedSchema(),
                    TestI18nBeans.appMessages(), event -> {}).initialize();
            var database = DatabaseProviderTestFixtures.main(source);
            var authors = new AuthorMapper(database);
            var series = new MangaSeriesMapper(database);
            var names = List.of("BOOM!", "BOOM%", "BOOM_x", "BOOM!x", "plain");
            for (int i = 0; i < names.size(); i++) {
                long id = i + 1;
                authors.insertIfAbsent(id, names.get(i), 0);
                series.insertIfAbsent(id, names.get(i), id, 0);
                jdbc.update("INSERT INTO artworks(artwork_id,title,folder,count,extensions,time,author_id,series_id) VALUES(?,?,?,1,'jpg',?,?,?)",
                        id, "work", "/fixture", id, id, id);
            }
            var authorService = new AuthorService(authors, null, null, null, null, null);
            var seriesService = new MangaSeriesService(series, authorService, null, null, null, null, null, null, null, null);
            var ids = Arrays.stream(expected.split(" ")).map(Long::valueOf).toList();
            List<Long> pageIds = page == 0 ? ids : List.of();
            for (Set<Long> filter : Arrays.asList(null, Set.of(1L, 2L, 3L, 4L, 5L))) {
                var foundAuthors = authorService.getPagedAuthorsWithArtworks(page, 20, " " + search + " ", "authorId", filter);
                assertThat(foundAuthors.totalElements()).isEqualTo(ids.size());
                assertThat(foundAuthors.content()).extracting(AuthorSummary::authorId).containsExactlyElementsOf(pageIds);
                var foundSeries = seriesService.getPagedSeriesWithArtworks(page, 20, search, "seriesId", filter);
                assertThat(foundSeries.totalElements()).isEqualTo(ids.size());
                assertThat(foundSeries.content()).extracting(MangaSeriesSummary::seriesId).containsExactlyElementsOf(pageIds);
            }
        } finally { source.destroy(); }
    }
}
