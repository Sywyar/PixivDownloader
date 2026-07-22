package top.sywyar.pixivdownload.gallery;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import top.sywyar.pixivdownload.core.gallery.facet.GalleryFacetType;
import top.sywyar.pixivdownload.core.gallery.model.GalleryKind;
import top.sywyar.pixivdownload.core.gallery.model.identity.GalleryWorkKey;
import top.sywyar.pixivdownload.core.gallery.model.media.GalleryMediaKind;
import top.sywyar.pixivdownload.core.gallery.model.projection.GalleryDataAccess;
import top.sywyar.pixivdownload.core.gallery.query.GalleryFilter;
import top.sywyar.pixivdownload.core.gallery.query.GalleryFilterField;
import top.sywyar.pixivdownload.core.gallery.query.GalleryFilterMode;
import top.sywyar.pixivdownload.core.gallery.query.GalleryProjectionQuery;
import top.sywyar.pixivdownload.core.gallery.query.GallerySortDirection;
import top.sywyar.pixivdownload.core.gallery.query.GallerySortField;
import top.sywyar.pixivdownload.core.work.model.PagedResult;
import top.sywyar.pixivdownload.core.work.model.WorkMetadata;
import top.sywyar.pixivdownload.core.work.model.WorkSummary;
import top.sywyar.pixivdownload.core.work.model.WorkTag;
import top.sywyar.pixivdownload.core.work.model.WorkType;
import top.sywyar.pixivdownload.core.work.query.AuthorQuery;
import top.sywyar.pixivdownload.core.work.query.AuthorSummary;
import top.sywyar.pixivdownload.core.work.query.TagOption;
import top.sywyar.pixivdownload.core.work.query.TagQuery;
import top.sywyar.pixivdownload.core.work.query.WorkQuery;
import top.sywyar.pixivdownload.core.work.service.WorkMetadataRepository;
import top.sywyar.pixivdownload.core.work.service.WorkQueryService;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Pixiv 图片长期画廊能力")
class PixivImageGalleryCapabilityProviderTest {

    @Mock
    private WorkQueryService workQueryService;
    @Mock
    private WorkMetadataRepository metadataRepository;

    private PixivImageGalleryCapabilityProvider provider;

    @BeforeEach
    void setUp() {
        provider = new PixivImageGalleryCapabilityProvider(workQueryService, metadataRepository);
    }

    @Test
    @DisplayName("显式声明共享 Pixiv 图片投影和作品命名空间")
    void declaresSharedProjectionAndWorkNamespace() {
        assertThat(provider.providerId()).isEqualTo("pixiv-image-capability");
        assertThat(provider.projections()).singleElement().satisfies(descriptor -> {
            assertThat(descriptor.sourceId()).isEqualTo("pixiv");
            assertThat(descriptor.kind()).isEqualTo(GalleryKind.IMAGE);
            assertThat(descriptor.dataAccess()).isEqualTo(GalleryDataAccess.SHARED);
            assertThat(descriptor.filterCapabilities()).containsKeys(
                    GalleryFilterField.AUTHOR,
                    GalleryFilterField.TAG,
                    GalleryFilterField.AI_STATUS,
                    GalleryFilterField.CONTENT_RATING,
                    GalleryFilterField.SOURCE,
                    GalleryFilterField.CONTAINED_MEDIA_KIND);
        });
        assertThat(provider.works()).singleElement().satisfies(descriptor -> {
            assertThat(descriptor.sourceId()).isEqualTo("pixiv");
            assertThat(descriptor.sourceWorkNamespace()).isEqualTo("artwork");
            assertThat(descriptor.dataAccess()).isEqualTo(GalleryDataAccess.SHARED);
        });
    }

    @Test
    @DisplayName("列表直接经作品端口映射中性投影并保留游标")
    void mapsPrimaryPageToNeutralProjection() {
        when(workQueryService.search(any())).thenReturn(new PagedResult<>(
                summaries(123L), 2, 0, 1, 2));
        when(metadataRepository.findAll(WorkType.ARTWORK, List.of(123L)))
                .thenReturn(List.of(meta(123L, 88L, "作者", 2, null,
                        List.of(new WorkTag(5L, "猫", "cat")), "webp")));

        var page = provider.page(query(null, 1, List.of()));

        assertThat(page.nextCursor()).isEqualTo("1");
        assertThat(page.projections()).singleElement().satisfies(projection -> {
            assertThat(projection.key().workKey())
                    .isEqualTo(new GalleryWorkKey("pixiv", "artwork", "123"));
            assertThat(projection.containedMediaKinds()).containsExactly(GalleryMediaKind.UGOIRA);
            assertThat(projection.attributes()).containsEntry("authorId", "88")
                    .containsEntry("contentRating", "r18g")
                    .containsEntry("tagIds", "5");
        });

        ArgumentCaptor<WorkQuery> captor = ArgumentCaptor.forClass(WorkQuery.class);
        verify(workQueryService).search(captor.capture());
        assertThat(captor.getValue().workType()).isEqualTo(WorkType.ARTWORK);
        assertThat(captor.getValue().page()).isZero();
        assertThat(captor.getValue().size()).isEqualTo(1);
        assertThat(captor.getValue().sort()).isEqualTo("date");
        assertThat(captor.getValue().r18()).isEqualTo("any");
        assertThat(captor.getValue().ai()).isEqualTo("any");
    }

    @Test
    @DisplayName("游标不在页边界时补足下一页并保持查询顺序")
    void keepsCursorLimitSemanticsAcrossPages() {
        when(workQueryService.search(argThat(query -> query != null && query.page() == 1 && query.size() == 4)))
                .thenReturn(new PagedResult<>(summaries(10L, 11L, 12L, 13L), 12, 1, 4, 3));
        when(workQueryService.search(argThat(query -> query != null && query.page() == 2 && query.size() == 4)))
                .thenReturn(new PagedResult<>(summaries(14L, 15L, 16L, 17L), 12, 2, 4, 3));
        when(metadataRepository.findAll(WorkType.ARTWORK, List.of(11L, 12L, 13L, 14L)))
                .thenReturn(List.of(
                        meta(11L, null, null, 0, false, List.of(), "jpg"),
                        meta(12L, null, null, 0, false, List.of(), "jpg"),
                        meta(13L, null, null, 0, false, List.of(), "jpg"),
                        meta(14L, null, null, 0, false, List.of(), "jpg")));

        var page = provider.page(query("5", 4, List.of()));

        assertThat(page.nextCursor()).isEqualTo("9");
        assertThat(page.projections())
                .extracting(projection -> projection.key().workKey().sourceWorkId())
                .containsExactly("11", "12", "13", "14");
    }

    @Test
    @DisplayName("其它来源或不支持的筛选模式不会触发 Pixiv 查询")
    void ignoresForeignSourceAndUnsupportedMode() {
        GalleryProjectionQuery foreign = new GalleryProjectionQuery(
                GalleryKind.IMAGE, "douyin", List.of(), GallerySortField.DOWNLOADED_AT,
                GallerySortDirection.DESC, null, 10);
        GalleryProjectionQuery unsupported = query(null, 10, List.of(new GalleryFilter(
                GalleryFilterField.TAG, GalleryFilterMode.ALL_OF, "pixiv", Set.of("5"))));

        assertThat(provider.page(foreign).projections()).isEmpty();
        assertThat(provider.page(unsupported).projections()).isEmpty();
        verifyNoInteractions(workQueryService, metadataRepository);
    }

    @Test
    @DisplayName("作者和标签 facet 带 Pixiv 来源身份")
    void facetsCarrySourceId() {
        when(workQueryService.authors(new AuthorQuery(WorkType.ARTWORK, null)))
                .thenReturn(List.of(new AuthorSummary(88L, "同名作者", 3)));
        when(workQueryService.tags(new TagQuery(WorkType.ARTWORK, null, 500, null)))
                .thenReturn(List.of(new TagOption(5L, "同名标签", "same", 7)));

        var page = provider.facets(query(null, 10, List.of(new GalleryFilter(
                GalleryFilterField.TAG, GalleryFilterMode.ANY_OF, "pixiv", Set.of("not-an-id")))));

        assertThat(page.facets())
                .extracting(facet -> facet.sourceId(), facet -> facet.type(), facet -> facet.count())
                .containsExactly(
                        tuple("pixiv", GalleryFacetType.AUTHOR, 3L),
                        tuple("pixiv", GalleryFacetType.TAG, 7L));
    }

    @Test
    @DisplayName("AI 非真筛选不把未知值当作 false 且计数使用相同谓词")
    void aiFalseFilterExcludesUnknownFromPageAndCount() {
        when(workQueryService.search(any())).thenReturn(new PagedResult<>(
                summaries(1L, 2L, 3L), 3, 0, 50, 1));
        when(metadataRepository.findAll(WorkType.ARTWORK, List.of(1L, 2L, 3L)))
                .thenReturn(List.of(
                        meta(1L, null, null, 0, true, List.of(), "jpg"),
                        meta(2L, null, null, 0, false, List.of(), "jpg"),
                        meta(3L, null, null, 0, null, List.of(), "jpg")));
        GalleryProjectionQuery query = query(null, 10, List.of(new GalleryFilter(
                GalleryFilterField.AI_STATUS, GalleryFilterMode.ANY_OF, "pixiv", Set.of("NON_AI"))));

        var page = provider.page(query);
        long count = provider.count(query);

        assertThat(page.projections())
                .extracting(projection -> projection.key().workKey().sourceWorkId())
                .containsExactly("2");
        assertThat(count).isEqualTo(1);
        verify(workQueryService, never()).searchAll(any());
    }

    @Test
    @DisplayName("R18 筛选按批次扫描并保持过滤后的完整计数")
    void r18FilterScansMetadataInBatches() {
        when(workQueryService.search(argThat(query -> query != null && query.page() == 0 && query.size() == 50)))
                .thenReturn(new PagedResult<>(summariesRange(1, 50), 55, 0, 50, 2));
        when(workQueryService.search(argThat(query -> query != null && query.page() == 1 && query.size() == 50)))
                .thenReturn(new PagedResult<>(summariesRange(51, 55), 55, 1, 50, 2));
        when(metadataRepository.findAll(WorkType.ARTWORK, idsRange(1, 50)))
                .thenReturn(metadataRange(1, 50, 0));
        when(metadataRepository.findAll(WorkType.ARTWORK, idsRange(51, 55)))
                .thenReturn(List.of(
                        meta(51L, null, null, 1, false, List.of(), "jpg"),
                        meta(52L, null, null, 1, false, List.of(), "jpg"),
                        meta(53L, null, null, 0, false, List.of(), "jpg"),
                        meta(54L, null, null, 0, false, List.of(), "jpg"),
                        meta(55L, null, null, 0, false, List.of(), "jpg")));
        GalleryProjectionQuery query = query(null, 2, List.of(new GalleryFilter(
                GalleryFilterField.CONTENT_RATING, GalleryFilterMode.ANY_OF, "pixiv", Set.of("R18"))));

        var page = provider.page(query);
        long count = provider.count(query);

        assertThat(page.projections())
                .extracting(projection -> projection.key().workKey().sourceWorkId())
                .containsExactly("51", "52");
        assertThat(page.hasMore()).isFalse();
        assertThat(count).isEqualTo(2);
    }

    @Test
    @DisplayName("作者标签和标题排序被归一为宿主作品查询参数")
    void mapsTypedFiltersAndSortToWorkQuery() {
        when(workQueryService.search(any())).thenReturn(new PagedResult<>(List.of(), 0, 0, 10, 0));
        GalleryProjectionQuery query = new GalleryProjectionQuery(
                GalleryKind.IMAGE, "pixiv", List.of(
                new GalleryFilter(GalleryFilterField.AUTHOR, GalleryFilterMode.ANY_OF,
                        "pixiv", Set.of("invalid")),
                new GalleryFilter(GalleryFilterField.AUTHOR, GalleryFilterMode.ANY_OF,
                        "pixiv", Set.of(" 88 ")),
                new GalleryFilter(GalleryFilterField.TAG, GalleryFilterMode.ANY_OF,
                        "pixiv", Set.of("5,6"))),
                GallerySortField.TITLE, GallerySortDirection.ASC, null, 10);

        provider.page(query);

        ArgumentCaptor<WorkQuery> captor = ArgumentCaptor.forClass(WorkQuery.class);
        verify(workQueryService).search(captor.capture());
        assertThat(captor.getValue().sort()).isEqualTo("artworkId");
        assertThat(captor.getValue().order()).isEqualTo("asc");
        assertThat(captor.getValue().authorIds()).containsExactly(88L);
        assertThat(captor.getValue().tagIds()).containsExactly(5L, 6L);
    }

    @Test
    @DisplayName("作品详情返回全部本地页并与投影共享身份")
    void returnsCompleteArtworkMedia() {
        when(metadataRepository.find(WorkType.ARTWORK, 123L)).thenReturn(Optional.of(
                meta(123L, 88L, "作者", 0, false, List.of(), "webp")));

        var work = provider.find(new GalleryWorkKey("pixiv", "artwork", "123")).orElseThrow();

        assertThat(work.media()).hasSize(2);
        assertThat(work.media()).extracting(asset -> asset.key().mediaId())
                .containsExactly("page-0", "page-1");
        assertThat(work.media()).extracting(asset -> asset.kind())
                .containsOnly(GalleryMediaKind.UGOIRA);
    }

    @Test
    @DisplayName("非法游标明确拒绝而不是回落首页")
    void rejectsInvalidCursor() {
        assertThatThrownBy(() -> provider.page(query("not-a-cursor", 10, List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Pixiv image cursor");
    }

    private static GalleryProjectionQuery query(String cursor, int limit, List<GalleryFilter> filters) {
        return new GalleryProjectionQuery(GalleryKind.IMAGE, "pixiv", filters,
                GallerySortField.DOWNLOADED_AT, GallerySortDirection.DESC, cursor, limit);
    }

    private static List<WorkSummary> summaries(long... ids) {
        List<WorkSummary> summaries = new ArrayList<>(ids.length);
        for (long id : ids) {
            summaries.add(new WorkSummary(WorkType.ARTWORK, id));
        }
        return summaries;
    }

    private static List<WorkSummary> summariesRange(long first, long last) {
        List<WorkSummary> summaries = new ArrayList<>((int) (last - first + 1));
        for (long id = first; id <= last; id++) {
            summaries.add(new WorkSummary(WorkType.ARTWORK, id));
        }
        return summaries;
    }

    private static List<Long> idsRange(long first, long last) {
        List<Long> ids = new ArrayList<>((int) (last - first + 1));
        for (long id = first; id <= last; id++) {
            ids.add(id);
        }
        return ids;
    }

    private static List<WorkMetadata> metadataRange(long first, long last, Integer xRestrict) {
        List<WorkMetadata> metadata = new ArrayList<>((int) (last - first + 1));
        for (long id = first; id <= last; id++) {
            metadata.add(meta(id, null, null, xRestrict, false, List.of(), "jpg"));
        }
        return metadata;
    }

    private static WorkMetadata meta(long id, Long authorId, String authorName,
                                     Integer xRestrict, Boolean isAi, List<WorkTag> tags,
                                     String extensions) {
        return new WorkMetadata(id, "标题" + id, "简介" + id,
                xRestrict, isAi, authorId, authorName, null, null, null,
                tags, 1000L + id, 2, extensions, "/p/" + id,
                false, null, null, 1L, "{artwork_id}_p{page}",
                2000L + id, null);
    }
}
