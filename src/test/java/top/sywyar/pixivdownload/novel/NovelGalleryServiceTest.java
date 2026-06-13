package top.sywyar.pixivdownload.novel;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import top.sywyar.pixivdownload.i18n.LocalizedException;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.plugin.api.work.query.AuthorQuery;
import top.sywyar.pixivdownload.plugin.api.work.query.AuthorSummary;
import top.sywyar.pixivdownload.plugin.api.work.model.NovelWorkDetails;
import top.sywyar.pixivdownload.plugin.api.work.model.PagedResult;
import top.sywyar.pixivdownload.plugin.api.work.query.SeriesQuery;
import top.sywyar.pixivdownload.plugin.api.work.query.SeriesSummary;
import top.sywyar.pixivdownload.plugin.api.work.query.TagOption;
import top.sywyar.pixivdownload.plugin.api.work.query.TagQuery;
import top.sywyar.pixivdownload.plugin.api.work.service.WorkAssetService;
import top.sywyar.pixivdownload.plugin.api.work.service.WorkDeletionService;
import top.sywyar.pixivdownload.plugin.api.work.model.WorkMetadata;
import top.sywyar.pixivdownload.plugin.api.work.service.WorkMetadataRepository;
import top.sywyar.pixivdownload.plugin.api.work.query.WorkQuery;
import top.sywyar.pixivdownload.plugin.api.work.service.WorkQueryService;
import top.sywyar.pixivdownload.plugin.api.work.model.WorkSummary;
import top.sywyar.pixivdownload.plugin.api.work.model.WorkTag;
import top.sywyar.pixivdownload.plugin.api.work.model.WorkType;
import top.sywyar.pixivdownload.core.metadata.NovelAuthorSummary;
import top.sywyar.pixivdownload.core.metadata.NovelTagOption;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("NovelGalleryService 单元测试")
class NovelGalleryServiceTest {

    @Mock
    private WorkQueryService workQueryService;
    @Mock
    private WorkMetadataRepository workMetadataRepository;
    @Mock
    private WorkAssetService workAssetService;
    @Mock
    private WorkDeletionService workDeletionService;

    private NovelGalleryService novelGalleryService;

    @BeforeEach
    void setUp() {
        novelGalleryService = new NovelGalleryService(workQueryService, workMetadataRepository,
                workAssetService, workDeletionService, TestI18nBeans.appMessages());
    }

    private static WorkMetadata meta(long id, Long authorId, Long seriesId) {
        return new WorkMetadata(WorkType.NOVEL, id, "小说" + id, null, 0, false,
                authorId, authorId == null ? null : "作者" + authorId,
                seriesId, null, null, List.of(new WorkTag(21L, "魔法", "magic")),
                100L, 1, "txt", "/n/novel-" + id,
                false, null, null, null, null, null,
                new NovelWorkDetails(1000, 2000, 300, 4, true, "ja", "jpg",
                        List.of("img-a"), List.of("zh-CN")));
    }

    @Test
    @DisplayName("小说不存在时返回 false，且不删除文件或标记数据库记录")
    void shouldNotDeleteWhenNovelMissing() {
        when(workQueryService.hasActiveWork(WorkType.NOVEL, 1L)).thenReturn(false);

        assertThat(novelGalleryService.deleteNovel(1L)).isFalse();

        verify(workAssetService, never()).deleteLocalFiles(any(), anyLong());
        verify(workDeletionService, never()).markDeleted(any(), anyLong());
    }

    @Test
    @DisplayName("已被标记删除的小说返回 false，不再触碰磁盘或重复标记")
    void shouldNotDeleteWhenAlreadyMarkedDeleted() {
        when(workQueryService.hasActiveWork(WorkType.NOVEL, 1L)).thenReturn(false);

        assertThat(novelGalleryService.deleteNovel(1L)).isFalse();

        verify(workAssetService, never()).deleteLocalFiles(any(), anyLong());
        verify(workDeletionService, never()).markDeleted(any(), anyLong());
    }

    @Test
    @DisplayName("删除小说应先删磁盘文件，再标记数据库软删除（WorkDeletionService.markDeleted，主行保留）")
    void shouldDeleteFilesThenDatabase() {
        when(workQueryService.hasActiveWork(WorkType.NOVEL, 100L)).thenReturn(true);
        when(workAssetService.deleteLocalFiles(WorkType.NOVEL, 100L)).thenReturn(true);

        assertThat(novelGalleryService.deleteNovel(100L)).isTrue();

        InOrder inOrder = inOrder(workAssetService, workDeletionService);
        inOrder.verify(workAssetService).deleteLocalFiles(WorkType.NOVEL, 100L);
        inOrder.verify(workDeletionService).markDeleted(WorkType.NOVEL, 100L);
    }

    @Test
    @DisplayName("磁盘文件删除失败时不标记数据库记录，并抛出异常以阻止状态不一致")
    void shouldAbortWhenFileDeletionFails() {
        when(workQueryService.hasActiveWork(WorkType.NOVEL, 999L)).thenReturn(true);
        when(workAssetService.deleteLocalFiles(WorkType.NOVEL, 999L)).thenReturn(false);

        assertThatThrownBy(() -> novelGalleryService.deleteNovel(999L))
                .isInstanceOf(LocalizedException.class);

        verify(workDeletionService, never()).markDeleted(any(), anyLong());
    }

    @Test
    @DisplayName("批量删除应去重并返回实际删除数量")
    void shouldBatchDeleteDistinctAndCount() {
        when(workQueryService.hasActiveWork(WorkType.NOVEL, 1L)).thenReturn(true);
        when(workQueryService.hasActiveWork(WorkType.NOVEL, 2L)).thenReturn(false);
        when(workAssetService.deleteLocalFiles(WorkType.NOVEL, 1L)).thenReturn(true);

        int deleted = novelGalleryService.deleteNovels(List.of(1L, 2L, 1L));

        assertThat(deleted).isEqualTo(1);
        verify(workDeletionService, times(1)).markDeleted(WorkType.NOVEL, 1L);
        verify(workDeletionService, never()).markDeleted(WorkType.NOVEL, 2L);
    }

    @Test
    @DisplayName("批量删除入参为空时返回 0")
    void shouldReturnZeroForEmptyBatch() {
        assertThat(novelGalleryService.deleteNovels(null)).isZero();
        assertThat(novelGalleryService.deleteNovels(List.of())).isZero();
        verify(workDeletionService, never()).markDeleted(any(), anyLong());
    }

    @Nested
    @DisplayName("查询走核心接口（search → hydrate 两步）")
    class QueryDelegationTests {

        @Test
        @DisplayName("query 先 search 取 id 分页，再批量 hydrate，视图字段含小说专属块、分页字段透传")
        void shouldSearchThenHydrateInOrder() {
            NovelGalleryService.NovelGalleryQuery query = new NovelGalleryService.NovelGalleryQuery(
                    0, 2, "date", "desc", null, "any", "any");
            when(workQueryService.search(any())).thenReturn(new PagedResult<>(
                    List.of(new WorkSummary(WorkType.NOVEL, 2L), new WorkSummary(WorkType.NOVEL, 1L)),
                    5, 0, 2, 3));
            when(workMetadataRepository.findAll(WorkType.NOVEL, List.of(2L, 1L)))
                    .thenReturn(List.of(meta(2L, 88L, null), meta(1L, null, null)));

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
            verify(workQueryService).search(captor.capture());
            WorkQuery workQuery = captor.getValue();
            assertThat(workQuery.workType()).isEqualTo(WorkType.NOVEL);
            assertThat(workQuery.page()).isZero();
            assertThat(workQuery.size()).isEqualTo(2);
            assertThat(workQuery.restriction()).isNull();
        }

        @Test
        @DisplayName("find 经元数据仓库取行；无记录（含软删）返回 null")
        void shouldFindViaMetadataRepository() {
            when(workMetadataRepository.find(WorkType.NOVEL, 1L))
                    .thenReturn(Optional.of(meta(1L, 88L, 700L)));
            when(workMetadataRepository.find(WorkType.NOVEL, 404L)).thenReturn(Optional.empty());

            NovelGalleryService.NovelView found = novelGalleryService.find(1L);
            assertThat(found).isNotNull();
            assertThat(found.novelId()).isEqualTo(1L);
            assertThat(found.title()).isEqualTo("小说1");
            assertThat(found.seriesId()).isEqualTo(700L);

            assertThat(novelGalleryService.find(404L)).isNull();
        }

        @Test
        @DisplayName("bySeries 走核心接口（excludeWorkId=0 不排除任何章节）；非法入参返回空且不查询")
        void shouldQueryBySeriesWithoutExclusion() {
            when(workQueryService.bySeries(WorkType.NOVEL, 700L, 0L, 30))
                    .thenReturn(List.of(new WorkSummary(WorkType.NOVEL, 1L)));
            when(workMetadataRepository.findAll(WorkType.NOVEL, List.of(1L)))
                    .thenReturn(List.of(meta(1L, null, 700L)));

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
        @DisplayName("系列目录走核心接口后保留装饰列（封面扩展名 / 系列标签），并在内存过滤分页")
        void shouldPageSeriesWithDecorations() {
            when(workQueryService.series(new SeriesQuery(WorkType.NOVEL, null))).thenReturn(List.of(
                    new SeriesSummary(700L, "系列甲", 801L, "作者甲", 2L, "png",
                            List.of(new WorkTag(21L, "魔法", "magic"))),
                    new SeriesSummary(701L, "701", null, null, 1L)));

            NovelGalleryService.PagedSeries page =
                    novelGalleryService.getPagedSeriesWithNovels(0, 24, null, "title");

            assertThat(page.content()).hasSize(2);
            assertThat(page.content().get(0).seriesId()).isEqualTo(701L);
            assertThat(page.content().get(1).seriesId()).isEqualTo(700L);
            assertThat(page.content().get(1).coverExt()).isEqualTo("png");
            assertThat(page.content().get(1).tags()).hasSize(1);
            assertThat(page.content().get(1).tags().get(0).getName()).isEqualTo("魔法");
        }
    }
}
