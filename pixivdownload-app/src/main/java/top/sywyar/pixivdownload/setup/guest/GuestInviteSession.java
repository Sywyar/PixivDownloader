package top.sywyar.pixivdownload.setup.guest;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

import java.util.Set;

/**
 * Snapshot of an invite guest session attached to the current request.
 */
public record GuestInviteSession(
        long id,
        String code,
        boolean allowSfw,
        boolean allowR18,
        boolean allowR18g,
        boolean tagUnrestricted,
        Set<Long> tagIds,
        boolean authorUnrestricted,
        Set<Long> authorIds,
        boolean novelTagUnrestricted,
        Set<Long> novelTagIds,
        boolean novelAuthorUnrestricted,
        Set<Long> novelAuthorIds,
        boolean collectionUnrestricted,
        Set<Long> collectionIds,
        boolean collectionRestrictsWorks) {

    public GuestInviteSession(long id, String code, boolean allowSfw, boolean allowR18, boolean allowR18g,
                              boolean tagUnrestricted, Set<Long> tagIds, boolean authorUnrestricted,
                              Set<Long> authorIds, boolean novelTagUnrestricted, Set<Long> novelTagIds,
                              boolean novelAuthorUnrestricted, Set<Long> novelAuthorIds) {
        this(id, code, allowSfw, allowR18, allowR18g, tagUnrestricted, tagIds, authorUnrestricted,
                authorIds, novelTagUnrestricted, novelTagIds, novelAuthorUnrestricted, novelAuthorIds,
                true, Set.of(), false);
    }

    public GuestInviteSession {
        collectionIds = Set.copyOf(collectionIds);
    }

    public boolean isCollectionVisible(long collectionId) {
        return collectionUnrestricted || collectionIds.contains(collectionId);
    }

    public static final String REQUEST_ATTR = "guestInvite";
    public static final String COOKIE_NAME = "pixiv_invite_token";

    /** 访客身份只能显式退出；邀请失效或解析失败不能恢复随请求携带的管理员权限。 */
    public static boolean isGuestRequest(HttpServletRequest request) {
        if (request.getAttribute(REQUEST_ATTR) instanceof GuestInviteSession) return true;
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (COOKIE_NAME.equals(cookie.getName()) && cookie.getValue() != null
                        && !cookie.getValue().isBlank()) return true;
            }
        }
        return false;
    }

    public boolean hasAnyAgeRating() {
        return allowSfw || allowR18 || allowR18g;
    }
}
