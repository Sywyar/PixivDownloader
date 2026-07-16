package top.sywyar.pixivdownload.novelgallery.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import top.sywyar.pixivdownload.core.metadata.novel.NovelGalleryRepository;
import top.sywyar.pixivdownload.novel.NovelSeriesService;
import top.sywyar.pixivdownload.novel.db.NovelDatabase;
import top.sywyar.pixivdownload.novel.db.NovelDownloadedStatusRow;
import top.sywyar.pixivdownload.novelgallery.NovelBatchService;
import top.sywyar.pixivdownload.novelgallery.NovelGalleryService;
import top.sywyar.pixivdownload.plugin.api.work.service.WorkAssetService;
import top.sywyar.pixivdownload.setup.guest.GuestAccessGuard;
import top.sywyar.pixivdownload.setup.guest.GuestInviteSession;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("小说批量下载状态")
class NovelGalleryControllerDownloadedBatchTest {

    private NovelDatabase novelDatabase;
    private NovelGalleryController controller;

    @BeforeEach
    void setUp() {
        novelDatabase = mock(NovelDatabase.class);
        controller = new NovelGalleryController(
                mock(NovelGalleryService.class),
                mock(NovelBatchService.class),
                mock(NovelSeriesService.class),
                novelDatabase,
                mock(NovelGalleryRepository.class),
                mock(WorkAssetService.class),
                mock(GuestAccessGuard.class));
    }

    @Test
    @DisplayName("管理员可同时取得有效记录与独立的软删除 ID")
    void adminCanRequestDeletedIdsSeparately() {
        List<Long> ids = List.of(1L, 2L, 3L);
        when(novelDatabase.getDownloadedStatuses(ids)).thenReturn(List.of(
                new NovelDownloadedStatusRow(1L, false),
                new NovelDownloadedStatusRow(2L, true)));

        var response = controller.downloadedBatch(
                new NovelGalleryController.NovelDownloadedBatchRequest(ids, true),
                new MockHttpServletRequest());

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().novelIds()).containsExactly(1L);
        assertThat(response.getBody().deletedNovelIds()).containsExactly(2L);
    }

    @Test
    @DisplayName("受邀访客即使请求软删除状态也只得到有效记录")
    void guestCannotRequestDeletedIds() {
        List<Long> ids = List.of(1L, 2L);
        when(novelDatabase.getDownloadedStatuses(ids)).thenReturn(List.of(
                new NovelDownloadedStatusRow(1L, false),
                new NovelDownloadedStatusRow(2L, true)));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(GuestInviteSession.REQUEST_ATTR, guestSession());

        var response = controller.downloadedBatch(
                new NovelGalleryController.NovelDownloadedBatchRequest(ids, true), request);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().novelIds()).containsExactly(1L);
        assertThat(response.getBody().deletedNovelIds()).isEmpty();
        verify(novelDatabase).getDownloadedStatuses(ids);
    }

    @Test
    @DisplayName("旧请求省略 includeDeleted 时默认不返回软删除 ID")
    void omittedIncludeDeletedDefaultsToFalse() throws Exception {
        var request = new ObjectMapper().readValue(
                "{\"novelIds\":[1,2]}",
                NovelGalleryController.NovelDownloadedBatchRequest.class);
        when(novelDatabase.getDownloadedStatuses(request.novelIds())).thenReturn(List.of(
                new NovelDownloadedStatusRow(1L, false),
                new NovelDownloadedStatusRow(2L, true)));

        var response = controller.downloadedBatch(request, new MockHttpServletRequest());

        assertThat(request.includeDeleted()).isFalse();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().novelIds()).containsExactly(1L);
        assertThat(response.getBody().deletedNovelIds()).isEmpty();
    }

    @Test
    @DisplayName("空 ID 请求返回两个空列表")
    void emptyIdsReturnEmptyLists() {
        var response = controller.downloadedBatch(
                new NovelGalleryController.NovelDownloadedBatchRequest(List.of(), true),
                new MockHttpServletRequest());

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().novelIds()).isEmpty();
        assertThat(response.getBody().deletedNovelIds()).isEmpty();
        verify(novelDatabase).getDownloadedStatuses(List.of());
    }

    private static GuestInviteSession guestSession() {
        return new GuestInviteSession(
                1L, "guest", true, false, false,
                true, Set.of(), true, Set.of(),
                true, Set.of(), true, Set.of());
    }
}
