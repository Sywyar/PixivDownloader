package top.sywyar.pixivdownload.collection;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import top.sywyar.pixivdownload.gallery.GalleryRepository;
import top.sywyar.pixivdownload.i18n.LocalizedException;
import top.sywyar.pixivdownload.setup.guest.GuestAccessGuard;
import top.sywyar.pixivdownload.setup.guest.GuestInviteSession;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CollectionController 访客边界")
class CollectionControllerTest {

    @Mock
    private CollectionService collectionService;
    @Mock
    private CollectionIconService iconService;
    @Mock
    private GalleryRepository galleryRepository;
    @Mock
    private GuestAccessGuard guestAccessGuard;

    private CollectionController controller;

    @BeforeEach
    void setUp() {
        controller = new CollectionController(collectionService, iconService, galleryRepository, guestAccessGuard);
    }

    @Test
    @DisplayName("插画收藏查询在读取成员关系前检查访客可见性")
    void collectionsOfChecksGuestVisibility() {
        MockHttpServletRequest request = guestRequest();
        when(collectionService.collectionsOf(123L)).thenReturn(List.of(1L, 2L));

        ResponseEntity<Map<String, Object>> response = controller.collectionsOf(123L, request);

        verify(guestAccessGuard).requireVisible(request, 123L);
        assertThat(response.getBody()).containsEntry("collectionIds", List.of(1L, 2L));
    }

    @Test
    @DisplayName("插画收藏查询在访客可见性检查失败时中止")
    void collectionsOfRejectsInvisibleArtwork() {
        MockHttpServletRequest request = guestRequest();
        doThrow(new LocalizedException(HttpStatus.FORBIDDEN,
                "guest.invite.forbidden",
                "forbidden"))
                .when(guestAccessGuard).requireVisible(request, 123L);

        assertThatThrownBy(() -> controller.collectionsOf(123L, request))
                .isInstanceOf(LocalizedException.class)
                .satisfies(e -> assertThat(((LocalizedException) e).getStatus())
                        .isEqualTo(HttpStatus.FORBIDDEN));

        verify(collectionService, never()).collectionsOf(anyLong());
    }

    @Test
    @DisplayName("小说收藏查询在读取成员关系前检查访客可见性")
    void novelCollectionsOfChecksGuestVisibility() {
        MockHttpServletRequest request = guestRequest();
        when(collectionService.novelCollectionsOf(456L)).thenReturn(List.of(3L));

        ResponseEntity<Map<String, Object>> response = controller.novelCollectionsOf(456L, request);

        verify(guestAccessGuard).requireNovelVisible(request, 456L);
        assertThat(response.getBody()).containsEntry("collectionIds", List.of(3L));
    }

    @Test
    @DisplayName("收藏夹图标下载在文件查找前拒绝不可见的访客收藏夹")
    void downloadIconRejectsInvisibleGuestCollection() {
        MockHttpServletRequest request = guestRequest();
        when(galleryRepository.findVisibleCollectionIds(any())).thenReturn(Set.of(8L));

        assertThatThrownBy(() -> controller.downloadIcon(7L, request))
                .isInstanceOf(LocalizedException.class)
                .satisfies(e -> assertThat(((LocalizedException) e).getStatus())
                        .isEqualTo(HttpStatus.FORBIDDEN));

        verify(collectionService, never()).get(anyLong());
        verifyNoInteractions(iconService);
    }

    @Test
    @DisplayName("收藏夹图标下载将管理员请求排除在访客收藏夹过滤之外")
    void downloadIconDoesNotApplyGuestCollectionFilterWithoutGuestSession() throws IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        when(collectionService.get(7L)).thenReturn(null);

        ResponseEntity<byte[]> response = controller.downloadIcon(7L, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verifyNoInteractions(galleryRepository);
    }

    private MockHttpServletRequest guestRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(GuestInviteSession.REQUEST_ATTR, new GuestInviteSession(
                1L, "invite-code", true, false, false, true, Set.of(), true, Set.of()
        ));
        return request;
    }
}
