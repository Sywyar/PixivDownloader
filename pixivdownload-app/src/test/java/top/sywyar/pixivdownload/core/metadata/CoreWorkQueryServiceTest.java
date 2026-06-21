package top.sywyar.pixivdownload.core.metadata;

import top.sywyar.pixivdownload.core.metadata.artwork.GalleryRepository;
import top.sywyar.pixivdownload.core.metadata.novel.NovelGalleryRepository;
import top.sywyar.pixivdownload.core.metadata.novel.NovelMetadataRepository;

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
import top.sywyar.pixivdownload.author.AuthorService;
import top.sywyar.pixivdownload.core.appconfig.DownloadConfig;
import top.sywyar.pixivdownload.core.db.schema.DatabaseInitializer;
import top.sywyar.pixivdownload.core.db.pathprefix.PathPrefixCodec;
import top.sywyar.pixivdownload.core.db.pathprefix.PathPrefixMapper;
import top.sywyar.pixivdownload.core.db.PixivDatabase;
import top.sywyar.pixivdownload.core.db.PixivMapper;
import top.sywyar.pixivdownload.core.db.TagDto;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.novel.db.NovelDatabase;
import top.sywyar.pixivdownload.novel.db.NovelMapper;
import top.sywyar.pixivdownload.plugin.DatabaseSchemaRegistry;
import top.sywyar.pixivdownload.plugin.api.work.query.AuthorQuery;
import top.sywyar.pixivdownload.plugin.api.work.query.AuthorSummary;
import top.sywyar.pixivdownload.plugin.api.work.model.PagedResult;
import top.sywyar.pixivdownload.plugin.api.work.query.SeriesNeighbors;
import top.sywyar.pixivdownload.plugin.api.work.query.SeriesQuery;
import top.sywyar.pixivdownload.plugin.api.work.query.SeriesSummary;
import top.sywyar.pixivdownload.plugin.api.work.query.TagOption;
import top.sywyar.pixivdownload.plugin.api.work.query.TagQuery;
import top.sywyar.pixivdownload.plugin.api.work.query.WorkQuery;
import top.sywyar.pixivdownload.plugin.api.work.model.WorkRestriction;
import top.sywyar.pixivdownload.plugin.api.work.model.WorkSummary;
import top.sywyar.pixivdownload.plugin.api.work.model.WorkTag;
import top.sywyar.pixivdownload.plugin.api.work.model.WorkType;
import top.sywyar.pixivdownload.series.MangaSeries;
import top.sywyar.pixivdownload.series.MangaSeriesService;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("CoreWorkQueryService 集成测试")
class CoreWorkQueryServiceTest {

    private static final WorkRestriction FULLY_OPEN =
            new WorkRestriction(Set.of(0, 1, 2), true, List.of(), true, List.of());

    private SingleConnectionDataSource dataSource;
    private SqlSession sqlSession;
    private JdbcTemplate jdbc;
    private PixivDatabase pixivDatabase;
    private NovelDatabase novelDatabase;
    private NovelMetadataRepository novelMetadataRepository;
    private AuthorService authorService;
    private MangaSeriesService mangaSeriesService;
    private CoreWorkQueryService service;

    @BeforeEach
    void setUp() {
        dataSource = new SingleConnectionDataSource();
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setUrl("jdbc:sqlite::memory:");
        dataSource.setSuppressClose(true);
        jdbc = new JdbcTemplate(dataSource);

        Environment env = new Environment("test", new JdbcTransactionFactory(), dataSource);
        Configuration config = new Configuration(env);
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(PixivMapper.class);
        config.addMapper(PathPrefixMapper.class);
        config.addMapper(NovelMapper.class);
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(config);
        sqlSession = factory.openSession(true);

        DatabaseSchemaRegistry registry = DatabaseSchemaRegistry.forBuiltInPlugins();
        DatabaseInitializer initializer = new DatabaseInitializer(
                jdbc, registry.contributions(), registry.mergedSchema(),
                TestI18nBeans.appMessages(), event -> {});
        initializer.initialize();

        DownloadConfig downloadConfig = new DownloadConfig();
        downloadConfig.setRootFolder(Path.of("pixiv-download").toAbsolutePath().normalize().toString());
        PathPrefixCodec codec = new PathPrefixCodec(
                sqlSession.getMapper(PathPrefixMapper.class), downloadConfig, TestI18nBeans.appMessages());
        codec.init();

        pixivDatabase = new PixivDatabase(
                sqlSession.getMapper(PixivMapper.class), TestI18nBeans.appMessages(), codec, initializer);
        pixivDatabase.init();
        novelMetadataRepository = new NovelMetadataRepository(dataSource, codec);
        novelDatabase = new NovelDatabase(
                sqlSession.getMapper(NovelMapper.class), pixivDatabase, codec, initializer, novelMetadataRepository);
        novelDatabase.init();

        authorService = mock(AuthorService.class);
        mangaSeriesService = mock(MangaSeriesService.class);
        service = new CoreWorkQueryService(
                new GalleryRepository(dataSource),
                new NovelGalleryRepository(dataSource),
                pixivDatabase,
                novelMetadataRepository,
                authorService,
                mangaSeriesService);
    }

    @AfterEach
    void tearDown() {
        sqlSession.close();
        dataSource.destroy();
    }

    private void insertArtwork(long id, long time, Long authorId) {
        pixivDatabase.insertArtwork(id, "作品" + id, "/p/" + id, 1, "jpg", time, 0, null, authorId, null);
    }

    private void insertNovel(long id, long time, Long authorId, Long seriesId) {
        novelDatabase.insertNovel(id, "小说" + id, "/n/" + id, 1, "", time, 0, null, authorId, null,
                1L, null, seriesId, null, null, null, null, null, null, null, "正文" + id, null);
    }

    private long tagId(String name) {
        return jdbc.queryForObject("SELECT tag_id FROM tags WHERE name = ?", Long.class, name);
    }

    private static WorkRestriction tagWhitelist(List<Long> tagIds) {
        return new WorkRestriction(Set.of(0, 1, 2), false, tagIds, true, List.of());
    }

    private static List<Long> ids(List<WorkSummary> summaries) {
        return summaries.stream().map(WorkSummary::workId).toList();
    }

    @Nested
    @DisplayName("search 与三态判重")
    class SearchTests {

        @Test
        @DisplayName("search 默认过滤软删除行，时间倒序，分页字段完整")
        void shouldFilterSoftDeletedAndPage() {
            insertArtwork(1L, 100L, null);
            insertArtwork(2L, 200L, null);
            insertArtwork(3L, 300L, null);
            pixivDatabase.markArtworkDeleted(2L);

            PagedResult<WorkSummary> result = service.search(
                    WorkQuery.builder(WorkType.ARTWORK).build());

            assertThat(ids(result.content())).containsExactly(3L, 1L);
            assertThat(result.content()).allMatch(s -> s.workType() == WorkType.ARTWORK);
            assertThat(result.totalElements()).isEqualTo(2);
            assertThat(result.page()).isZero();
            assertThat(result.size()).isEqualTo(24);
            assertThat(result.totalPages()).isEqualTo(1);
        }

        @Test
        @DisplayName("search 分页数学在查询侧完成：第二页取次新作品")
        void shouldComputePagingMath() {
            insertArtwork(1L, 100L, null);
            insertArtwork(2L, 200L, null);
            insertArtwork(3L, 300L, null);

            PagedResult<WorkSummary> result = service.search(
                    WorkQuery.builder(WorkType.ARTWORK).page(1).size(1).build());

            assertThat(ids(result.content())).containsExactly(2L);
            assertThat(result.totalElements()).isEqualTo(3);
            assertThat(result.totalPages()).isEqualTo(3);
        }

        @Test
        @DisplayName("search 带访客限制：携带白名单外标签的作品被排除")
        void shouldApplyGuestRestriction() {
            insertArtwork(1L, 100L, null);
            insertArtwork(2L, 200L, null);
            pixivDatabase.saveArtworkTags(1L, List.of(new TagDto("魔法", null)));
            pixivDatabase.saveArtworkTags(2L, List.of(new TagDto("魔法", null), new TagDto("禁止", null)));

            PagedResult<WorkSummary> result = service.search(WorkQuery.builder(WorkType.ARTWORK)
                    .restriction(tagWhitelist(List.of(tagId("魔法"))))
                    .build());

            assertThat(ids(result.content())).containsExactly(1L);
            assertThat(result.totalElements()).isEqualTo(1);
        }

        @Test
        @DisplayName("searchAll 返回命中条件的全部作品 id，不分页")
        void shouldReturnAllIds() {
            insertArtwork(1L, 100L, 88L);
            insertArtwork(2L, 200L, 88L);
            insertArtwork(3L, 300L, 99L);

            List<WorkSummary> result = service.searchAll(WorkQuery.builder(WorkType.ARTWORK)
                    .size(1)
                    .authorIds(List.of(88L))
                    .build());

            assertThat(ids(result)).containsExactly(2L, 1L);
        }

        @Test
        @DisplayName("插画三态判重：active / 软删 / 不存在")
        void shouldExposeArtworkTriState() {
            insertArtwork(1L, 100L, null);
            insertArtwork(2L, 200L, null);
            pixivDatabase.markArtworkDeleted(2L);

            assertThat(service.hasWork(WorkType.ARTWORK, 1L)).isTrue();
            assertThat(service.hasActiveWork(WorkType.ARTWORK, 1L)).isTrue();
            assertThat(service.hasWork(WorkType.ARTWORK, 2L)).isTrue();
            assertThat(service.hasActiveWork(WorkType.ARTWORK, 2L)).isFalse();
            assertThat(service.hasWork(WorkType.ARTWORK, 404L)).isFalse();
            assertThat(service.hasActiveWork(WorkType.ARTWORK, 404L)).isFalse();
        }

        @Test
        @DisplayName("小说三态判重：active / 软删 / 不存在")
        void shouldExposeNovelTriState() {
            insertNovel(11L, 100L, null, null);
            insertNovel(12L, 200L, null, null);
            novelMetadataRepository.markNovelDeleted(12L);

            assertThat(service.hasWork(WorkType.NOVEL, 11L)).isTrue();
            assertThat(service.hasActiveWork(WorkType.NOVEL, 11L)).isTrue();
            assertThat(service.hasWork(WorkType.NOVEL, 12L)).isTrue();
            assertThat(service.hasActiveWork(WorkType.NOVEL, 12L)).isFalse();
            assertThat(service.hasWork(WorkType.NOVEL, 404L)).isFalse();
            assertThat(service.hasActiveWork(WorkType.NOVEL, 404L)).isFalse();
        }
    }

    @Nested
    @DisplayName("关联查询（插画）")
    class RelationTests {

        @Test
        @DisplayName("relatedByTags 按共享标签数降序返回相关作品")
        void shouldFindRelatedByTags() {
            insertArtwork(1L, 100L, null);
            insertArtwork(2L, 200L, null);
            insertArtwork(3L, 300L, null);
            pixivDatabase.saveArtworkTags(1L, List.of(new TagDto("魔法", null), new TagDto("冒险", null)));
            pixivDatabase.saveArtworkTags(2L, List.of(new TagDto("魔法", null), new TagDto("冒险", null)));
            pixivDatabase.saveArtworkTags(3L, List.of(new TagDto("魔法", null)));

            assertThat(ids(service.relatedByTags(WorkType.ARTWORK, 1L, 10)))
                    .containsExactly(2L, 3L);
        }

        @Test
        @DisplayName("byAuthor 返回同作者其他作品并排除自身")
        void shouldFindByAuthorExcludingSelf() {
            insertArtwork(1L, 100L, 88L);
            insertArtwork(2L, 200L, 88L);
            insertArtwork(3L, 300L, 99L);

            assertThat(ids(service.byAuthor(WorkType.ARTWORK, 88L, 2L, 10)))
                    .containsExactly(1L);
        }

        @Test
        @DisplayName("bySeries 按系列序号升序返回并排除自身")
        void shouldFindBySeriesExcludingSelf() {
            insertArtwork(1L, 100L, null);
            insertArtwork(2L, 200L, null);
            insertArtwork(3L, 300L, null);
            pixivDatabase.updateSeriesInfo(1L, 900L, 3L);
            pixivDatabase.updateSeriesInfo(2L, 900L, 1L);
            pixivDatabase.updateSeriesInfo(3L, 900L, 2L);

            assertThat(ids(service.bySeries(WorkType.ARTWORK, 900L, 3L, 10)))
                    .containsExactly(2L, 1L);
        }

        @Test
        @DisplayName("seriesNeighbors 返回最近的上一章与下一章；无系列信息时为 empty")
        void shouldFindSeriesNeighbors() {
            insertArtwork(1L, 100L, null);
            insertArtwork(2L, 200L, null);
            insertArtwork(3L, 300L, null);
            insertArtwork(4L, 400L, null);
            pixivDatabase.updateSeriesInfo(1L, 900L, 1L);
            pixivDatabase.updateSeriesInfo(2L, 900L, 5L);
            pixivDatabase.updateSeriesInfo(3L, 900L, 9L);

            Optional<SeriesNeighbors> neighbors = service.seriesNeighbors(WorkType.ARTWORK, 2L);

            assertThat(neighbors).isPresent();
            assertThat(neighbors.get().seriesId()).isEqualTo(900L);
            assertThat(neighbors.get().currentOrder()).isEqualTo(5L);
            assertThat(neighbors.get().prev().workId()).isEqualTo(1L);
            assertThat(neighbors.get().next().workId()).isEqualTo(3L);
            assertThat(service.seriesNeighbors(WorkType.ARTWORK, 4L)).isEmpty();
        }
    }

    @Nested
    @DisplayName("标签 / 作者 / 系列目录")
    class CatalogTests {

        @Test
        @DisplayName("插画标签目录带使用计数；访客限制下过滤不可见标签")
        void shouldListArtworkTagsWithRestriction() {
            insertArtwork(1L, 100L, null);
            insertArtwork(2L, 200L, null);
            pixivDatabase.saveArtworkTags(1L, List.of(new TagDto("魔法", null)));
            pixivDatabase.saveArtworkTags(2L, List.of(new TagDto("魔法", null), new TagDto("禁止", null)));

            List<TagOption> all = service.tags(new TagQuery(WorkType.ARTWORK, null, 0, null));
            assertThat(all).extracting(TagOption::name, TagOption::workCount)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple("魔法", 2L),
                            org.assertj.core.groups.Tuple.tuple("禁止", 1L));

            List<TagOption> restricted = service.tags(new TagQuery(
                    WorkType.ARTWORK, null, 0, tagWhitelist(List.of(tagId("魔法")))));
            assertThat(restricted).extracting(TagOption::name).containsExactly("魔法");
        }

        @Test
        @DisplayName("tagByName 精确命中返回标签，未命中返回 empty")
        void shouldFindTagByExactName() {
            insertArtwork(1L, 100L, null);
            pixivDatabase.saveArtworkTags(1L, List.of(new TagDto("魔法", "magic")));

            Optional<TagOption> found = service.tagByName(WorkType.ARTWORK, "魔法", null);
            assertThat(found).isPresent();
            assertThat(found.get().translatedName()).isEqualTo("magic");
            assertThat(found.get().workCount()).isEqualTo(1L);

            assertThat(service.tagByName(WorkType.ARTWORK, "不存在", null)).isEmpty();
        }

        @Test
        @DisplayName("小说标签目录按访客投影计数，透传搜索词")
        void shouldListNovelTagCounts() {
            insertNovel(11L, 100L, null, null);
            insertNovel(12L, 200L, null, null);
            novelDatabase.saveNovelTags(11L, List.of(new TagDto("魔法", null)));
            novelDatabase.saveNovelTags(12L, List.of(new TagDto("魔法", null), new TagDto("冒险", null)));

            List<TagOption> all = service.tags(new TagQuery(WorkType.NOVEL, null, 100, FULLY_OPEN));
            assertThat(all).extracting(TagOption::name, TagOption::workCount)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple("魔法", 2L),
                            org.assertj.core.groups.Tuple.tuple("冒险", 1L));

            List<TagOption> searched = service.tags(new TagQuery(WorkType.NOVEL, "冒", 100, FULLY_OPEN));
            assertThat(searched).extracting(TagOption::name).containsExactly("冒险");
        }

        @Test
        @DisplayName("插画作者目录无限制时统计全部未删除作品，补全作者名、缺名以 id 字符串兜底")
        void shouldListArtworkAuthorCountsWithNames() {
            insertArtwork(1L, 100L, 801L);
            insertArtwork(2L, 200L, 801L);
            insertArtwork(3L, 300L, 802L);
            insertArtwork(4L, 400L, 802L);
            pixivDatabase.markArtworkDeleted(4L);
            when(authorService.getAuthorNames(anyCollection())).thenReturn(Map.of(801L, "作者甲"));

            List<AuthorSummary> authors = service.authors(new AuthorQuery(WorkType.ARTWORK, null));

            assertThat(authors).containsExactlyInAnyOrder(
                    new AuthorSummary(801L, "作者甲", 2L),
                    new AuthorSummary(802L, "802", 1L));
        }

        @Test
        @DisplayName("插画作者目录带访客限制：仅统计对该访客可见的作品")
        void shouldListArtworkAuthorCountsWithRestriction() {
            insertArtwork(1L, 100L, 801L);
            insertArtwork(2L, 200L, 801L);
            insertArtwork(3L, 300L, 802L);
            pixivDatabase.saveArtworkTags(1L, List.of(new TagDto("魔法", null)));
            pixivDatabase.saveArtworkTags(2L, List.of(new TagDto("魔法", null), new TagDto("禁止", null)));
            pixivDatabase.saveArtworkTags(3L, List.of(new TagDto("魔法", null)));
            when(authorService.getAuthorNames(anyCollection())).thenReturn(Map.of());

            List<AuthorSummary> authors = service.authors(new AuthorQuery(
                    WorkType.ARTWORK, tagWhitelist(List.of(tagId("魔法")))));

            assertThat(authors).containsExactlyInAnyOrder(
                    new AuthorSummary(801L, "801", 1L),
                    new AuthorSummary(802L, "802", 1L));
        }

        @Test
        @DisplayName("插画系列目录补全系列标题与作者，缺行以 id 字符串兜底")
        void shouldListArtworkSeriesCountsWithTitles() {
            insertArtwork(1L, 100L, 801L);
            insertArtwork(2L, 200L, 801L);
            insertArtwork(3L, 300L, 802L);
            pixivDatabase.updateSeriesInfo(1L, 700L, 1L);
            pixivDatabase.updateSeriesInfo(2L, 700L, 2L);
            pixivDatabase.updateSeriesInfo(3L, 701L, 1L);
            when(mangaSeriesService.getSeriesByIds(anyCollection())).thenReturn(List.of(
                    new MangaSeries(700L, "系列甲", 801L, 1L, null, null, null)));
            when(authorService.getAuthorNames(anyCollection())).thenReturn(Map.of(801L, "作者甲"));

            List<SeriesSummary> series = service.series(new SeriesQuery(WorkType.ARTWORK, null));

            assertThat(series).containsExactlyInAnyOrder(
                    new SeriesSummary(700L, "系列甲", 801L, "作者甲", 2L),
                    new SeriesSummary(701L, "701", null, null, 1L));
        }

        @Test
        @DisplayName("插画系列目录带访客限制：仅统计对该访客可见的作品")
        void shouldListArtworkSeriesCountsWithRestriction() {
            insertArtwork(1L, 100L, null);
            insertArtwork(2L, 200L, null);
            pixivDatabase.updateSeriesInfo(1L, 700L, 1L);
            pixivDatabase.updateSeriesInfo(2L, 700L, 2L);
            pixivDatabase.saveArtworkTags(1L, List.of(new TagDto("魔法", null)));
            pixivDatabase.saveArtworkTags(2L, List.of(new TagDto("魔法", null), new TagDto("禁止", null)));
            when(mangaSeriesService.getSeriesByIds(anyCollection())).thenReturn(List.of());
            when(authorService.getAuthorNames(anyCollection())).thenReturn(Map.of());

            List<SeriesSummary> series = service.series(new SeriesQuery(
                    WorkType.ARTWORK, tagWhitelist(List.of(tagId("魔法")))));

            assertThat(series).containsExactly(new SeriesSummary(700L, "700", null, null, 1L));
        }

        @Test
        @DisplayName("小说作者目录补全作者名，缺名以 id 字符串兜底")
        void shouldListNovelAuthorCountsWithNames() {
            insertNovel(11L, 100L, 801L, null);
            insertNovel(12L, 200L, 801L, null);
            insertNovel(13L, 300L, 802L, null);
            when(authorService.getAuthorNames(anyCollection())).thenReturn(Map.of(801L, "作者甲"));

            List<AuthorSummary> authors = service.authors(new AuthorQuery(WorkType.NOVEL, FULLY_OPEN));

            assertThat(authors).containsExactlyInAnyOrder(
                    new AuthorSummary(801L, "作者甲", 2L),
                    new AuthorSummary(802L, "802", 1L));
        }

        @Test
        @DisplayName("小说系列目录补全系列标题与作者，缺行以 id 字符串兜底")
        void shouldListNovelSeriesCountsWithTitles() {
            insertNovel(11L, 100L, 801L, 700L);
            insertNovel(12L, 200L, 801L, 700L);
            insertNovel(13L, 300L, 802L, 701L);
            jdbc.update("INSERT INTO novel_series(series_id, title, author_id, updated_time) VALUES (?, ?, ?, ?)",
                    700L, "系列甲", 801L, 1L);
            when(authorService.getAuthorNames(anyCollection())).thenReturn(Map.of(801L, "作者甲"));

            List<SeriesSummary> series = service.series(new SeriesQuery(WorkType.NOVEL, FULLY_OPEN));

            assertThat(series).containsExactlyInAnyOrder(
                    new SeriesSummary(700L, "系列甲", 801L, "作者甲", 2L),
                    new SeriesSummary(701L, "701", null, null, 1L));
        }
    }

    @Nested
    @DisplayName("小说列表与关联查询")
    class NovelQueryTests {

        @Test
        @DisplayName("小说 search 默认过滤软删除行，时间倒序，分页字段完整")
        void shouldFilterSoftDeletedAndPageNovels() {
            insertNovel(11L, 100L, null, null);
            insertNovel(12L, 200L, null, null);
            insertNovel(13L, 300L, null, null);
            novelMetadataRepository.markNovelDeleted(12L);

            PagedResult<WorkSummary> result = service.search(
                    WorkQuery.builder(WorkType.NOVEL).build());

            assertThat(ids(result.content())).containsExactly(13L, 11L);
            assertThat(result.content()).allMatch(s -> s.workType() == WorkType.NOVEL);
            assertThat(result.totalElements()).isEqualTo(2);
            assertThat(result.page()).isZero();
            assertThat(result.size()).isEqualTo(24);
            assertThat(result.totalPages()).isEqualTo(1);
        }

        @Test
        @DisplayName("小说 search 分页数学在查询侧完成：第二页取次新小说")
        void shouldComputeNovelPagingMath() {
            insertNovel(11L, 100L, null, null);
            insertNovel(12L, 200L, null, null);
            insertNovel(13L, 300L, null, null);

            PagedResult<WorkSummary> result = service.search(
                    WorkQuery.builder(WorkType.NOVEL).page(1).size(1).build());

            assertThat(ids(result.content())).containsExactly(12L);
            assertThat(result.totalElements()).isEqualTo(3);
            assertThat(result.totalPages()).isEqualTo(3);
        }

        @Test
        @DisplayName("小说 search 带访客限制：携带白名单外标签的小说被排除")
        void shouldApplyGuestRestrictionToNovels() {
            insertNovel(11L, 100L, null, null);
            insertNovel(12L, 200L, null, null);
            novelDatabase.saveNovelTags(11L, List.of(new TagDto("魔法", null)));
            novelDatabase.saveNovelTags(12L, List.of(new TagDto("魔法", null), new TagDto("禁止", null)));

            PagedResult<WorkSummary> result = service.search(WorkQuery.builder(WorkType.NOVEL)
                    .restriction(tagWhitelist(List.of(tagId("魔法"))))
                    .build());

            assertThat(ids(result.content())).containsExactly(11L);
            assertThat(result.totalElements()).isEqualTo(1);
        }

        @Test
        @DisplayName("小说 searchAll 返回命中条件的全部 id 不分页；正文检索经 FTS 命中")
        void shouldReturnAllNovelIdsAndSearchContent() {
            insertNovel(11L, 100L, 88L, null);
            insertNovel(12L, 200L, 88L, null);
            insertNovel(13L, 300L, 99L, null);

            assertThat(ids(service.searchAll(WorkQuery.builder(WorkType.NOVEL)
                    .size(1).authorIds(List.of(88L)).build())))
                    .containsExactly(12L, 11L);

            assertThat(ids(service.searchAll(WorkQuery.builder(WorkType.NOVEL)
                    .searchType("content").search("正文12").build())))
                    .containsExactly(12L);
        }

        @Test
        @DisplayName("小说 relatedByTags 按共享标签数降序返回相关小说")
        void shouldFindNovelRelatedByTags() {
            insertNovel(11L, 100L, null, null);
            insertNovel(12L, 200L, null, null);
            insertNovel(13L, 300L, null, null);
            novelDatabase.saveNovelTags(11L, List.of(new TagDto("魔法", null), new TagDto("冒险", null)));
            novelDatabase.saveNovelTags(12L, List.of(new TagDto("魔法", null), new TagDto("冒险", null)));
            novelDatabase.saveNovelTags(13L, List.of(new TagDto("魔法", null)));

            assertThat(ids(service.relatedByTags(WorkType.NOVEL, 11L, 10)))
                    .containsExactly(12L, 13L);
        }

        @Test
        @DisplayName("小说 byAuthor 返回同作者其他小说并排除自身")
        void shouldFindNovelsByAuthorExcludingSelf() {
            insertNovel(11L, 100L, 88L, null);
            insertNovel(12L, 200L, 88L, null);
            insertNovel(13L, 300L, 99L, null);

            assertThat(ids(service.byAuthor(WorkType.NOVEL, 88L, 12L, 10)))
                    .containsExactly(11L);
        }

        @Test
        @DisplayName("小说 bySeries 按系列序号升序返回并排除指定小说")
        void shouldFindNovelsBySeriesExcludingSelf() {
            insertNovel(11L, 100L, null, 900L);
            insertNovel(12L, 200L, null, 900L);
            insertNovel(13L, 300L, null, 900L);
            novelDatabase.updateSeriesInfo(11L, 900L, 3L);
            novelDatabase.updateSeriesInfo(12L, 900L, 1L);
            novelDatabase.updateSeriesInfo(13L, 900L, 2L);

            assertThat(ids(service.bySeries(WorkType.NOVEL, 900L, 13L, 10)))
                    .containsExactly(12L, 11L);
        }

        @Test
        @DisplayName("小说 seriesNeighbors 返回最近的上一章与下一章；无系列或无序号时为 empty")
        void shouldFindNovelSeriesNeighbors() {
            insertNovel(11L, 100L, null, 900L);
            insertNovel(12L, 200L, null, 900L);
            insertNovel(13L, 300L, null, 900L);
            insertNovel(14L, 400L, null, null);
            insertNovel(15L, 500L, null, 901L);
            novelDatabase.updateSeriesInfo(11L, 900L, 1L);
            novelDatabase.updateSeriesInfo(12L, 900L, 5L);
            novelDatabase.updateSeriesInfo(13L, 900L, 9L);
            jdbc.update("INSERT INTO novel_series(series_id, title, author_id, updated_time) VALUES (?, ?, ?, ?)",
                    900L, "系列乙", null, 1L);

            Optional<SeriesNeighbors> neighbors = service.seriesNeighbors(WorkType.NOVEL, 12L);

            assertThat(neighbors).isPresent();
            assertThat(neighbors.get().seriesId()).isEqualTo(900L);
            assertThat(neighbors.get().seriesTitle()).isEqualTo("系列乙");
            assertThat(neighbors.get().currentOrder()).isEqualTo(5L);
            assertThat(neighbors.get().prev().workId()).isEqualTo(11L);
            assertThat(neighbors.get().next().workId()).isEqualTo(13L);
            assertThat(service.seriesNeighbors(WorkType.NOVEL, 14L)).isEmpty();
            assertThat(service.seriesNeighbors(WorkType.NOVEL, 15L)).isEmpty();
        }

        @Test
        @DisplayName("小说 tagByName 精确命中返回标签，未命中返回 empty")
        void shouldFindNovelTagByExactName() {
            insertNovel(11L, 100L, null, null);
            novelDatabase.saveNovelTags(11L, List.of(new TagDto("魔法", "magic")));

            Optional<TagOption> found = service.tagByName(WorkType.NOVEL, "魔法", null);
            assertThat(found).isPresent();
            assertThat(found.get().translatedName()).isEqualTo("magic");
            assertThat(found.get().workCount()).isEqualTo(1L);

            assertThat(service.tagByName(WorkType.NOVEL, "不存在", null)).isEmpty();
        }

        @Test
        @DisplayName("小说无限制目录（restriction 为 null）统计全部未删除行：tags / authors / series")
        void shouldListNovelCatalogsWithoutRestriction() {
            insertNovel(11L, 100L, 801L, 700L);
            insertNovel(12L, 200L, 801L, 700L);
            insertNovel(13L, 300L, 802L, 701L);
            insertNovel(14L, 400L, 802L, 701L);
            novelDatabase.saveNovelTags(11L, List.of(new TagDto("魔法", null)));
            novelDatabase.saveNovelTags(14L, List.of(new TagDto("魔法", null)));
            novelMetadataRepository.markNovelDeleted(14L);
            when(authorService.getAuthorNames(anyCollection())).thenReturn(Map.of(801L, "作者甲"));

            assertThat(service.tags(new TagQuery(WorkType.NOVEL, null, 100, null)))
                    .extracting(TagOption::name, TagOption::workCount)
                    .containsExactly(org.assertj.core.groups.Tuple.tuple("魔法", 1L));

            assertThat(service.authors(new AuthorQuery(WorkType.NOVEL, null)))
                    .containsExactlyInAnyOrder(
                            new AuthorSummary(801L, "作者甲", 2L),
                            new AuthorSummary(802L, "802", 1L));

            assertThat(service.series(new SeriesQuery(WorkType.NOVEL, null)))
                    .containsExactlyInAnyOrder(
                            new SeriesSummary(700L, "700", null, null, 2L),
                            new SeriesSummary(701L, "701", null, null, 1L));
        }

        @Test
        @DisplayName("小说系列目录批量补全封面扩展名与系列标签（装饰列）")
        void shouldDecorateNovelSeriesCatalog() {
            insertNovel(11L, 100L, 801L, 700L);
            jdbc.update("INSERT INTO novel_series(series_id, title, author_id, updated_time, cover_ext)"
                    + " VALUES (?, ?, ?, ?, ?)", 700L, "系列甲", 801L, 1L, "png");
            novelDatabase.saveNovelSeriesTags(700L, List.of(new TagDto("魔法", "magic")));
            when(authorService.getAuthorNames(anyCollection())).thenReturn(Map.of(801L, "作者甲"));

            List<SeriesSummary> series = service.series(new SeriesQuery(WorkType.NOVEL, FULLY_OPEN));

            assertThat(series).hasSize(1);
            assertThat(series.get(0).title()).isEqualTo("系列甲");
            assertThat(series.get(0).authorName()).isEqualTo("作者甲");
            assertThat(series.get(0).coverExt()).isEqualTo("png");
            assertThat(series.get(0).tags()).extracting(WorkTag::name).containsExactly("魔法");
        }
    }
}
