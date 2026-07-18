package top.sywyar.pixivdownload.gallery;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import top.sywyar.pixivdownload.gallery.web.GalleryArtworkResponse;
import top.sywyar.pixivdownload.gallery.web.GalleryPageResponse;
import top.sywyar.pixivdownload.gallery.web.GalleryTagOptionResponse;
import top.sywyar.pixivdownload.plugin.api.work.model.PagedResult;
import top.sywyar.pixivdownload.plugin.api.work.query.TagOption;
import top.sywyar.pixivdownload.plugin.api.work.query.TagQuery;
import top.sywyar.pixivdownload.plugin.api.work.service.WorkDeletionService;
import top.sywyar.pixivdownload.plugin.api.work.model.WorkMetadata;
import top.sywyar.pixivdownload.plugin.api.work.model.WorkRestriction;
import top.sywyar.pixivdownload.plugin.api.work.service.WorkMetadataRepository;
import top.sywyar.pixivdownload.plugin.api.work.query.WorkQuery;
import top.sywyar.pixivdownload.plugin.api.work.service.WorkQueryService;
import top.sywyar.pixivdownload.plugin.api.work.model.WorkSummary;
import top.sywyar.pixivdownload.plugin.api.work.model.WorkType;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("GalleryService 单元测试")
class GalleryServiceTest {

    @Mock
    private WorkQueryService workQueryService;
    @Mock
    private WorkMetadataRepository workMetadataRepository;
    @Mock
    private WorkDeletionService workDeletionService;

    private GalleryService galleryService;

    @BeforeEach
    void setUp() {
        galleryService = new GalleryService(workQueryService, workMetadataRepository, workDeletionService);
    }

    @Test
    @DisplayName("deleteArtwork 委托核心统一删除入口并透传布尔结果")
    void shouldDelegateDeleteArtworkToCore() {
        when(workDeletionService.delete(WorkType.ARTWORK, 12345L)).thenReturn(true);
        assertThat(galleryService.deleteArtwork(12345L)).isTrue();
        verify(workDeletionService).delete(WorkType.ARTWORK, 12345L);

        when(workDeletionService.delete(WorkType.ARTWORK, 404L)).thenReturn(false);
        assertThat(galleryService.deleteArtwork(404L)).isFalse();
        verify(workDeletionService).delete(WorkType.ARTWORK, 404L);
    }

    @Test
    @DisplayName("deleteArtworks 委托核心批量删除入口并透传删除计数")
    void shouldDelegateDeleteArtworksToCore() {
        when(workDeletionService.deleteAll(WorkType.ARTWORK, List.of(1L, 2L, 1L))).thenReturn(2);
        assertThat(galleryService.deleteArtworks(List.of(1L, 2L, 1L))).isEqualTo(2);
        verify(workDeletionService).deleteAll(WorkType.ARTWORK, List.of(1L, 2L, 1L));
    }

    @Nested
    @DisplayName("查询走核心接口（search → hydrate 两步）")
    class QueryDelegationTests {

        private static WorkMetadata meta(long id, Long authorId, Long seriesId) {
            return new WorkMetadata(WorkType.ARTWORK, id, "标题" + id, null, 0, false,
                    authorId, authorId == null ? null : "作者" + authorId,
                    seriesId, null, null, List.of(), 100L, 1, "jpg", "/p/" + id,
                    false, null, null, null, null, null, null, null);
        }

        @Test
        @DisplayName("query 先 search 取 id 分页，再批量 hydrate，内容顺序与 id 顺序一致、分页字段透传")
        void shouldSearchThenHydrateInOrder() {
            WorkQuery query = WorkQuery.builder(WorkType.ARTWORK)
                    .page(0).size(2).sort("date").order("desc").searchType("all")
                    .tagIds(List.of(11L)).build();
            when(workQueryService.search(any())).thenReturn(new PagedResult<>(
                    List.of(new WorkSummary(WorkType.ARTWORK, 2L), new WorkSummary(WorkType.ARTWORK, 1L)),
                    5, 0, 2, 3));
            when(workMetadataRepository.findAll(WorkType.ARTWORK, List.of(2L, 1L)))
                    .thenReturn(List.of(meta(2L, null, null), meta(1L, null, null)));

            GalleryPageResponse response = galleryService.query(query);

            assertThat(response.content()).extracting(GalleryArtworkResponse::artworkId)
                    .containsExactly(2L, 1L);
            assertThat(response.totalElements()).isEqualTo(5);
            assertThat(response.totalPages()).isEqualTo(3);

            ArgumentCaptor<WorkQuery> captor = ArgumentCaptor.forClass(WorkQuery.class);
            verify(workQueryService).search(captor.capture());
            WorkQuery workQuery = captor.getValue();
            assertThat(workQuery.workType()).isEqualTo(WorkType.ARTWORK);
            assertThat(workQuery.page()).isZero();
            assertThat(workQuery.size()).isEqualTo(2);
            assertThat(workQuery.tagIds()).containsExactly(11L);
            assertThat(workQuery.restriction()).isNull();
        }

        @Test
        @DisplayName("query 将 WorkRestriction 纯值传入核心接口")
        void shouldPassWorkRestriction() {
            WorkRestriction restriction = new WorkRestriction(
                    Set.of(0), false, List.of(7L), true, List.of());
            WorkQuery query = WorkQuery.builder(WorkType.ARTWORK)
                    .page(0).size(24).restriction(restriction).build();
            when(workQueryService.search(any())).thenReturn(new PagedResult<>(List.of(), 0, 0, 24, 0));

            galleryService.query(query);

            ArgumentCaptor<WorkQuery> captor = ArgumentCaptor.forClass(WorkQuery.class);
            verify(workQueryService).search(captor.capture());
            assertThat(captor.getValue().restriction()).isNotNull();
            assertThat(captor.getValue().restriction().allowedXRestricts()).containsExactly(0);
            assertThat(captor.getValue().restriction().tagUnrestricted()).isFalse();
            assertThat(captor.getValue().restriction().tagIds()).containsExactly(7L);
            assertThat(captor.getValue().restriction().authorUnrestricted()).isTrue();
        }

        @Test
        @DisplayName("findArtwork 经元数据仓库取行；无记录（含软删）返回 null")
        void shouldFindArtworkViaMetadataRepository() {
            when(workMetadataRepository.find(WorkType.ARTWORK, 1L))
                    .thenReturn(Optional.of(meta(1L, 88L, 700L)));
            when(workMetadataRepository.find(WorkType.ARTWORK, 404L)).thenReturn(Optional.empty());

            GalleryArtworkResponse found = galleryService.findArtwork(1L);
            assertThat(found).isNotNull();
            assertThat(found.artworkId()).isEqualTo(1L);
            assertThat(found.title()).isEqualTo("标题1");
            assertThat(found.authorName()).isEqualTo("作者88");
            assertThat(found.seriesId()).isEqualTo(700L);
            assertThat(found.deleted()).isFalse();

            assertThat(galleryService.findArtwork(404L)).isNull();
        }

        @Test
        @DisplayName("byAuthor 以基准作品的作者发起查询并钳制 limit；基准缺作者时返回空且不再查询")
        void shouldQueryByAuthorWithClampedLimit() {
            when(workMetadataRepository.find(WorkType.ARTWORK, 1L))
                    .thenReturn(Optional.of(meta(1L, 88L, null)));
            when(workQueryService.byAuthor(WorkType.ARTWORK, 88L, 1L, 12))
                    .thenReturn(List.of(new WorkSummary(WorkType.ARTWORK, 2L)));
            when(workMetadataRepository.findAll(WorkType.ARTWORK, List.of(2L)))
                    .thenReturn(List.of(meta(2L, 88L, null)));

            assertThat(galleryService.byAuthor(1L, 0))
                    .extracting(GalleryArtworkResponse::artworkId).containsExactly(2L);

            when(workMetadataRepository.find(WorkType.ARTWORK, 3L))
                    .thenReturn(Optional.of(meta(3L, null, null)));
            assertThat(galleryService.byAuthor(3L, 10)).isEmpty();
            verify(workQueryService, times(1)).byAuthor(any(), anyLong(), anyLong(), anyInt());
        }

        @Test
        @DisplayName("listTags / findTag 转换核心标签目录行，保持 artworkCount 字段语义")
        void shouldConvertTagOptions() {
            when(workQueryService.tags(any())).thenReturn(List.of(
                    new TagOption(5L, "魔法", "magic", 3L)));
            when(workQueryService.tagByName(WorkType.ARTWORK, "魔法", null))
                    .thenReturn(Optional.of(new TagOption(5L, "魔法", "magic", 3L)));

            List<GalleryTagOptionResponse> tags = galleryService.listTags("魔", 100, null);
            assertThat(tags).containsExactly(new GalleryTagOptionResponse(5L, "魔法", "magic", 3));

            assertThat(galleryService.findTag("魔法", null))
                    .isEqualTo(new GalleryTagOptionResponse(5L, "魔法", "magic", 3));

            ArgumentCaptor<TagQuery> captor = ArgumentCaptor.forClass(TagQuery.class);
            verify(workQueryService).tags(captor.capture());
            assertThat(captor.getValue().workType()).isEqualTo(WorkType.ARTWORK);
            assertThat(captor.getValue().search()).isEqualTo("魔");
            assertThat(captor.getValue().limit()).isEqualTo(100);
        }

        @Test
        @DisplayName("seriesNeighbors 透传核心接口结果，empty 映射为 null")
        void shouldReturnNullWhenNoSeriesNeighbors() {
            when(workQueryService.seriesNeighbors(WorkType.ARTWORK, 1L)).thenReturn(Optional.empty());

            assertThat(galleryService.seriesNeighbors(1L)).isNull();
        }
    }
}
