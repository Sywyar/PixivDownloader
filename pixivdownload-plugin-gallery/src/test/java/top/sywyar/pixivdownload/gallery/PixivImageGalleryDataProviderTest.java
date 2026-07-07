package top.sywyar.pixivdownload.gallery;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import top.sywyar.pixivdownload.core.gallery.facet.GalleryFacetPage;
import top.sywyar.pixivdownload.core.gallery.facet.GalleryFacetType;
import top.sywyar.pixivdownload.core.gallery.model.GalleryFieldCapability;
import top.sywyar.pixivdownload.core.gallery.model.GalleryFieldStrategy;
import top.sywyar.pixivdownload.core.gallery.model.GalleryKind;
import top.sywyar.pixivdownload.core.gallery.model.GalleryPage;
import top.sywyar.pixivdownload.core.gallery.model.GalleryQuery;
import top.sywyar.pixivdownload.plugin.api.work.model.PagedResult;
import top.sywyar.pixivdownload.plugin.api.work.model.WorkMetadata;
import top.sywyar.pixivdownload.plugin.api.work.model.WorkSummary;
import top.sywyar.pixivdownload.plugin.api.work.model.WorkTag;
import top.sywyar.pixivdownload.plugin.api.work.model.WorkType;
import top.sywyar.pixivdownload.plugin.api.work.query.AuthorQuery;
import top.sywyar.pixivdownload.plugin.api.work.query.AuthorSummary;
import top.sywyar.pixivdownload.plugin.api.work.query.TagOption;
import top.sywyar.pixivdownload.plugin.api.work.query.TagQuery;
import top.sywyar.pixivdownload.plugin.api.work.query.WorkQuery;
import top.sywyar.pixivdownload.plugin.api.work.service.WorkMetadataRepository;
import top.sywyar.pixivdownload.plugin.api.work.service.WorkQueryService;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Pixiv IMAGE 画廊数据提供方")
class PixivImageGalleryDataProviderTest {

    @Mock
    private WorkQueryService workQueryService;
    @Mock
    private WorkMetadataRepository workMetadataRepository;

    private PixivImageGalleryDataProvider provider;

    @BeforeEach
    void setUp() {
        provider = new PixivImageGalleryDataProvider(workQueryService, workMetadataRepository);
    }

    @Test
    @DisplayName("声明 pixiv 来源的 IMAGE provider 与真实字段能力")
    void declaresPixivImageSourceAndSupportedFields() {
        assertThat(provider.providerId()).isEqualTo("pixiv-image");

        assertThat(provider.sources())
                .singleElement()
                .satisfies(source -> {
                    assertThat(source.providerId()).isEqualTo("pixiv-image");
                    assertThat(source.sourceId()).isEqualTo("pixiv");
                    assertThat(source.kinds()).containsExactly(GalleryKind.IMAGE);
                    assertThat(source.displayNamespace()).isEqualTo("gallery");
                    assertThat(source.displayI18nKey()).isEqualTo("source.pixiv");
                    assertThat(source.fieldStrategies())
                            .extracting(GalleryFieldStrategy::field, GalleryFieldStrategy::capability)
                            .containsExactly(
                                    tuple("r18", GalleryFieldCapability.SUPPORTED),
                                    tuple("ai", GalleryFieldCapability.SUPPORTED));
                });
    }

    @Test
    @DisplayName("IMAGE 查询返回真实作品映射后的 GalleryItem")
    void queryMapsArtworkMetadataToGalleryItem() {
        when(workQueryService.search(any())).thenReturn(new PagedResult<>(
                summaries(123L), 1, 0, 2, 1));
        when(workMetadataRepository.findAll(WorkType.ARTWORK, List.of(123L)))
                .thenReturn(List.of(meta(123L, 88L, "作者88", 700L, 2, null,
                        List.of(new WorkTag(5L, "猫", "cat")))));

        GalleryPage page = provider.query(new GalleryQuery(
                GalleryKind.IMAGE, "pixiv", List.of(), Map.of("sort", "date"), 0, 2));

        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items()).singleElement().satisfies(item -> {
            assertThat(item.ref().providerId()).isEqualTo("pixiv-image");
            assertThat(item.ref().sourceId()).isEqualTo("pixiv");
            assertThat(item.ref().kind()).isEqualTo(GalleryKind.IMAGE);
            assertThat(item.ref().workId()).isEqualTo("123");
            assertThat(item.title()).isEqualTo("标题123");
            assertThat(item.thumbnailUrl()).isEqualTo("/api/downloaded/thumbnail-file/123/0");
            assertThat(item.detailUrl()).isEqualTo("/pixiv-artwork.html?id=123");
            assertThat(item.attributes()).containsEntry("authorId", "88")
                    .containsEntry("authorName", "作者88")
                    .containsEntry("contentRating", "r18g")
                    .containsEntry("aiStatus", "unknown")
                    .containsEntry("tagIds", "5")
                    .containsEntry("tagNames", "猫");
            assertThat(item.attributes()).doesNotContainKey("isAi");
        });

        ArgumentCaptor<WorkQuery> captor = ArgumentCaptor.forClass(WorkQuery.class);
        verify(workQueryService).search(captor.capture());
        assertThat(captor.getValue().workType()).isEqualTo(WorkType.ARTWORK);
        assertThat(captor.getValue().page()).isZero();
        assertThat(captor.getValue().size()).isEqualTo(2);
        assertThat(captor.getValue().r18()).isEqualTo("any");
        assertThat(captor.getValue().ai()).isEqualTo("any");
    }

    @Test
    @DisplayName("offset 不在页边界时会补足下一页并保持 offset/limit 语义")
    void queryKeepsOffsetLimitSemanticsAcrossPages() {
        when(workQueryService.search(argThat(q -> q != null && q.page() == 1 && q.size() == 4)))
                .thenReturn(new PagedResult<>(summaries(10L, 11L, 12L, 13L), 12, 1, 4, 3));
        when(workQueryService.search(argThat(q -> q != null && q.page() == 2 && q.size() == 4)))
                .thenReturn(new PagedResult<>(summaries(14L, 15L, 16L, 17L), 12, 2, 4, 3));
        when(workMetadataRepository.findAll(WorkType.ARTWORK, List.of(11L, 12L, 13L, 14L)))
                .thenReturn(List.of(
                        meta(11L, null, null, null, 0, false, List.of()),
                        meta(12L, null, null, null, 0, false, List.of()),
                        meta(13L, null, null, null, 0, false, List.of()),
                        meta(14L, null, null, null, 0, false, List.of())));

        GalleryPage page = provider.query(new GalleryQuery(
                GalleryKind.IMAGE, "pixiv", List.of(), Map.of(), 5, 4));

        assertThat(page.offset()).isEqualTo(5);
        assertThat(page.limit()).isEqualTo(4);
        assertThat(page.total()).isEqualTo(12);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.items()).extracting(item -> item.ref().workId())
                .containsExactly("11", "12", "13", "14");
    }

    @Test
    @DisplayName("指定其它 sourceId 时不触发 pixiv 查询")
    void queryIgnoresForeignSourceId() {
        GalleryPage page = provider.query(new GalleryQuery(
                GalleryKind.IMAGE, "douyin", List.of(), Map.of(), 0, 10));

        assertThat(page.items()).isEmpty();
        assertThat(page.total()).isZero();
        verifyNoInteractions(workQueryService, workMetadataRepository);
    }

    @Test
    @DisplayName("作者和 tag facet 带 pixiv sourceId")
    void facetsCarrySourceId() {
        when(workQueryService.authors(new AuthorQuery(WorkType.ARTWORK, null)))
                .thenReturn(List.of(new AuthorSummary(88L, "同名作者", 3)));
        when(workQueryService.tags(new TagQuery(WorkType.ARTWORK, null, 500, null)))
                .thenReturn(List.of(new TagOption(5L, "同名标签", "same", 7)));

        GalleryFacetPage page = provider.facets(GalleryQuery.of(GalleryKind.IMAGE));

        assertThat(page.facets()).hasSize(2);
        assertThat(page.facets())
                .extracting(facet -> facet.sourceId(), facet -> facet.type(), facet -> facet.count())
                .containsExactly(
                        tuple("pixiv", GalleryFacetType.AUTHOR, 3L),
                        tuple("pixiv", GalleryFacetType.TAG, 7L));
    }

    @Test
    @DisplayName("AI 非真筛选不把 unknown 当作 false")
    void aiFalseFilterDoesNotIncludeUnknown() {
        when(workQueryService.search(any()))
                .thenReturn(new PagedResult<>(summaries(1L, 2L, 3L), 3, 0, 50, 1));
        when(workMetadataRepository.findAll(WorkType.ARTWORK, List.of(1L, 2L, 3L)))
                .thenReturn(List.of(
                        meta(1L, null, null, null, 0, true, List.of()),
                        meta(2L, null, null, null, 0, false, List.of()),
                        meta(3L, null, null, null, 0, null, List.of())));

        GalleryPage page = provider.query(new GalleryQuery(
                GalleryKind.IMAGE, "pixiv", List.of(), Map.of("ai", "no"), 0, 10));

        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items()).extracting(item -> item.ref().workId()).containsExactly("2");
        ArgumentCaptor<WorkQuery> captor = ArgumentCaptor.forClass(WorkQuery.class);
        verify(workQueryService).search(captor.capture());
        verify(workQueryService, never()).searchAll(any());
        assertThat(captor.getValue().size()).isEqualTo(50);
        assertThat(captor.getValue().r18()).isEqualTo("any");
        assertThat(captor.getValue().ai()).isEqualTo("any");
    }

    @Test
    @DisplayName("R18 metadata 筛选按批次扫描并保持过滤后总数")
    void r18FilterScansMetadataInBatches() {
        when(workQueryService.search(argThat(q -> q != null && q.page() == 0 && q.size() == 50)))
                .thenReturn(new PagedResult<>(summariesRange(1, 50), 55, 0, 50, 2));
        when(workQueryService.search(argThat(q -> q != null && q.page() == 1 && q.size() == 50)))
                .thenReturn(new PagedResult<>(summariesRange(51, 55), 55, 1, 50, 2));
        when(workMetadataRepository.findAll(WorkType.ARTWORK, idsRange(1, 50)))
                .thenReturn(metadataRange(1, 50, 0));
        when(workMetadataRepository.findAll(WorkType.ARTWORK, idsRange(51, 55)))
                .thenReturn(List.of(
                        meta(51L, null, null, null, 1, false, List.of()),
                        meta(52L, null, null, null, 1, false, List.of()),
                        meta(53L, null, null, null, 0, false, List.of()),
                        meta(54L, null, null, null, 0, false, List.of()),
                        meta(55L, null, null, null, 0, false, List.of())));

        GalleryPage page = provider.query(new GalleryQuery(
                GalleryKind.IMAGE, "pixiv", List.of(), Map.of("r18", "yes"), 0, 2));

        assertThat(page.total()).isEqualTo(2);
        assertThat(page.hasMore()).isFalse();
        assertThat(page.items()).extracting(item -> item.ref().workId()).containsExactly("51", "52");
        verify(workQueryService, never()).searchAll(any());
    }

    private static List<WorkSummary> summaries(long... ids) {
        List<WorkSummary> out = new java.util.ArrayList<>(ids.length);
        for (long id : ids) {
            out.add(new WorkSummary(WorkType.ARTWORK, id));
        }
        return out;
    }

    private static List<WorkSummary> summariesRange(long first, long last) {
        List<WorkSummary> out = new java.util.ArrayList<>((int) (last - first + 1));
        for (long id = first; id <= last; id++) {
            out.add(new WorkSummary(WorkType.ARTWORK, id));
        }
        return out;
    }

    private static List<Long> idsRange(long first, long last) {
        List<Long> out = new java.util.ArrayList<>((int) (last - first + 1));
        for (long id = first; id <= last; id++) {
            out.add(id);
        }
        return out;
    }

    private static List<WorkMetadata> metadataRange(long first, long last, Integer xRestrict) {
        List<WorkMetadata> out = new java.util.ArrayList<>((int) (last - first + 1));
        for (long id = first; id <= last; id++) {
            out.add(meta(id, null, null, null, xRestrict, false, List.of()));
        }
        return out;
    }

    private static WorkMetadata meta(long id, Long authorId, String authorName, Long seriesId,
                                     Integer xRestrict, Boolean isAi, List<WorkTag> tags) {
        return new WorkMetadata(WorkType.ARTWORK, id, "标题" + id, "简介" + id,
                xRestrict, isAi, authorId, authorName,
                seriesId, seriesId == null ? null : 1L, seriesId == null ? null : "系列" + seriesId,
                tags, 1000L + id, 2, "jpg,png", "/p/" + id,
                false, null, null, 1L, "{artwork_id}_p{page}", null,
                2000L + id, null, null);
    }
}
