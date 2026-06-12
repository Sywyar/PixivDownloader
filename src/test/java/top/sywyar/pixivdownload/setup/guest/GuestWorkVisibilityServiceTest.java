package top.sywyar.pixivdownload.setup.guest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import top.sywyar.pixivdownload.gallery.GuestRestriction;
import top.sywyar.pixivdownload.plugin.api.WorkRestriction;
import top.sywyar.pixivdownload.plugin.api.WorkType;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GuestWorkVisibilityServiceTest {

    private GuestAccessGuard guestAccessGuard;
    private GuestWorkVisibilityService service;
    private MockHttpServletRequest request;

    /**
     * 两侧白名单字段取值全部错开，投影用例可以分辨拿错媒体类型的回归。
     */
    private static final GuestInviteSession SESSION = new GuestInviteSession(
            1L, "code",
            true, false, true,
            false, Set.of(11L),
            true, Set.of(),
            true, Set.of(),
            false, Set.of(33L));

    @BeforeEach
    void setUp() {
        guestAccessGuard = mock(GuestAccessGuard.class);
        service = new GuestWorkVisibilityService(guestAccessGuard);
        request = new MockHttpServletRequest();
    }

    private void attachSession() {
        request.setAttribute(GuestInviteSession.REQUEST_ATTR, SESSION);
    }

    @Test
    @DisplayName("非访客请求：requireVisible 放行、isVisibleToGuest 恒真、restrictionFrom 为 null")
    void nonGuestRequestIsUnrestricted() {
        assertDoesNotThrow(() -> service.requireVisible(request, WorkType.ARTWORK, 42L));
        assertTrue(service.isVisibleToGuest(request, WorkType.ARTWORK, 42L));
        assertTrue(service.isVisibleToGuest(request, WorkType.NOVEL, 42L));
        assertNull(service.restrictionFrom(request, WorkType.ARTWORK));
        assertNull(service.restrictionFrom(request, WorkType.NOVEL));
        verify(guestAccessGuard, never()).isVisibleToGuest(42L, null);
        verify(guestAccessGuard, never()).isNovelVisibleToGuest(42L, null);
    }

    @Test
    @DisplayName("requireVisible 按 WorkType 路由到插画/小说两套守卫方法")
    void requireVisibleRoutesByWorkType() {
        service.requireVisible(request, WorkType.ARTWORK, 42L);
        verify(guestAccessGuard).requireVisible(request, 42L);
        verify(guestAccessGuard, never()).requireNovelVisible(request, 42L);

        service.requireVisible(request, WorkType.NOVEL, 43L);
        verify(guestAccessGuard).requireNovelVisible(request, 43L);
    }

    @Test
    @DisplayName("访客请求的 isVisibleToGuest 按 WorkType 委托守卫并透传判定结果")
    void isVisibleToGuestDelegatesByWorkType() {
        attachSession();
        when(guestAccessGuard.isVisibleToGuest(42L, SESSION)).thenReturn(false);
        when(guestAccessGuard.isNovelVisibleToGuest(42L, SESSION)).thenReturn(true);

        assertFalse(service.isVisibleToGuest(request, WorkType.ARTWORK, 42L));
        assertTrue(service.isVisibleToGuest(request, WorkType.NOVEL, 42L));
    }

    @Test
    @DisplayName("restrictionFrom 插画侧投影：年龄分级位图与 tagIds/authorIds 白名单")
    void restrictionFromProjectsArtworkSide() {
        attachSession();
        WorkRestriction restriction = service.restrictionFrom(request, WorkType.ARTWORK);

        assertEquals(Set.of(0, 2), restriction.allowedXRestricts());
        assertFalse(restriction.tagUnrestricted());
        assertEquals(List.of(11L), restriction.tagIds());
        assertTrue(restriction.authorUnrestricted());
        assertEquals(List.of(), restriction.authorIds());
        assertFalse(restriction.fullyOpen());
    }

    @Test
    @DisplayName("restrictionFrom 小说侧投影：取 novelTagIds/novelAuthorIds 两组白名单")
    void restrictionFromProjectsNovelSide() {
        attachSession();
        WorkRestriction restriction = service.restrictionFrom(request, WorkType.NOVEL);

        assertEquals(Set.of(0, 2), restriction.allowedXRestricts());
        assertTrue(restriction.tagUnrestricted());
        assertEquals(List.of(), restriction.tagIds());
        assertFalse(restriction.authorUnrestricted());
        assertEquals(List.of(33L), restriction.authorIds());
        assertFalse(restriction.fullyOpen());
    }

    @Test
    @DisplayName("restrictionFrom 投影与 GuestRestriction.from/forNovel 逐字段等价（防漂移对照）")
    void restrictionFromMatchesLegacyGuestRestriction() {
        attachSession();
        WorkRestriction artwork = service.restrictionFrom(request, WorkType.ARTWORK);
        GuestRestriction legacyArtwork = GuestRestriction.from(SESSION);
        assertEquals(legacyArtwork.allowedXRestricts(), artwork.allowedXRestricts());
        assertEquals(legacyArtwork.tagUnrestricted(), artwork.tagUnrestricted());
        assertEquals(legacyArtwork.tagIds(), artwork.tagIds());
        assertEquals(legacyArtwork.authorUnrestricted(), artwork.authorUnrestricted());
        assertEquals(legacyArtwork.authorIds(), artwork.authorIds());
        assertEquals(legacyArtwork.fullyOpen(), artwork.fullyOpen());

        WorkRestriction novel = service.restrictionFrom(request, WorkType.NOVEL);
        GuestRestriction legacyNovel = GuestRestriction.forNovel(SESSION);
        assertEquals(legacyNovel.allowedXRestricts(), novel.allowedXRestricts());
        assertEquals(legacyNovel.tagUnrestricted(), novel.tagUnrestricted());
        assertEquals(legacyNovel.tagIds(), novel.tagIds());
        assertEquals(legacyNovel.authorUnrestricted(), novel.authorUnrestricted());
        assertEquals(legacyNovel.authorIds(), novel.authorIds());
        assertEquals(legacyNovel.fullyOpen(), novel.fullyOpen());
    }
}
