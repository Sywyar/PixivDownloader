package top.sywyar.pixivdownload.douyin.gallery;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.douyin.db.history.DouyinHistoryService;
import top.sywyar.pixivdownload.douyin.db.history.DouyinWorkFileRecord;
import top.sywyar.pixivdownload.douyin.db.history.DouyinWorkRecord;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("Douyin 插件自有画廊响应装配")
class DouyinGalleryDataProviderTest {

    private DouyinHistoryService historyService;
    private DouyinGalleryDataProvider provider;

    @BeforeEach
    void setUp() {
        historyService = mock(DouyinHistoryService.class);
        provider = new DouyinGalleryDataProvider(historyService);
    }

    @Test
    @DisplayName("卡片与详情保留插件自有作品身份和媒体类型")
    void assemblesSourceOwnedProjectionAndWork() {
        when(historyService.findById("7351")).thenReturn(Optional.of(work()));
        when(historyService.findFilesByWorkId("7351")).thenReturn(List.of(
                file(0, null, "COVER"), file(1, "live", "LIVE_PHOTO_VIDEO")));

        var card = provider.projection(work(), DouyinGalleryDataProvider.Kind.VIDEO);
        var detail = provider.find("7351").orElseThrow();

        assertThat(card.key().workKey())
                .isEqualTo(new DouyinGalleryDataProvider.WorkKey("douyin", "aweme", "7351"));
        assertThat(card.preferredMediaId()).isEqualTo("live");
        assertThat(card.thumbnailUrl()).isEqualTo("/api/douyin/history/7351/media/0");
        assertThat(detail.media()).extracting(DouyinGalleryDataProvider.MediaAsset::kind)
                .containsExactly(DouyinGalleryDataProvider.MediaKind.COVER,
                        DouyinGalleryDataProvider.MediaKind.LIVE_PHOTO_VIDEO);
        assertThat(detail.media().get(0).key().mediaId()).isEqualTo("index-0");
    }

    @Test
    @DisplayName("未知媒体类型保持 UNKNOWN")
    void unknownMediaTypeStaysUnknown() {
        when(historyService.findById("7351")).thenReturn(Optional.of(work()));
        when(historyService.findFilesByWorkId("7351"))
                .thenReturn(List.of(file(0, "future", "FUTURE_MEDIA")));

        assertThat(provider.find("7351").orElseThrow().media()).singleElement()
                .extracting(DouyinGalleryDataProvider.MediaAsset::kind)
                .isEqualTo(DouyinGalleryDataProvider.MediaKind.UNKNOWN);
    }

    private static DouyinWorkFileRecord file(int index, String mediaId, String mediaType) {
        return new DouyinWorkFileRecord("7351", index, mediaId, mediaType,
                "file-" + index + ".bin", "bin", 12L, "application/octet-stream", 1000L);
    }

    private static DouyinWorkRecord work() {
        return new DouyinWorkRecord("7351", "标题", "/tmp/douyin/7351", 2, "jpg,mp4", 1000L,
                false, "MIXED", "https://v.douyin.com/7351/",
                "https://www.douyin.com/video/7351", null,
                "author-1", "作者", "简介", "条目", "说明", 2000L,
                "collection-1", "合集", 3);
    }
}
