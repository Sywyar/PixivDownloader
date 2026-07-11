package top.sywyar.pixivdownload.douyin.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import top.sywyar.pixivdownload.core.gallery.model.GalleryKind;
import top.sywyar.pixivdownload.core.gallery.model.projection.GalleryProjectionPage;
import top.sywyar.pixivdownload.douyin.db.history.DouyinHistoryPage;
import top.sywyar.pixivdownload.douyin.db.history.DouyinHistoryQuery;
import top.sywyar.pixivdownload.douyin.db.history.DouyinHistoryService;
import top.sywyar.pixivdownload.douyin.db.history.DouyinWorkFileRecord;
import top.sywyar.pixivdownload.douyin.db.history.DouyinWorkRecord;
import top.sywyar.pixivdownload.douyin.gallery.DouyinGalleryDataProvider;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("抖音独立画廊接口")
class DouyinGalleryControllerTest {

    private DouyinHistoryService historyService;
    private DouyinGalleryController controller;

    @BeforeEach
    void setUp() {
        historyService = mock(DouyinHistoryService.class);
        controller = new DouyinGalleryController(
                historyService, new DouyinGalleryDataProvider(historyService));
    }

    @Test
    @DisplayName("总览分页保留共享作品身份并使用本地媒体缩略图")
    void allProjectionReturnsSourceOwnedCards() {
        when(historyService.search(any())).thenReturn(new DouyinHistoryPage(List.of(work()), 1));
        when(historyService.findFilesByWorkId("7351")).thenReturn(List.of(
                file(0, "cover", "COVER"), file(1, "video", "VIDEO")));

        var response = controller.projections("ALL", null, 24, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isInstanceOfSatisfying(GalleryProjectionPage.class, page ->
                assertThat(page.projections()).singleElement().satisfies(card -> {
                    assertThat(card.key().workKey().sourceId()).isEqualTo("douyin");
                    assertThat(card.key().workKey().sourceWorkNamespace()).isEqualTo("aweme");
                    assertThat(card.key().kind()).isEqualTo(GalleryKind.VIDEO);
                    assertThat(card.thumbnailUrl()).isEqualTo("/api/douyin/history/7351/media/0");
                }));
        ArgumentCaptor<DouyinHistoryQuery> query = ArgumentCaptor.forClass(DouyinHistoryQuery.class);
        verify(historyService).search(query.capture());
        assertThat(query.getValue().requiredMediaTypes()).isEmpty();
    }

    @Test
    @DisplayName("图片与视频分类映射为各自媒体存在条件")
    void categoriesUseMediaPredicates() {
        when(historyService.search(any())).thenReturn(new DouyinHistoryPage(List.of(), 0));

        controller.projections("IMAGE", null, 24, null);
        controller.projections("VIDEO", null, 24, null);

        ArgumentCaptor<DouyinHistoryQuery> query = ArgumentCaptor.forClass(DouyinHistoryQuery.class);
        verify(historyService, org.mockito.Mockito.times(2)).search(query.capture());
        assertThat(query.getAllValues().get(0).requiredMediaTypes()).containsExactly("IMAGE");
        assertThat(query.getAllValues().get(1).requiredMediaTypes())
                .containsExactly("VIDEO", "LIVE_PHOTO_VIDEO");
    }

    @Test
    @DisplayName("非法分类与游标返回稳定的客户端错误")
    void invalidCategoryAndCursorAreRejected() {
        assertThat(controller.projections("NOVEL", null, 24, null).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(controller.projections("ALL", "not-a-cursor", 24, null).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("详情返回完整媒体作品且缺失记录返回 404")
    void detailReturnsWorkOrNotFound() {
        when(historyService.findById("7351")).thenReturn(Optional.of(work()));
        when(historyService.findFilesByWorkId("7351")).thenReturn(List.of(file(0, "image", "IMAGE")));

        var found = controller.work("7351");
        var missing = controller.work("missing");

        assertThat(found.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(found.getBody()).isNotNull();
        assertThat(found.getBody().work().media()).singleElement().satisfies(media ->
                assertThat(media.url()).isEqualTo("/api/douyin/history/7351/media/0"));
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private static DouyinWorkRecord work() {
        return new DouyinWorkRecord("7351", "作品", "/tmp/douyin/7351", 2, "jpg,mp4", 1000L,
                false, "MIXED", "https://v.douyin.com/7351/",
                "https://www.douyin.com/video/7351", null,
                "author-1", "作者", "简介", "条目", "说明", 2000L,
                "collection-1", "合集", 3);
    }

    private static DouyinWorkFileRecord file(int index, String mediaId, String mediaType) {
        return new DouyinWorkFileRecord("7351", index, mediaId, mediaType,
                "file-" + index + ".bin", "bin", 12L,
                "application/octet-stream", 1000L);
    }
}
