package top.sywyar.pixivdownload.novelgallery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import top.sywyar.pixivdownload.plugin.api.work.query.AuthorQuery;
import top.sywyar.pixivdownload.plugin.api.work.query.AuthorSummary;
import top.sywyar.pixivdownload.novel.db.series.NovelSeriesCatalogRepository;
import top.sywyar.pixivdownload.novel.db.series.NovelSeriesCatalogRow;
import top.sywyar.pixivdownload.novel.metadata.NovelWorkDetails;
import top.sywyar.pixivdownload.novel.metadata.NovelWorkDetailsRepository;
import top.sywyar.pixivdownload.plugin.api.work.model.PagedResult;
import top.sywyar.pixivdownload.plugin.api.work.model.WorkRestriction;
import top.sywyar.pixivdownload.plugin.api.work.query.TagOption;
import top.sywyar.pixivdownload.plugin.api.work.query.TagQuery;
import top.sywyar.pixivdownload.plugin.api.work.service.WorkDeletionService;
import top.sywyar.pixivdownload.plugin.api.work.model.WorkMetadata;
import top.sywyar.pixivdownload.plugin.api.work.service.WorkMetadataRepository;
import top.sywyar.pixivdownload.plugin.api.work.query.WorkQuery;
import top.sywyar.pixivdownload.plugin.api.work.service.WorkQueryService;
import top.sywyar.pixivdownload.plugin.api.work.model.WorkSummary;
import top.sywyar.pixivdownload.plugin.api.work.model.WorkTag;
import top.sywyar.pixivdownload.plugin.api.work.model.WorkType;
import top.sywyar.pixivdownload.core.metadata.novel.NovelAuthorSummary;
import top.sywyar.pixivdownload.core.metadata.novel.NovelTagOption;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("NovelGalleryService 单元测试")
class NovelGalleryServiceTest {

    @Mock
    private WorkQueryService workQueryService;
    @Mock
    private NovelOwnedWorkSearch novelOwnedWorkSearch;
    @Mock
    private WorkMetadataRepository workMetadataRepository;
    @Mock
    private NovelWorkDetailsRepository novelWorkDetailsRepository;
    @Mock
    private NovelSeriesCatalogRepository novelSeriesCatalogRepository;
    @Mock
    private WorkDeletionService workDeletionService;

    private NovelGalleryService novelGalleryService;

    @BeforeEach
    void setUp() {
        novelGalleryService = new NovelGalleryService(
                workQueryService, novelOwnedWorkSearch, workMetadataRepository,
                novelWorkDetailsRepository, novelSeriesCatalogRepository, workDeletionService);
    }

    private static WorkMetadata meta(long id, Long authorId, Long seriesId) {
        return new WorkMetadata(WorkType.NOVEL, id, "小说" + id, null, 0, false,
                authorId, authorId == null ? null : "作者" + authorId,
                seriesId, null, null, List.of(new WorkTag(21L, "魔法", "magic")),
                100L, 1, "txt", "/n/novel-" + id,
                false, null, null, null, null, null, null, true);
    }

    private static NovelWorkDetails details(long id) {
        return new NovelWorkDetails(id, 1000, 2000, 300, 4, "ja", "jpg",
                List.of("img-a"), List.of("zh-CN"));
    }

    @Test
    @DisplayName("deleteNovel 委托核心统一删除入口并透传布尔结果")
    void shouldDelegateDeleteNovelToCore() {
        when(workDeletionService.delete(WorkType.NOVEL, 100L)).thenReturn(true);
        assertThat(novelGalleryService.deleteNovel(100L)).isTrue();
        verify(workDeletionService).delete(WorkType.NOVEL, 100L);

        when(workDeletionService.delete(WorkType.NOVEL, 404L)).thenReturn(false);
        assertThat(novelGalleryService.deleteNovel(404L)).isFalse();
        verify(workDeletionService).delete(WorkType.NOVEL, 404L);
    }

    @Test
    @DisplayName("deleteNovels 委托核心批量删除入口并透传删除计数")
    void shouldDelegateDeleteNovelsToCore() {
        when(workDeletionService.deleteAll(WorkType.NOVEL, List.of(1L, 2L, 1L))).thenReturn(2);
        assertThat(novelGalleryService.deleteNovels(List.of(1L, 2L, 1L))).isEqualTo(2);
        verify(workDeletionService).deleteAll(WorkType.NOVEL, List.of(1L, 2L, 1L));
    }

    @Nested
    @DisplayName("查询走核心接口（search → hydrate 两步）")
    class QueryDelegationTests {

        @Test
        @DisplayName("query 先 search 取 id 分页，再批量 hydrate，视图字段含小说专属块、分页字段透传")
        void shouldSearchThenHydrateInOrder() {
            NovelGalleryService.NovelGalleryQuery query = new NovelGalleryService.NovelGalleryQuery(
                    0, 2, "date", "desc", null, "any", "any");
            when(novelOwnedWorkSearch.search(any(), org.mockito.ArgumentMatchers.nullable(String.class)))
                    .thenReturn(new PagedResult<>(
                    List.of(new WorkSummary(WorkType.NOVEL, 2L), new WorkSummary(WorkType.NOVEL, 1L)),
                    5, 0, 2, 3));
            when(workMetadataRepository.findAll(WorkType.NOVEL, List.of(2L, 1L)))
                    .thenReturn(List.of(meta(2L, 88L, null), meta(1L, null, null)));
            when(novelWorkDetailsRepository.findAll(List.of(2L, 1L)))
                    .thenReturn(Map.of(2L, details(2L), 1L, details(1L)));

            NovelGalleryService.PagedNovels page = novelGalleryService.query(query);

            assertThat(page.content()).extracting(NovelGalleryService.NovelView::novelId)
                    .containsExactly(2L, 1L);
            assertThat(page.totalElements()).isEqualTo(5);
            assertThat(page.totalPages()).isEqualTo(3);
            NovelGalleryService.NovelView first = page.content().get(0);
            assertThat(first.authorName()).isEqualTo("作者88");
            assertThat(first.wordCount()).isEqualTo(1000);
            assertThat(first.coverExt()).isEqualTo("jpg");
            assertThat(first.embeddedImageIds()).containsExactly("img-a");
            assertThat(first.translatedLanguages()).containsExactly("zh-CN");
            assertThat(first.tags()).hasSize(1);
            assertThat(first.tags().get(0).getName()).isEqualTo("魔法");

            ArgumentCaptor<WorkQuery> captor = ArgumentCaptor.forClass(WorkQuery.class);
            verify(novelOwnedWorkSearch).search(captor.capture(), org.mockito.ArgumentMatchers.isNull());
            WorkQuery workQuery = captor.getValue();
            assertThat(workQuery.workType()).isEqualTo(WorkType.NOVEL);
            assertThat(workQuery.page()).isZero();
            assertThat(workQuery.size()).isEqualTo(2);
            assertThat(workQuery.restriction()).isNull();
        }

        @Test
        @DisplayName("NovelGalleryQuery 携带的访客限制应作为纯值原样传给核心查询")
        void shouldPassWorkRestrictionToCoreQuery() {
            WorkRestriction restriction = new WorkRestriction(
                    Set.of(0, 1), false, List.of(21L), true, List.of());
            NovelGalleryService.NovelGalleryQuery query = new NovelGalleryService.NovelGalleryQuery(
                    0, 24, "date", "desc", null, "all", "any", "any", null,
                    null, null, null, null, null, null, null, null, restriction);
            when(novelOwnedWorkSearch.search(any(), org.mockito.ArgumentMatchers.nullable(String.class))).thenReturn(
                    new PagedResult<>(List.of(), 0, 0, 24, 0));

            novelGalleryService.query(query);

            ArgumentCaptor<WorkQuery> captor = ArgumentCaptor.forClass(WorkQuery.class);
            verify(novelOwnedWorkSearch).search(captor.capture(), org.mockito.ArgumentMatchers.isNull());
            assertThat(captor.getValue().restriction()).isSameAs(restriction);
        }

        @Test
        @DisplayName("正文搜索词只传给小说私有适配层，宿主查询保持中性")
        void shouldKeepPrivateContentQueryOutOfHostWorkQuery() {
            NovelGalleryService.NovelGalleryQuery query = new NovelGalleryService.NovelGalleryQuery(
                    0, 24, "date", "desc", "冒险旅程", "content", "any", "any",
                    null, null, null, null, null, null, null, null, null, null);
            when(novelOwnedWorkSearch.search(any(), org.mockito.ArgumentMatchers.eq("冒险旅程")))
                    .thenReturn(new PagedResult<>(List.of(), 0, 0, 24, 0));

            novelGalleryService.query(query);

            ArgumentCaptor<WorkQuery> captor = ArgumentCaptor.forClass(WorkQuery.class);
            verify(novelOwnedWorkSearch).search(captor.capture(), org.mockito.ArgumentMatchers.eq("冒险旅程"));
            assertThat(captor.getValue().search()).isNull();
            assertThat(captor.getValue().searchType()).isEqualTo("all");
        }

        @Test
        @DisplayName("find 经元数据仓库取行；无记录（含软删）返回 null")
        void shouldFindViaMetadataRepository() {
            when(workMetadataRepository.find(WorkType.NOVEL, 1L))
                    .thenReturn(Optional.of(meta(1L, 88L, 700L)));
            when(workMetadataRepository.find(WorkType.NOVEL, 404L)).thenReturn(Optional.empty());
            when(novelWorkDetailsRepository.find(1L)).thenReturn(Optional.of(details(1L)));
            when(workMetadataRepository.find(WorkType.NOVEL, 2L))
                    .thenReturn(Optional.of(meta(2L, null, null)));
            when(novelWorkDetailsRepository.find(2L)).thenReturn(Optional.empty());

            NovelGalleryService.NovelView found = novelGalleryService.find(1L);
            assertThat(found).isNotNull();
            assertThat(found.novelId()).isEqualTo(1L);
            assertThat(found.title()).isEqualTo("小说1");
            assertThat(found.seriesId()).isEqualTo(700L);

            assertThat(novelGalleryService.find(404L)).isNull();
            assertThat(novelGalleryService.find(2L)).isNull();
            verify(novelWorkDetailsRepository, never()).find(404L);
        }

        @Test
        @DisplayName("bySeries 走核心接口（excludeWorkId=0 不排除任何章节）；非法入参返回空且不查询")
        void shouldQueryBySeriesWithoutExclusion() {
            when(workQueryService.bySeries(WorkType.NOVEL, 700L, 0L, 30))
                    .thenReturn(List.of(new WorkSummary(WorkType.NOVEL, 1L)));
            when(workMetadataRepository.findAll(WorkType.NOVEL, List.of(1L)))
                    .thenReturn(List.of(meta(1L, null, 700L)));
            when(novelWorkDetailsRepository.findAll(List.of(1L)))
                    .thenReturn(Map.of(1L, details(1L)));

            assertThat(novelGalleryService.bySeries(700L, 30))
                    .extracting(NovelGalleryService.NovelView::novelId).containsExactly(1L);

            assertThat(novelGalleryService.bySeries(0L, 30)).isEmpty();
            assertThat(novelGalleryService.bySeries(700L, 0)).isEmpty();
            verify(workQueryService, times(1)).bySeries(any(), anyLong(), anyLong(), org.mockito.ArgumentMatchers.anyInt());
        }

        @Test
        @DisplayName("seriesNeighbors 透传核心接口结果，empty 映射为 null")
        void shouldReturnNullWhenNoSeriesNeighbors() {
            when(workQueryService.seriesNeighbors(WorkType.NOVEL, 1L)).thenReturn(Optional.empty());

            assertThat(novelGalleryService.seriesNeighbors(1L)).isNull();
        }

        @Test
        @DisplayName("listTags 转换核心标签目录行为 NovelTagOption，无访客会话时 restriction 为 null")
        void shouldConvertTagOptions() {
            when(workQueryService.tags(any())).thenReturn(List.of(
                    new TagOption(5L, "魔法", "magic", 3L)));

            List<NovelTagOption> tags = novelGalleryService.listTags("魔", 100);
            assertThat(tags).containsExactly(new NovelTagOption(5L, "魔法", "magic", 3L));

            ArgumentCaptor<TagQuery> captor = ArgumentCaptor.forClass(TagQuery.class);
            verify(workQueryService).tags(captor.capture());
            assertThat(captor.getValue().workType()).isEqualTo(WorkType.NOVEL);
            assertThat(captor.getValue().search()).isEqualTo("魔");
            assertThat(captor.getValue().limit()).isEqualTo(100);
            assertThat(captor.getValue().restriction()).isNull();
        }

        @Test
        @DisplayName("作者目录走核心接口后在内存按 search/sort 过滤分页，缺名以 id 字符串兜底语义由核心承担")
        void shouldPageAuthorsFromCoreCatalog() {
            when(workQueryService.authors(new AuthorQuery(WorkType.NOVEL, null))).thenReturn(List.of(
                    new AuthorSummary(801L, "作者甲", 2L),
                    new AuthorSummary(802L, "802", 5L)));

            NovelGalleryService.PagedAuthors byCount =
                    novelGalleryService.getPagedAuthorsWithNovels(0, 24, null, "novels");
            assertThat(byCount.content()).extracting(NovelAuthorSummary::authorId)
                    .containsExactly(802L, 801L);
            assertThat(byCount.totalElements()).isEqualTo(2);

            NovelGalleryService.PagedAuthors searched =
                    novelGalleryService.getPagedAuthorsWithNovels(0, 24, "甲", "name");
            assertThat(searched.content()).extracting(NovelAuthorSummary::authorId)
                    .containsExactly(801L);
        }

        @Test
        @DisplayName("系列目录由小说插件读取并保留封面与标签装饰列")
        void shouldPageOwnedSeriesCatalogWithDecorations() {
            when(novelSeriesCatalogRepository.findAll()).thenReturn(List.of(
                    new NovelSeriesCatalogRow(700L, "系列甲", 801L, 2L, "png"),
                    new NovelSeriesCatalogRow(701L, "701", 802L, 1L, null)));
            when(workQueryService.authorNames(Set.of(801L, 802L))).thenReturn(Map.of(801L, "作者甲"));
            when(novelSeriesCatalogRepository.findTags(any())).thenReturn(Map.of(
                    700L, List.of(new WorkTag(21L, "魔法", "magic"))));

            NovelGalleryService.PagedSeries page =
                    novelGalleryService.getPagedSeriesWithNovels(0, 24, null, "title");

            assertThat(page.content()).hasSize(2);
            assertThat(page.content().get(0).seriesId()).isEqualTo(701L);
            assertThat(page.content().get(0).authorName()).isNull();
            assertThat(page.content().get(1).seriesId()).isEqualTo(700L);
            assertThat(page.content().get(1).authorName()).isEqualTo("作者甲");
            assertThat(page.content().get(1).coverExt()).isEqualTo("png");
            assertThat(page.content().get(1).tags()).hasSize(1);
            assertThat(page.content().get(1).tags().get(0).name()).isEqualTo("魔法");
        }

        @Test
        @DisplayName("受限系列目录用核心聚合计数，不全量扫描作品或批量 hydrate")
        void shouldCountRestrictedSeriesInCoreAggregation() {
            WorkRestriction restriction = new WorkRestriction(
                    Set.of(0), false, List.of(21L), true, List.of());
            when(novelSeriesCatalogRepository.findAll()).thenReturn(List.of(
                    new NovelSeriesCatalogRow(700L, "系列甲", 801L, 3L, null),
                    new NovelSeriesCatalogRow(701L, "系列乙", 802L, 2L, null)));
            when(workQueryService.countBySeries(WorkType.NOVEL, restriction))
                    .thenReturn(Map.of(700L, 2L));
            when(workQueryService.authorNames(Set.of(801L))).thenReturn(Map.of(801L, "作者甲"));
            when(novelSeriesCatalogRepository.findTags(any())).thenReturn(Map.of());

            NovelGalleryService.PagedSeries page = novelGalleryService
                    .getPagedSeriesWithNovels(0, 24, null, "title", restriction);

            assertThat(page.content()).singleElement().satisfies(series -> {
                assertThat(series.seriesId()).isEqualTo(700L);
                assertThat(series.novelCount()).isEqualTo(2L);
            });
            verify(workQueryService).countBySeries(WorkType.NOVEL, restriction);
            verify(workQueryService, never()).searchAll(any());
            verifyNoInteractions(workMetadataRepository);
        }

        @Test
        @DisplayName("访客系列过滤直接使用核心聚合键集合，不读取系列目录或作品行")
        void shouldResolveVisibleSeriesIdsWithoutHydratingWorks() {
            WorkRestriction restriction = new WorkRestriction(
                    Set.of(0), true, List.of(), false, List.of(801L));
            when(workQueryService.countBySeries(WorkType.NOVEL, restriction))
                    .thenReturn(Map.of(700L, 3L, 701L, 1L));

            assertThat(novelGalleryService.visibleSeriesIds(restriction))
                    .containsExactlyInAnyOrder(700L, 701L);

            verify(workQueryService).countBySeries(WorkType.NOVEL, restriction);
            verifyNoInteractions(novelSeriesCatalogRepository, workMetadataRepository);
        }
    }

    @Test
    @DisplayName("系列响应保持既有字段名与标签 wire 结构")
    void shouldPreserveSeriesWireShape() {
        NovelSeriesSummary summary = new NovelSeriesSummary(
                700L, "系列甲", 801L, null, 2L, "png",
                List.of(new WorkTag(21L, "魔法", "magic")));

        JsonNode json = new ObjectMapper().valueToTree(summary);

        assertThat(json.size()).isEqualTo(7);
        assertThat(json.has("seriesId")).isTrue();
        assertThat(json.has("title")).isTrue();
        assertThat(json.has("authorId")).isTrue();
        assertThat(json.has("authorName")).isTrue();
        assertThat(json.has("novelCount")).isTrue();
        assertThat(json.has("coverExt")).isTrue();
        assertThat(json.has("tags")).isTrue();
        assertThat(json.path("tags").get(0).size()).isEqualTo(3);
        assertThat(json.path("tags").get(0).path("tagId").asLong()).isEqualTo(21L);
        assertThat(json.path("tags").get(0).path("name").asText()).isEqualTo("魔法");
        assertThat(json.path("tags").get(0).path("translatedName").asText()).isEqualTo("magic");
    }
}
