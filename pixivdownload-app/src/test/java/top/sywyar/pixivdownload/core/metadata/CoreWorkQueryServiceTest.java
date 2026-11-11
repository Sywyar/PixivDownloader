package top.sywyar.pixivdownload.core.metadata;

import top.sywyar.pixivdownload.core.metadata.artwork.GalleryRepository;
import top.sywyar.pixivdownload.core.metadata.novel.NovelGalleryRepository;
import top.sywyar.pixivdownload.core.metadata.novel.NovelMetadataRepository;

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
import top.sywyar.pixivdownload.core.db.MainDatabase;
import top.sywyar.pixivdownload.core.db.InsertArtworkArgument;
import top.sywyar.pixivdownload.core.db.PixivMapper;
import top.sywyar.pixivdownload.core.db.TagDto;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.plugin.registry.schema.DatabaseSchemaRegistry;
import top.sywyar.pixivdownload.core.work.query.AuthorQuery;
import top.sywyar.pixivdownload.core.work.query.AuthorSummary;
import top.sywyar.pixivdownload.core.work.model.PagedResult;
import top.sywyar.pixivdownload.core.work.query.SeriesNeighbors;
import top.sywyar.pixivdownload.core.work.query.TagOption;
import top.sywyar.pixivdownload.core.work.query.TagQuery;
import top.sywyar.pixivdownload.core.work.query.WorkQuery;
import top.sywyar.pixivdownload.core.work.model.WorkRestriction;
import top.sywyar.pixivdownload.core.work.model.WorkSummary;
import top.sywyar.pixivdownload.core.work.model.WorkType;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("CoreWorkQueryService 集成测试")
class CoreWorkQueryServiceTest {

    private static final WorkRestriction FULLY_OPEN =
            new WorkRestriction(Set.of(0, 1, 2), true, List.of(), true, List.of());

    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbc;
    private PixivDatabase pixivDatabase;
    private NovelMetadataRepository novelMetadataRepository;
    private MainDatabase novelStorage;
    private AuthorService authorService;
    private CoreWorkQueryService service;

    @BeforeEach
    void setUp() {
        dataSource = new SingleConnectionDataSource();
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setUrl("jdbc:sqlite::memory:");
        dataSource.setSuppressClose(true);
        jdbc = new JdbcTemplate(dataSource);

        DatabaseSchemaRegistry registry = DatabaseSchemaRegistry.forBuiltInPlugins();
        DatabaseInitializer initializer = top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.initializer(
                jdbc, registry.contributions(), registry.mergedSchema(),
                TestI18nBeans.appMessages(), event -> {});
        initializer.initialize();

        DownloadConfig downloadConfig = new DownloadConfig();
        downloadConfig.setRootFolder(Path.of("pixiv-download").toAbsolutePath().normalize().toString());
        PathPrefixCodec codec = new PathPrefixCodec(
                new PathPrefixMapper(top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.main(dataSource)), downloadConfig, TestI18nBeans.appMessages());
        codec.init();

        pixivDatabase = new PixivDatabase(
                new PixivMapper(top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.main(dataSource)), TestI18nBeans.appMessages(), codec, initializer);
        pixivDatabase.init();
        novelStorage = spy(top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.main(dataSource));
        novelMetadataRepository = new NovelMetadataRepository(novelStorage, codec);

        authorService = mock(AuthorService.class);
        service = new CoreWorkQueryService(
                new GalleryRepository(top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.main(dataSource)),
                new NovelGalleryRepository(top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.main(dataSource)),
                pixivDatabase,
                novelMetadataRepository,
                authorService, () -> true);
    }

    @AfterEach
    void tearDown() {
        dataSource.destroy();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = WorkType.class, names = {"ARTWORK", "NOVEL"})
    @DisplayName("历史记录时间用于展示、日期筛选与分页，无需来源元数据或完成时间")
    void usesHistoricalRecordTimeThroughoutGallery(WorkType type) {
        long recordedAt = 1700000000000L;
        for (long id = 1; id <= 3; id++) {
            long time = recordedAt + id * 60000;
            if (type == WorkType.ARTWORK) insertArtwork(id, time, null);
            else insertNovel(id, time, null, null);
        }
        var metadata = new CoreWorkMetadataRepository(() -> true, pixivDatabase,
                novelMetadataRepository, authorService,
                mock(top.sywyar.pixivdownload.series.MangaSeriesService.class));
        var query = WorkQuery.builder(type).sort("date").order("desc").size(1)
                .downloadTimeRange(recordedAt + 60000, recordedAt + 180000).build();
        var first = service.search(query);
        assertThat(first.totalElements()).isEqualTo(2);
        assertThat(ids(first.content())).containsExactly(2L);
        assertThat(metadata.find(type, 2L).orElseThrow().downloadTime()).isEqualTo(recordedAt + 120000);
        var next = service.search(WorkQuery.builder(type).sort("date").order("desc").page(1).size(1)
                .downloadTimeRange(recordedAt + 60000, recordedAt + 180000).build());
        assertThat(ids(next.content())).containsExactly(1L);
        assertThat(metadata.find(type, 1L).orElseThrow().downloadTime()).isEqualTo(recordedAt + 60000);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(WorkType.class)
    @DisplayName("多日期范围取交集，分页计数与批量结果一致且排除缺失日期")
    void intersectsDateRanges(WorkType type) {
        String table = type == WorkType.ARTWORK ? "artworks" : "novels";
        String key = type == WorkType.ARTWORK ? "artwork_id" : "novel_id";
        for (long id = 1; id <= 5; id++) {
            if (type == WorkType.ARTWORK) insertArtwork(id, id * 100, null);
            else insertNovel(id, id * 100, null, null);
        }
        jdbc.update("UPDATE " + table + " SET source_upload_time=100, create_time=200, update_time=300, reupload_time=400");
        jdbc.update("UPDATE " + table + " SET create_time=201 WHERE " + key + "=1");
        jdbc.update("UPDATE " + table + " SET source_upload_time=101 WHERE " + key + "=3");
        jdbc.update("UPDATE " + table + " SET update_time=NULL WHERE " + key + "=4");
        var query = WorkQuery.builder(type).size(1).sort("date").order("asc")
                .uploadTimeRange(100L, 101L).downloadTimeRange(100L, 501L)
                .sourceTimeRanges(200L, 201L, 300L, 301L, 400L, 401L).build();
        var first = service.search(query);
        assertThat(first.totalElements()).isEqualTo(2);
        assertThat(ids(first.content())).containsExactly(2L);
        assertThat(ids(service.searchAll(query))).containsExactly(2L, 5L);
        assertThat(ids(service.searchAll(query.withDownloadTimeRange(500L, 501L)
                .withUploadTimeRange(100L, 101L).withSourceTimeField("uploadTime")))).containsExactly(5L);
        assertThat(ids(service.searchAll(query.withSourceTimeRanges(null, null, 300L, 301L, 400L, 401L))))
                .containsExactly(1L, 2L, 5L);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> query.withSourceTimeRanges(20L, 10L, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void insertArtwork(long id, long time, Long authorId) {
        pixivDatabase.insertArtwork(InsertArtworkArgument.builder()
                .artworkId(id)
                .title("作品" + id)
                .folder("/p/" + id)
                .count(1)
                .extensions("jpg")
                .time(time)
                .xRestrict(0)
                .authorId(authorId)
                .build());
    }

    private void insertNovel(long id, long time, Long authorId, Long seriesId) {
        jdbc.update("""
                        INSERT INTO novels(novel_id, title, folder, count, extensions, time, R18, is_ai,
                                           author_id, description, file_name, file_author_name_id,
                                           series_id, series_order, word_count, text_length,
                                           reading_time_seconds, page_count, is_original, x_language,
                                           cover_ext, deleted)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                        """,
                id, "小说" + id, "/n/" + id, 1, "", time, 0, null,
                authorId, null, 1L, null, seriesId, null, null, null,
                null, null, null, null, null);
    }

    private void saveNovelTags(long novelId, List<TagDto> tags) {
        for (TagDto tag : tags) {
            Long tagId = pixivDatabase.upsertTagAndGetId(tag.getName(), tag.getTranslatedName());
            jdbc.update("INSERT INTO novel_tags(novel_id, tag_id) VALUES (?, ?)", novelId, tagId);
        }
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
            saveNovelTags(11L, List.of(new TagDto("魔法", null)));
            saveNovelTags(12L, List.of(new TagDto("魔法", null), new TagDto("冒险", null)));

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
        @DisplayName("作者名精确读取不为作者池缺行添加数字 id 兜底")
        void shouldResolveExactAuthorNamesWithoutFallback() {
            when(authorService.getAuthorNames(anyCollection())).thenReturn(Map.of(801L, "作者甲"));

            assertThat(service.authorNames(List.of(801L, 802L)))
                    .containsExactly(Map.entry(801L, "作者甲"));
        }

        @Test
        @DisplayName("小说系列计数用单次核心聚合保留访客分级与标签作者限制")
        void shouldCountVisibleNovelSeriesInCoreAggregation() {
            insertNovel(11L, 100L, 801L, 700L);
            insertNovel(12L, 200L, 999L, 700L);
            insertNovel(13L, 300L, 801L, 701L);
            insertNovel(14L, 400L, 801L, 701L);
            insertNovel(15L, 500L, 801L, 702L);
            insertNovel(16L, 600L, 801L, 700L);
            saveNovelTags(11L, List.of(new TagDto("魔法", null)));
            saveNovelTags(12L, List.of(new TagDto("魔法", null)));
            saveNovelTags(14L, List.of(new TagDto("禁止", null)));
            saveNovelTags(15L, List.of(new TagDto("魔法", null)));
            saveNovelTags(16L, List.of(new TagDto("魔法", null)));
            jdbc.update("UPDATE novels SET R18 = 1 WHERE novel_id = 15");
            novelMetadataRepository.markNovelDeleted(16L);

            WorkRestriction restriction = new WorkRestriction(
                    Set.of(0), false, List.of(tagId("魔法")), false, List.of(801L));

            assertThat(service.countBySeries(WorkType.NOVEL, restriction))
                    .containsExactly(
                            Map.entry(700L, 1L),
                            Map.entry(701L, 1L));
            assertThat(service.countBySeries(WorkType.NOVEL, null))
                    .containsExactly(
                            Map.entry(700L, 2L),
                            Map.entry(701L, 2L),
                            Map.entry(702L, 1L));
        }

    }

    @Nested
    @DisplayName("小说列表与关联查询")
    class NovelQueryTests {

        @Test
        @DisplayName("小说列表一次读取候选元数据，不随作品数量逐篇查询")
        void shouldReadNovelCandidatesInOneStorageOperation() {
            for (long id = 1; id <= 32; id++) {
                insertNovel(id, id, null, null);
            }

            var result = service.searchAll(WorkQuery.builder(WorkType.NOVEL).build());

            assertThat(ids(result)).containsExactlyElementsOf(
                    java.util.stream.LongStream.iterate(32, id -> id - 1).limit(32).boxed().toList());
            verify(novelStorage, times(1)).read(any());
        }

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

        @org.junit.jupiter.params.ParameterizedTest
        @org.junit.jupiter.params.provider.NullSource
        @org.junit.jupiter.params.provider.ValueSource(strings = {"date"})
        @DisplayName("小说空排序与日期排序均支持空库，并按记录时间升降序查询")
        void shouldDefaultNullNovelSortToRecordTime(String sort) {
            var query = WorkQuery.builder(WorkType.NOVEL).sort(sort).build();
            assertThat(service.searchAll(query)).isEmpty();
            assertThat(service.search(query).content()).isEmpty();

            insertNovel(11L, 300L, null, null);
            insertNovel(12L, 100L, null, null);
            insertNovel(13L, 200L, null, null);

            assertThat(ids(service.searchAll(query))).containsExactly(11L, 13L, 12L);
            assertThat(ids(service.search(query).content())).containsExactly(11L, 13L, 12L);
            var ascending = WorkQuery.builder(WorkType.NOVEL).sort(sort).order("asc").build();
            assertThat(ids(service.searchAll(ascending))).containsExactly(12L, 13L, 11L);
            assertThat(ids(service.search(ascending).content())).containsExactly(12L, 13L, 11L);
        }

        @org.junit.jupiter.params.ParameterizedTest
        @org.junit.jupiter.params.provider.CsvSource({
                "uploadTime, source_upload_time",
                "createTime, create_time",
                "updateTime, update_time",
                "reuploadTime, reupload_time"
        })
        @DisplayName("小说显式时间排序按所选字段升降序，缺失值置后且同值按 ID 升序")
        void shouldKeepNovelSourceTimeSortSemantics(String sort, String column) {
            for (long id : List.of(13L, 12L, 14L, 11L)) {
                insertNovel(id, id * 100L, null, null);
            }
            jdbc.update("UPDATE novels SET " + column + " = 200 WHERE novel_id IN (11, 13)");
            jdbc.update("UPDATE novels SET " + column + " = 100 WHERE novel_id = 12");

            var ascending = WorkQuery.builder(WorkType.NOVEL).sort(sort).order("asc").build();
            var descending = WorkQuery.builder(WorkType.NOVEL).sort(sort).order("desc").build();
            assertThat(ids(service.searchAll(ascending))).containsExactly(12L, 11L, 13L, 14L);
            assertThat(ids(service.searchAll(descending))).containsExactly(11L, 13L, 12L, 14L);
            assertThat(ids(service.search(ascending).content())).containsExactly(12L, 11L, 13L, 14L);
            assertThat(ids(service.search(descending).content())).containsExactly(11L, 13L, 12L, 14L);
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
        @DisplayName("小说超大页码返回空页并保留计数")
        void shouldReturnEmptyNovelPageBeyondIntegerOffset() {
            insertNovel(11L, 100L, null, null);
            for (int page : List.of(1_073_741_824, Integer.MAX_VALUE)) {
                var result = service.search(WorkQuery.builder(WorkType.NOVEL).page(page).size(2).build());
                assertThat(result.content()).isEmpty();
                assertThat(result.totalElements()).isEqualTo(1);
                assertThat(result.page()).isEqualTo(page);
                assertThat(result.totalPages()).isEqualTo(1);
            }
        }

        @Test
        @DisplayName("小说 search 带访客限制：携带白名单外标签的小说被排除")
        void shouldApplyGuestRestrictionToNovels() {
            insertNovel(11L, 100L, null, null);
            insertNovel(12L, 200L, null, null);
            saveNovelTags(11L, List.of(new TagDto("魔法", null)));
            saveNovelTags(12L, List.of(new TagDto("魔法", null), new TagDto("禁止", null)));

            PagedResult<WorkSummary> result = service.search(WorkQuery.builder(WorkType.NOVEL)
                    .restriction(tagWhitelist(List.of(tagId("魔法"))))
                    .build());

            assertThat(ids(result.content())).containsExactly(11L);
            assertThat(result.totalElements()).isEqualTo(1);
        }

        @Test
        @DisplayName("小说 searchAll 返回命中条件的全部 id 不分页")
        void shouldReturnAllNovelIds() {
            insertNovel(11L, 100L, 88L, null);
            insertNovel(12L, 200L, 88L, null);
            insertNovel(13L, 300L, 99L, null);

            assertThat(ids(service.searchAll(WorkQuery.builder(WorkType.NOVEL)
                    .size(1).authorIds(List.of(88L)).build())))
                    .containsExactly(12L, 11L);
        }

        @Test
        @DisplayName("小说标签筛选保留全选、排除和任选的组合语义")
        void shouldCombineNovelTagFilters() {
            insertNovel(11L, 100L, null, null);
            insertNovel(12L, 200L, null, null);
            insertNovel(13L, 300L, null, null);
            saveNovelTags(11L, List.of(new TagDto("魔法", null), new TagDto("冒险", null)));
            saveNovelTags(12L, List.of(new TagDto("魔法", null)));
            long magic = tagId("魔法");
            long adventure = tagId("冒险");

            assertThat(ids(service.searchAll(WorkQuery.builder(WorkType.NOVEL)
                    .tagIds(List.of(magic, adventure)).build()))).containsExactly(11L);
            assertThat(ids(service.searchAll(WorkQuery.builder(WorkType.NOVEL)
                    .excludedTagIds(List.of(adventure)).build()))).containsExactly(13L, 12L);
            assertThat(ids(service.searchAll(WorkQuery.builder(WorkType.NOVEL)
                    .optionalTagIds(List.of(adventure)).build()))).containsExactly(11L);
            assertThat(ids(service.searchAll(WorkQuery.builder(WorkType.NOVEL)
                    .optionalTagIds(List.of(Long.MAX_VALUE)).build()))).isEmpty();
            assertThat(ids(service.searchAll(WorkQuery.builder(WorkType.NOVEL)
                    .tagIds(List.of(magic)).excludedTagIds(List.of(adventure))
                    .optionalTagIds(List.of(magic, adventure)).build()))).containsExactly(12L);
        }

        @Test
        @DisplayName("小说作者和系列排除保持空值身份及组合筛选")
        void shouldCombineNovelAuthorAndSeriesFilters() {
            insertNovel(11L, 100L, 88L, 700L);
            insertNovel(12L, 200L, 99L, 701L);
            insertNovel(13L, 300L, null, null);

            assertThat(ids(service.searchAll(WorkQuery.builder(WorkType.NOVEL)
                    .excludedAuthorIds(List.of(88L)).build()))).containsExactly(13L, 12L);
            assertThat(ids(service.searchAll(WorkQuery.builder(WorkType.NOVEL)
                    .authorIds(List.of(88L, 99L)).optionalAuthorIds(List.of(99L))
                    .excludedAuthorIds(List.of(88L)).build()))).containsExactly(12L);
            assertThat(ids(service.searchAll(WorkQuery.builder(WorkType.NOVEL)
                    .excludedSeriesIds(List.of(700L)).build()))).containsExactly(13L, 12L);
            assertThat(ids(service.searchAll(WorkQuery.builder(WorkType.NOVEL)
                    .seriesIds(List.of(700L, 701L)).excludedSeriesIds(List.of(700L)).build())))
                    .containsExactly(12L);
        }

        @Test
        @DisplayName("宿主查询对未知或来源私有搜索类型 fail-closed")
        void shouldNotInterpretPrivateSearchTypeAsMetadataSearch() {
            insertNovel(11L, 100L, null, null);

            assertThat(ids(service.searchAll(WorkQuery.builder(WorkType.NOVEL)
                    .searchType("private-field")
                    .search("小说11")
                    .build())))
                    .isEmpty();
        }

        @Test
        @DisplayName("小说 relatedByTags 按共享标签数降序返回相关小说")
        void shouldFindNovelRelatedByTags() {
            insertNovel(11L, 100L, null, null);
            insertNovel(12L, 200L, null, null);
            insertNovel(13L, 300L, null, null);
            saveNovelTags(11L, List.of(new TagDto("魔法", null), new TagDto("冒险", null)));
            saveNovelTags(12L, List.of(new TagDto("魔法", null), new TagDto("冒险", null)));
            saveNovelTags(13L, List.of(new TagDto("魔法", null)));

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
            jdbc.update("UPDATE novels SET series_id = ?, series_order = ? WHERE novel_id = ?", 900L, 3L, 11L);
            jdbc.update("UPDATE novels SET series_id = ?, series_order = ? WHERE novel_id = ?", 900L, 1L, 12L);
            jdbc.update("UPDATE novels SET series_id = ?, series_order = ? WHERE novel_id = ?", 900L, 2L, 13L);

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
            jdbc.update("UPDATE novels SET series_id = ?, series_order = ? WHERE novel_id = ?", 900L, 1L, 11L);
            jdbc.update("UPDATE novels SET series_id = ?, series_order = ? WHERE novel_id = ?", 900L, 5L, 12L);
            jdbc.update("UPDATE novels SET series_id = ?, series_order = ? WHERE novel_id = ?", 900L, 9L, 13L);
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
            saveNovelTags(11L, List.of(new TagDto("魔法", "magic")));

            Optional<TagOption> found = service.tagByName(WorkType.NOVEL, "魔法", null);
            assertThat(found).isPresent();
            assertThat(found.get().translatedName()).isEqualTo("magic");
            assertThat(found.get().workCount()).isEqualTo(1L);

            assertThat(service.tagByName(WorkType.NOVEL, "不存在", null)).isEmpty();
        }

        @Test
        @DisplayName("小说无限制目录（restriction 为 null）统计全部未删除行：tags / authors")
        void shouldListNovelCatalogsWithoutRestriction() {
            insertNovel(11L, 100L, 801L, 700L);
            insertNovel(12L, 200L, 801L, 700L);
            insertNovel(13L, 300L, 802L, 701L);
            insertNovel(14L, 400L, 802L, 701L);
            saveNovelTags(11L, List.of(new TagDto("魔法", null)));
            saveNovelTags(14L, List.of(new TagDto("魔法", null)));
            novelMetadataRepository.markNovelDeleted(14L);
            when(authorService.getAuthorNames(anyCollection())).thenReturn(Map.of(801L, "作者甲"));

            assertThat(service.tags(new TagQuery(WorkType.NOVEL, null, 100, null)))
                    .extracting(TagOption::name, TagOption::workCount)
                    .containsExactly(org.assertj.core.groups.Tuple.tuple("魔法", 1L));

            assertThat(service.authors(new AuthorQuery(WorkType.NOVEL, null)))
                    .containsExactlyInAnyOrder(
                            new AuthorSummary(801L, "作者甲", 2L),
                            new AuthorSummary(802L, "802", 1L));
        }
    }
}
