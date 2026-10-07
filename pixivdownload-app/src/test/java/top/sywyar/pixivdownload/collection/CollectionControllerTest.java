package top.sywyar.pixivdownload.collection;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import top.sywyar.pixivdownload.core.metadata.artwork.GalleryRepository;
import top.sywyar.pixivdownload.core.metadata.novel.NovelGalleryRepository;
import top.sywyar.pixivdownload.i18n.LocalizedException;
import top.sywyar.pixivdownload.setup.guest.GuestAccessGuard;
import top.sywyar.pixivdownload.setup.guest.GuestInviteSession;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Collections;
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
    @Mock
    private NovelGalleryRepository novelGalleryRepository;
    @TempDir
    private Path tempDir;

    private CollectionController controller;

    @BeforeEach
    void setUp() {
        controller = new CollectionController(collectionService, iconService, galleryRepository, novelGalleryRepository, guestAccessGuard);
    }

    @Test
    @DisplayName("插画收藏查询在读取成员关系前检查访客可见性")
    void collectionsOfChecksGuestVisibility() {
        MockHttpServletRequest request = guestRequest();
        when(collectionService.collectionsOf(123L)).thenReturn(List.of(1L, 2L));

        ResponseEntity<CollectionController.CollectionIdsResponse> response =
                controller.collectionsOf(123L, request);

        verify(guestAccessGuard).requireVisible(request, 123L);
        assertThat(response.getBody().collectionIds()).containsExactly(1L, 2L);
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

        ResponseEntity<CollectionController.CollectionIdsResponse> response =
                controller.novelCollectionsOf(456L, request);

        verify(guestAccessGuard).requireNovelVisible(request, 456L);
        assertThat(response.getBody().collectionIds()).containsExactly(3L);
    }

    @Test
    @DisplayName("收藏夹图标下载在文件查找前拒绝不可见的访客收藏夹")
    void downloadIconRejectsInvisibleGuestCollection() {
        MockHttpServletRequest request = guestRequest();

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

    @Test
    @DisplayName("收藏夹图标下载响应带 nosniff")
    void downloadIconAddsNosniffHeader() throws IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        Path icon = tempDir.resolve("7.png");
        Files.write(icon, new byte[]{1, 2, 3});
        when(collectionService.get(7L)).thenReturn(new Collection(
                7L, "收藏夹", "png", null, 0, 1L, 0, 0));
        when(iconService.findExistingIcon(7L, "png")).thenReturn(icon);
        when(iconService.contentType("png")).thenReturn("image/png");

        ResponseEntity<byte[]> response = controller.downloadIcon(7L, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).isEqualTo("public, max-age=3600");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("访客批量归属只查询可见作品并仅返回可见收藏")
    void guestMembershipsFilterBothVisibilityBoundaries(boolean novel) {
        MockHttpServletRequest request = guestRequest();
        GuestInviteSession session = GuestAccessGuard.extractSession(request);
        if (novel) {
            when(guestAccessGuard.isNovelVisibleToGuest(123L, session)).thenReturn(true);
            when(collectionService.novelMembershipsOf(List.of(123L))).thenReturn(Map.of(123L, List.of(7L, 8L)));
        } else {
            when(guestAccessGuard.isVisibleToGuest(123L, session)).thenReturn(true);
            when(collectionService.membershipsOf(List.of(123L))).thenReturn(Map.of(123L, List.of(7L, 8L)));
        }

        assertThat(memberships(novel, List.of(123L, 456L, 123L), request).memberships())
                .containsExactlyEntriesOf(Map.of(123L, List.of(8L)));
        if (novel) verify(collectionService).novelMembershipsOf(List.of(123L));
        else verify(collectionService).membershipsOf(List.of(123L));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("空列表和全部不可见作品不查询收藏成员关系")
    void guestEmptyMembershipsDoNotReadCollections(boolean novel) {
        MockHttpServletRequest request = guestRequest();
        assertThat(memberships(novel, List.of(), request).memberships()).isEmpty();
        assertThat(memberships(novel, List.of(456L), request).memberships()).isEmpty();
        verifyNoInteractions(collectionService, galleryRepository);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("访客批量输入在可见性检查前整体拒绝超限和非法 ID")
    void guestMembershipsRejectInvalidInputBeforeWork(boolean novel) {
        MockHttpServletRequest request = guestRequest();
        for (List<Long> ids : List.of(Collections.nCopies(501, 123L), List.of(0L),
                List.of(-1L), Collections.<Long>singletonList(null))) {
            assertThatThrownBy(() -> memberships(novel, ids, request))
                    .isInstanceOf(LocalizedException.class)
                    .satisfies(e -> assertThat(((LocalizedException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        }
        verifyNoInteractions(guestAccessGuard, collectionService, galleryRepository);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("访客上限边界可用且先去重再检查可见性")
    void guestMembershipsAllowBoundary(boolean novel) {
        MockHttpServletRequest request = guestRequest();
        assertThat(memberships(novel, Collections.nCopies(500, 123L), request).memberships()).isEmpty();
        GuestInviteSession session = GuestAccessGuard.extractSession(request);
        if (novel) verify(guestAccessGuard).isNovelVisibleToGuest(123L, session);
        else verify(guestAccessGuard).isVisibleToGuest(123L, session);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("管理员批量查询保持原有容量和完整收藏结果")
    void administratorMembershipsKeepExistingContract(boolean novel) {
        List<Long> ids = Collections.nCopies(501, 123L);
        Map<Long, List<Long>> expected = Map.of(123L, List.of(7L, 8L));
        if (novel) when(collectionService.novelMembershipsOf(ids)).thenReturn(expected);
        else when(collectionService.membershipsOf(ids)).thenReturn(expected);
        assertThat(memberships(novel, ids, new MockHttpServletRequest()).memberships()).isEqualTo(expected);
        verifyNoInteractions(guestAccessGuard, galleryRepository);
    }

    @Test
    @DisplayName("访客收藏列表分别裁剪插画与小说计数且保留纯小说收藏夹")
    void listCountsOnlyVisibleWorks() {
        List<Collection> stored = List.of(
                new Collection(1, "haha", null, "private/path", 0, 1, 2, 3),
                new Collection(2, "novels", null, null, 1, 2, 0, 4),
                new Collection(3, "empty", null, null, 2, 3, 2, 0),
                new Collection(7, "hidden", null, null, 3, 4, 5, 0));
        when(collectionService.listAll()).thenReturn(stored);
        when(galleryRepository.countVisibleWorksByCollection(any())).thenReturn(Map.of(1L, 1L, 7L, 5L));
        when(novelGalleryRepository.countVisibleWorksByCollection(any())).thenReturn(Map.of(2L, 1L));
        var collections = controller.list(guestRequest()).collections();
        assertThat(collections).extracting(Collection::id).containsExactly(1L, 2L);
        assertThat(collections.get(0).artworkCount()).isEqualTo(1);
        assertThat(collections.get(0).novelCount()).isZero();
        assertThat(collections.get(0).downloadRoot()).isNull();
        assertThat(collections.get(1).novelCount()).isEqualTo(1);
        assertThat(controller.list(new MockHttpServletRequest()).collections()).isEqualTo(stored);
    }

    @Test
    @DisplayName("单作品归属也隐藏未授权收藏夹")
    void singleMembershipsHideUnselectedCollections() {
        when(collectionService.collectionsOf(123L)).thenReturn(List.of(1L, 7L));
        when(collectionService.novelCollectionsOf(456L)).thenReturn(List.of(7L, 3L));
        assertThat(controller.collectionsOf(123L, guestRequest()).getBody().collectionIds()).containsExactly(1L);
        assertThat(controller.novelCollectionsOf(456L, guestRequest()).getBody().collectionIds()).containsExactly(3L);
    }

    private CollectionController.MembershipsResponse memberships(
            boolean novel, List<Long> ids, MockHttpServletRequest request) {
        return novel
                ? controller.novelMemberships(new CollectionController.NovelMembershipsRequest(ids), request).getBody()
                : controller.memberships(new CollectionController.ArtworkMembershipsRequest(ids), request).getBody();
    }

    private MockHttpServletRequest guestRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(GuestInviteSession.REQUEST_ATTR, new GuestInviteSession(
                1L, "invite-code", true, false, false,
                true, Set.of(), true, Set.of(),
                true, Set.of(), true, Set.of(), false, Set.of(1L, 2L, 3L, 8L), false
        ));
        return request;
    }
}
