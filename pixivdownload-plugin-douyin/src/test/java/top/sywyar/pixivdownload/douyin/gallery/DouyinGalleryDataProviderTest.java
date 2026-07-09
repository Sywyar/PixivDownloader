package top.sywyar.pixivdownload.douyin.gallery;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import top.sywyar.pixivdownload.core.gallery.model.GalleryKind;
import top.sywyar.pixivdownload.core.gallery.model.identity.GalleryWorkKey;
import top.sywyar.pixivdownload.core.gallery.model.media.GalleryMediaKind;
import top.sywyar.pixivdownload.core.gallery.query.GalleryProjectionQuery;
import top.sywyar.pixivdownload.core.gallery.query.GallerySortDirection;
import top.sywyar.pixivdownload.core.gallery.query.GallerySortField;
import top.sywyar.pixivdownload.douyin.db.history.DouyinHistoryPage;
import top.sywyar.pixivdownload.douyin.db.history.DouyinHistoryQuery;
import top.sywyar.pixivdownload.douyin.db.history.DouyinHistoryService;
import top.sywyar.pixivdownload.douyin.db.history.DouyinWorkFileRecord;
import top.sywyar.pixivdownload.douyin.db.history.DouyinWorkRecord;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Douyin 中性画廊数据提供方")
class DouyinGalleryDataProviderTest {

    private DouyinHistoryService historyService;
    private DouyinGalleryDataProvider provider;

    @BeforeEach
    void setUp() {
        historyService = mock(DouyinHistoryService.class);
        provider = new DouyinGalleryDataProvider(historyService);
    }

    @Test
    @DisplayName("同时声明图片和视频投影并共享 aweme 作品身份")
    void declaresImageVideoAndSharedWorkIdentity() {
        assertThat(provider.projections()).extracting(descriptor -> descriptor.kind())
                .containsExactly(GalleryKind.IMAGE, GalleryKind.VIDEO);
        assertThat(provider.works()).singleElement().satisfies(descriptor -> {
            assertThat(descriptor.sourceId()).isEqualTo("douyin");
            assertThat(descriptor.sourceWorkNamespace()).isEqualTo("aweme");
        });
    }

    @Test
    @DisplayName("图片投影的查询谓词仅接受 IMAGE 媒体")
    void imageProjectionUsesImageExistsPredicate() {
        when(historyService.search(any())).thenReturn(new DouyinHistoryPage(List.of(work("7351")), 1));
        when(historyService.findFilesByWorkId("7351")).thenReturn(List.of(file("7351", 0, null, "IMAGE")));

        var page = provider.page(query(GalleryKind.IMAGE));

        assertThat(page.projections()).singleElement().satisfies(projection -> {
            assertThat(projection.key().workKey())
                    .isEqualTo(new GalleryWorkKey("douyin", "aweme", "7351"));
            assertThat(projection.key().kind()).isEqualTo(GalleryKind.IMAGE);
            assertThat(projection.preferredMediaId()).isEqualTo("index-0");
        });
        ArgumentCaptor<DouyinHistoryQuery> captor = ArgumentCaptor.forClass(DouyinHistoryQuery.class);
        verify(historyService).search(captor.capture());
        assertThat(captor.getValue().requiredMediaTypes()).containsExactly("IMAGE");
    }

    @Test
    @DisplayName("视频投影同时接受普通视频和实况视频且封面不参与分流")
    void videoProjectionUsesVideoAndLivePhotoPredicate() {
        when(historyService.search(any())).thenReturn(new DouyinHistoryPage(List.of(work("7351")), 1));
        when(historyService.findFilesByWorkId("7351")).thenReturn(List.of(
                file("7351", 0, "cover", "COVER"),
                file("7351", 1, "live", "LIVE_PHOTO_VIDEO")));

        var page = provider.page(query(GalleryKind.VIDEO));

        assertThat(page.projections()).singleElement().satisfies(projection -> {
            assertThat(projection.preferredMediaId()).isEqualTo("live");
            assertThat(projection.containedMediaKinds())
                    .containsExactlyInAnyOrder(GalleryMediaKind.COVER, GalleryMediaKind.LIVE_PHOTO_VIDEO);
        });
        ArgumentCaptor<DouyinHistoryQuery> captor = ArgumentCaptor.forClass(DouyinHistoryQuery.class);
        verify(historyService).search(captor.capture());
        assertThat(captor.getValue().requiredMediaTypes())
                .containsExactly("VIDEO", "LIVE_PHOTO_VIDEO");
    }

    @Test
    @DisplayName("作品详情返回图片视频实况视频和封面的完整媒体集合")
    void workDetailReturnsCompleteMediaSet() {
        when(historyService.findById("7351")).thenReturn(java.util.Optional.of(work("7351")));
        when(historyService.findFilesByWorkId("7351")).thenReturn(List.of(
                file("7351", 0, "image", "IMAGE"),
                file("7351", 1, "video", "VIDEO"),
                file("7351", 2, "live", "LIVE_PHOTO_VIDEO"),
                file("7351", 3, null, "COVER")));

        var detail = provider.find(new GalleryWorkKey("douyin", "aweme", "7351"));

        assertThat(detail).isPresent();
        assertThat(detail.orElseThrow().media()).extracting(asset -> asset.kind())
                .containsExactly(GalleryMediaKind.IMAGE, GalleryMediaKind.VIDEO,
                        GalleryMediaKind.LIVE_PHOTO_VIDEO, GalleryMediaKind.COVER);
        assertThat(detail.orElseThrow().media().get(3).key().mediaId()).isEqualTo("index-3");
    }

    private static GalleryProjectionQuery query(GalleryKind kind) {
        return new GalleryProjectionQuery(kind, "douyin", List.of(), GallerySortField.DOWNLOADED_AT,
                GallerySortDirection.DESC, null, 20);
    }

    private static DouyinWorkFileRecord file(String workId, int index, String mediaId, String mediaType) {
        return new DouyinWorkFileRecord(workId, index, mediaId, mediaType,
                "file-" + index + ".bin", "bin", 12L, "application/octet-stream", 1000L);
    }

    private static DouyinWorkRecord work(String id) {
        return new DouyinWorkRecord(id, "标题" + id, "/tmp/douyin/" + id, 4, "jpg,mp4", 1000L,
                false, "MIXED", "https://v.douyin.com/" + id + "/",
                "https://www.douyin.com/video/" + id, "https://p3.douyinpic.com/" + id + ".jpg",
                "author-1", "作者1", "简介" + id, "条目" + id, "说明" + id, 2000L,
                "collection-1", "合集1", 3);
    }
}
