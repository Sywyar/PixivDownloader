package top.sywyar.pixivdownload.novelgallery;

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
import top.sywyar.pixivdownload.novel.db.NovelDatabase;
import top.sywyar.pixivdownload.novel.db.NovelRecord;
import top.sywyar.pixivdownload.novel.metadata.NovelWorkDetails;
import top.sywyar.pixivdownload.novel.metadata.NovelWorkDetailsRepository;
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
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Pixiv 小说长期画廊能力")
class PixivNovelGalleryCapabilityProviderTest {

    @Mock
    private WorkQueryService workQueryService;
    @Mock
    private WorkMetadataRepository metadataRepository;
    @Mock
    private NovelDatabase novelDatabase;
    @Mock
    private NovelWorkDetailsRepository novelWorkDetailsRepository;

    private PixivNovelGalleryCapabilityProvider provider;

    @BeforeEach
    void setUp() {
        provider = new PixivNovelGalleryCapabilityProvider(
                workQueryService, metadataRepository, novelDatabase, novelWorkDetailsRepository);
    }

    @Test
    @DisplayName("显式声明共享 Pixiv 小说投影和作品命名空间")
    void declaresSharedProjectionAndWorkNamespace() {
        assertThat(provider.providerId()).isEqualTo("pixiv-novel-capability");
        assertThat(provider.projections()).singleElement().satisfies(descriptor -> {
            assertThat(descriptor.sourceId()).isEqualTo("pixiv");
            assertThat(descriptor.kind()).isEqualTo(GalleryKind.NOVEL);
            assertThat(descriptor.dataAccess()).isEqualTo(GalleryDataAccess.SHARED);
            assertThat(descriptor.filterCapabilities().get(GalleryFilterField.CONTAINED_MEDIA_KIND)
                    .constantValues()).containsExactly("TEXT");
        });
        assertThat(provider.works()).singleElement().satisfies(descriptor -> {
            assertThat(descriptor.sourceId()).isEqualTo("pixiv");
            assertThat(descriptor.sourceWorkNamespace()).isEqualTo("novel");
            assertThat(descriptor.dataAccess()).isEqualTo(GalleryDataAccess.SHARED);
        });
    }

    @Test
    @DisplayName("列表直接经宿主作品端口映射小说投影")
    void mapsPrimaryNovelProjection() {
        when(workQueryService.search(any())).thenReturn(new PagedResult<>(
                summaries(123L), 1, 0, 1, 1));
        when(metadataRepository.findAll(WorkType.NOVEL, List.of(123L)))
                .thenReturn(List.of(meta(123L, 88L, "作者", 1, false, true,
                        List.of(new WorkTag(5L, "猫", "cat")))));
        when(novelWorkDetailsRepository.findAll(List.of(123L))).thenReturn(Map.of(
                123L, details(123L, "jpg", 1200, 8, "ja")));

        var page = provider.page(query(null, 1, List.of()));

        assertThat(page.projections()).singleElement().satisfies(projection -> {
            assertThat(projection.key().workKey())
                    .isEqualTo(new GalleryWorkKey("pixiv", "novel", "123"));
            assertThat(projection.containedMediaKinds())
                    .containsExactlyInAnyOrder(GalleryMediaKind.TEXT, GalleryMediaKind.COVER);
            assertThat(projection.attributes()).containsEntry("authorId", "88")
                    .containsEntry("contentRating", "r18")
                    .containsEntry("wordCount", "1200")
                    .containsEntry("pageCount", "8")
                    .containsEntry("language", "ja")
                    .containsEntry("tagIds", "5");
        });

        ArgumentCaptor<WorkQuery> captor = ArgumentCaptor.forClass(WorkQuery.class);
        verify(workQueryService).search(captor.capture());
        assertThat(captor.getValue().workType()).isEqualTo(WorkType.NOVEL);
        assertThat(captor.getValue().page()).isZero();
        assertThat(captor.getValue().size()).isEqualTo(1);
        assertThat(captor.getValue().sort()).isEqualTo("date");
        assertThat(captor.getValue().r18()).isEqualTo("any");
        assertThat(captor.getValue().ai()).isEqualTo("any");
    }

    @Test
    @DisplayName("元数据或详情在并发软删除中缺席时游标仍越过已消费的来源候选")
    void advancesCursorPastCandidatesWhoseMetadataOrDetailsDisappear() {
        when(workQueryService.search(any())).thenReturn(new PagedResult<>(
                summaries(1L, 2L, 3L), 4, 0, 3, 2));
        WorkMetadata second = meta(2L, null, null, 0, false, true, List.of());
        WorkMetadata third = meta(3L, null, null, 0, false, true, List.of());
        when(metadataRepository.findAll(WorkType.NOVEL, List.of(1L, 2L, 3L)))
                .thenReturn(List.of(second, third));
        when(novelWorkDetailsRepository.findAll(List.of(2L, 3L))).thenReturn(Map.of(
                3L, details(3L, null, 100, 1, null)));

        var page = provider.page(query(null, 3, List.of()));

        assertThat(page.projections())
                .extracting(projection -> projection.key().workKey().sourceWorkId())
                .containsExactly("3");
        assertThat(page.nextCursor()).isEqualTo("3");
        assertThat(page.hasMore()).isTrue();
    }

    @Test
    @DisplayName("指定其它来源或非文本媒体时不触发小说查询")
    void ignoresForeignSourceOrNonTextMedia() {
        GalleryProjectionQuery foreign = new GalleryProjectionQuery(
                GalleryKind.NOVEL, "douyin", List.of(), GallerySortField.DOWNLOADED_AT,
                GallerySortDirection.DESC, null, 10);
        GalleryProjectionQuery nonText = query(null, 10, List.of(new GalleryFilter(
                GalleryFilterField.CONTAINED_MEDIA_KIND, GalleryFilterMode.ANY_OF,
                "pixiv", Set.of("IMAGE"))));

        assertThat(provider.page(foreign).projections()).isEmpty();
        assertThat(provider.page(nonText).projections()).isEmpty();
        verifyNoInteractions(workQueryService, metadataRepository, novelDatabase,
                novelWorkDetailsRepository);
    }

    @Test
    @DisplayName("作者和标签 facet 带 Pixiv 来源身份")
    void facetsCarrySourceId() {
        when(workQueryService.authors(new AuthorQuery(WorkType.NOVEL, null)))
                .thenReturn(List.of(new AuthorSummary(88L, "同名作者", 3)));
        when(workQueryService.tags(new TagQuery(WorkType.NOVEL, null, 500, null)))
                .thenReturn(List.of(new TagOption(5L, "同名标签", "same", 7)));

        var page = provider.facets(query(null, 10, List.of()));

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
        when(metadataRepository.findAll(WorkType.NOVEL, List.of(1L, 2L, 3L)))
                .thenReturn(List.of(
                        meta(1L, null, null, 0, true, true, List.of()),
                        meta(2L, null, null, 0, false, true, List.of()),
                        meta(3L, null, null, 0, null, true, List.of())));
        when(novelWorkDetailsRepository.findAll(List.of(2L))).thenReturn(Map.of(
                2L, details(2L, null, 100, 1, null)));
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
    @DisplayName("小说详情返回正文封面和内嵌图片的完整媒体集合")
    void returnsTextCoverAndEmbeddedImages() {
        NovelRecord record = org.mockito.Mockito.mock(NovelRecord.class);
        when(record.rawContent()).thenReturn("[chapter:正文]");
        when(novelDatabase.getNovel(123L)).thenReturn(record);
        when(metadataRepository.find(WorkType.NOVEL, 123L)).thenReturn(Optional.of(
                meta(123L, 88L, "作者", 0, false, true, List.of())));
        when(novelWorkDetailsRepository.find(123L)).thenReturn(Optional.of(
                details(123L, "jpg", 1000, 4, "ja")));

        var work = provider.find(new GalleryWorkKey("pixiv", "novel", "123")).orElseThrow();

        assertThat(work.media()).extracting(asset -> asset.kind()).containsExactly(
                GalleryMediaKind.TEXT, GalleryMediaKind.COVER, GalleryMediaKind.IMAGE);
        assertThat(work.media().get(0).content()).isEqualTo("[chapter:正文]");
        assertThat(work.media()).extracting(asset -> asset.key().mediaId())
                .containsExactly("text", "cover", "embedded-img-a");
    }

    @Test
    @DisplayName("作者和标签筛选被归一为宿主作品查询参数")
    void mapsAuthorAndTagFiltersToWorkQuery() {
        when(workQueryService.search(any())).thenReturn(new PagedResult<>(List.of(), 0, 0, 10, 0));
        GalleryProjectionQuery query = query(null, 10, List.of(
                new GalleryFilter(GalleryFilterField.AUTHOR, GalleryFilterMode.ANY_OF,
                        "pixiv", Set.of(" 88 ")),
                new GalleryFilter(GalleryFilterField.TAG, GalleryFilterMode.ANY_OF,
                        "pixiv", Set.of("5,6"))));

        provider.page(query);

        ArgumentCaptor<WorkQuery> captor = ArgumentCaptor.forClass(WorkQuery.class);
        verify(workQueryService).search(captor.capture());
        assertThat(captor.getValue().authorIds()).containsExactly(88L);
        assertThat(captor.getValue().tagIds()).containsExactly(5L, 6L);
    }

    private static GalleryProjectionQuery query(String cursor, int limit, List<GalleryFilter> filters) {
        return new GalleryProjectionQuery(GalleryKind.NOVEL, "pixiv", filters,
                GallerySortField.DOWNLOADED_AT, GallerySortDirection.DESC, cursor, limit);
    }

    private static List<WorkSummary> summaries(long... ids) {
        List<WorkSummary> summaries = new ArrayList<>(ids.length);
        for (long id : ids) {
            summaries.add(new WorkSummary(WorkType.NOVEL, id));
        }
        return summaries;
    }

    private static WorkMetadata meta(long id, Long authorId, String authorName,
                                     Integer xRestrict, Boolean isAi, Boolean isOriginal,
                                     List<WorkTag> tags) {
        return new WorkMetadata(WorkType.NOVEL, id, "小说" + id, "简介" + id,
                xRestrict, isAi, authorId, authorName, null, null, null,
                tags, 1000L + id, 1, "txt,epub", "/n/" + id,
                false, null, null, null, null, null,
                2000L + id, isOriginal);
    }

    private static NovelWorkDetails details(long novelId, String coverExt, Integer wordCount,
                                            Integer pageCount, String language) {
        return new NovelWorkDetails(novelId, wordCount, 2000, 300, pageCount, language, coverExt,
                List.of("img-a"), List.of("zh-CN"));
    }
}
