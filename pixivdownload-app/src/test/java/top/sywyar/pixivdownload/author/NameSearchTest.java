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

@DisplayName("作者与漫画系列搜索的 LIKE 字面转义和通配符")
class NameSearchTest {
    @ParameterizedTest
    @DisplayName("感叹号按字面匹配，百分号和下划线继续作为通配符")
    @CsvSource({"BOOM!,1 4", "BOOM!%,1 4", "BOOM!_,4", "BOOM%,1 2 3 4", "BOOM_,1 2 3 4", "plain,5"})
    void searchesNamesThroughServicesAndRealSqlite(String search, String expected) {
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
            for (Set<Long> filter : Arrays.asList(null, Set.of(1L, 2L, 3L, 4L, 5L))) {
                var foundAuthors = authorService.getPagedAuthorsWithArtworks(0, 20, " " + search + " ", "authorId", filter);
                assertThat(foundAuthors.totalElements()).isEqualTo(ids.size());
                assertThat(foundAuthors.content()).extracting(AuthorSummary::authorId).containsExactlyElementsOf(ids);
                var foundSeries = seriesService.getPagedSeriesWithArtworks(0, 20, search, "seriesId", filter);
                assertThat(foundSeries.totalElements()).isEqualTo(ids.size());
                assertThat(foundSeries.content()).extracting(MangaSeriesSummary::seriesId).containsExactlyElementsOf(ids);
            }
        } finally { source.destroy(); }
    }
}
