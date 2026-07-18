package top.sywyar.pixivdownload.novelgallery;

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
import top.sywyar.pixivdownload.plugin.api.work.model.NovelWorkDetails;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Pixiv NOVEL 画廊数据提供方")
class PixivNovelGalleryDataProviderTest {

    @Mock
    private WorkQueryService workQueryService;
    @Mock
    private NovelOwnedWorkSearch novelOwnedWorkSearch;
    @Mock
    private WorkMetadataRepository workMetadataRepository;

    private PixivNovelGalleryDataProvider provider;

    @BeforeEach
    void setUp() {
        provider = new PixivNovelGalleryDataProvider(
                workQueryService, novelOwnedWorkSearch, workMetadataRepository);
    }

    @Test
    @DisplayName("声明 pixiv 来源的 NOVEL provider 与小说字段能力")
    void declaresPixivNovelSourceAndFieldStrategies() {
        assertThat(provider.providerId()).isEqualTo("pixiv-novel");

        assertThat(provider.sources())
                .singleElement()
                .satisfies(source -> {
                    assertThat(source.providerId()).isEqualTo("pixiv-novel");
                    assertThat(source.sourceId()).isEqualTo("pixiv");
                    assertThat(source.kinds()).containsExactly(GalleryKind.NOVEL);
                    assertThat(source.displayNamespace()).isEqualTo("novel-gallery");
                    assertThat(source.displayI18nKey()).isEqualTo("source.pixiv");
                    assertThat(source.fieldStrategies())
                            .extracting(GalleryFieldStrategy::field, GalleryFieldStrategy::capability)
                            .containsExactly(
                                    tuple("r18", GalleryFieldCapability.SUPPORTED),
                                    tuple("ai", GalleryFieldCapability.SUPPORTED),
                                    tuple("language", GalleryFieldCapability.SUPPORTED),
                                    tuple("wordCount", GalleryFieldCapability.SUPPORTED),
                                    tuple("pageCount", GalleryFieldCapability.SUPPORTED));
                });
    }

    @Test
    @DisplayName("NOVEL 查询返回真实小说映射后的 GalleryItem")
    void queryMapsNovelMetadataToGalleryItem() {
        when(novelOwnedWorkSearch.search(any())).thenReturn(new PagedResult<>(
                summaries(123L), 1, 0, 2, 1));
        when(workMetadataRepository.findAll(WorkType.NOVEL, List.of(123L)))
                .thenReturn(List.of(meta(123L, 88L, "作者88", 700L, 1, false,
                        details("jpg", 1200, 8, "ja"),
                        List.of(new WorkTag(5L, "猫", "cat")))));

        GalleryPage page = provider.query(new GalleryQuery(
                GalleryKind.NOVEL, "pixiv", List.of(), Map.of("sort", "wordCount"), 0, 2));

        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items()).singleElement().satisfies(item -> {
            assertThat(item.ref().providerId()).isEqualTo("pixiv-novel");
            assertThat(item.ref().sourceId()).isEqualTo("pixiv");
            assertThat(item.ref().kind()).isEqualTo(GalleryKind.NOVEL);
            assertThat(item.ref().workId()).isEqualTo("123");
            assertThat(item.title()).isEqualTo("小说123");
            assertThat(item.thumbnailUrl()).isEqualTo("/api/gallery/novel/123/cover");
            assertThat(item.detailUrl()).isEqualTo("/pixiv-novel.html?id=123");
            assertThat(item.attributes()).containsEntry("authorId", "88")
                    .containsEntry("authorName", "作者88")
                    .containsEntry("contentRating", "r18")
                    .containsEntry("aiStatus", "false")
                    .containsEntry("isAi", "false")
                    .containsEntry("wordCount", "1200")
                    .containsEntry("pageCount", "8")
                    .containsEntry("language", "ja")
                    .containsEntry("xLanguage", "ja")
                    .containsEntry("coverExt", "jpg")
                    .containsEntry("tagIds", "5")
                    .containsEntry("tagNames", "猫");
        });

        ArgumentCaptor<WorkQuery> captor = ArgumentCaptor.forClass(WorkQuery.class);
        verify(novelOwnedWorkSearch).search(captor.capture());
        assertThat(captor.getValue().workType()).isEqualTo(WorkType.NOVEL);
        assertThat(captor.getValue().page()).isZero();
        assertThat(captor.getValue().size()).isEqualTo(2);
        assertThat(captor.getValue().sort()).isEqualTo("wordCount");
    }

    @Test
    @DisplayName("指定其它 sourceId 或其它 kind 时不触发小说查询")
    void queryIgnoresForeignSourceOrKind() {
        GalleryPage foreignSource = provider.query(new GalleryQuery(
                GalleryKind.NOVEL, "douyin", List.of(), Map.of(), 0, 10));
        GalleryPage foreignKind = provider.query(new GalleryQuery(
                GalleryKind.IMAGE, "pixiv", List.of(), Map.of(), 0, 10));

        assertThat(foreignSource.items()).isEmpty();
        assertThat(foreignKind.items()).isEmpty();
        verifyNoInteractions(workQueryService, novelOwnedWorkSearch, workMetadataRepository);
    }

    @Test
    @DisplayName("作者和 tag facet 带 pixiv sourceId")
    void facetsCarrySourceId() {
        when(workQueryService.authors(new AuthorQuery(WorkType.NOVEL, null)))
                .thenReturn(List.of(new AuthorSummary(88L, "同名作者", 3)));
        when(workQueryService.tags(new TagQuery(WorkType.NOVEL, null, 500, null)))
                .thenReturn(List.of(new TagOption(5L, "同名标签", "same", 7)));

        GalleryFacetPage page = provider.facets(GalleryQuery.of(GalleryKind.NOVEL));

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
        when(novelOwnedWorkSearch.search(any()))
                .thenReturn(new PagedResult<>(summaries(1L, 2L, 3L), 3, 0, 50, 1));
        when(workMetadataRepository.findAll(WorkType.NOVEL, List.of(1L, 2L, 3L)))
                .thenReturn(List.of(
                        meta(1L, null, null, null, 0, true, details(null, 100, 1, null), List.of()),
                        meta(2L, null, null, null, 0, false, details(null, 100, 1, null), List.of()),
                        meta(3L, null, null, null, 0, null, details(null, 100, 1, null), List.of())));

        GalleryPage page = provider.query(new GalleryQuery(
                GalleryKind.NOVEL, "pixiv", List.of(), Map.of("ai", "no"), 0, 10));

        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items()).extracting(item -> item.ref().workId()).containsExactly("2");
        ArgumentCaptor<WorkQuery> captor = ArgumentCaptor.forClass(WorkQuery.class);
        verify(novelOwnedWorkSearch).search(captor.capture());
        verify(novelOwnedWorkSearch, never()).searchAll(any());
        assertThat(captor.getValue().size()).isEqualTo(50);
        assertThat(captor.getValue().r18()).isEqualTo("any");
        assertThat(captor.getValue().ai()).isEqualTo("any");
    }

    private static List<WorkSummary> summaries(long... ids) {
        List<WorkSummary> out = new java.util.ArrayList<>(ids.length);
        for (long id : ids) {
            out.add(new WorkSummary(WorkType.NOVEL, id));
        }
        return out;
    }

    private static WorkMetadata meta(long id, Long authorId, String authorName, Long seriesId,
                                     Integer xRestrict, Boolean isAi, NovelWorkDetails details,
                                     List<WorkTag> tags) {
        return new WorkMetadata(WorkType.NOVEL, id, "小说" + id, "简介" + id,
                xRestrict, isAi, authorId, authorName,
                seriesId, seriesId == null ? null : 1L, seriesId == null ? null : "系列" + seriesId,
                tags, 1000L + id, 1, "txt,epub", "/n/" + id,
                false, null, null, null, null, null,
                2000L + id, details.isOriginal(), details);
    }

    private static NovelWorkDetails details(String coverExt, Integer wordCount, Integer pageCount, String language) {
        return new NovelWorkDetails(wordCount, 2000, 300, pageCount, true, language, coverExt,
                List.of("img-a"), List.of("zh-CN"));
    }
}
