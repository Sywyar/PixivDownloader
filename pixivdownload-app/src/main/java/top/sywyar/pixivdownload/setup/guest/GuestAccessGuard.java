package top.sywyar.pixivdownload.setup.guest;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.core.work.model.WorkType;
import top.sywyar.pixivdownload.core.work.service.WorkVisibilityService;

/**
 * 访客邀请的越界守卫。在单作品访问端点入口处调用 {@link #requireVisible(HttpServletRequest, long)}，
 * 越界（年龄分级超出 / 标签作者均不在白名单）抛 403。
 *
 * <p>请求未携带 GuestInviteSession 时直接放行（管理员或非访客访问不受此守卫影响）。
 */
@Component
@RequiredArgsConstructor
public class GuestAccessGuard {

    private final GuestWorkVisibilityScopeFactory scopeFactory;
    private final WorkVisibilityService workVisibilityService;

    /**
     * 抽出当前请求挂载的访客邀请会话；可能为 {@code null}。
     */
    public static GuestInviteSession extractSession(HttpServletRequest request) {
        return GuestWorkVisibilityScopeFactory.extractSession(request);
    }

    /**
     * 若当前请求是访客身份，校验作品是否在其可见范围内；越界抛 403。
     */
    public void requireVisible(HttpServletRequest request, long artworkId) {
        workVisibilityService.requireVisible(
                scopeFactory.fromRequest(request), WorkType.ARTWORK, artworkId);
    }

    /**
     * 单作品可见性判定：
     * 1) 年龄分级必须在允许集合内；
     * 2) OR 语义白名单：作品任一标签命中 {@code tagIds} 或作者在 {@code authorIds} 即可见。
     *    某维度 {@code unrestricted=true} 视为无限制。
     */
    public boolean isVisibleToGuest(long artworkId, GuestInviteSession session) {
        return workVisibilityService.isVisible(
                scopeFactory.fromSession(session), WorkType.ARTWORK, artworkId);
    }

    /**
     * 若当前请求是访客身份，校验小说是否在其可见范围内；越界抛 403。
     */
    public void requireNovelVisible(HttpServletRequest request, long novelId) {
        workVisibilityService.requireVisible(
                scopeFactory.fromRequest(request), WorkType.NOVEL, novelId);
    }

    /**
     * 单篇小说的可见性判定。规则与 {@link #isVisibleToGuest} 相同，但读取 {@code novels} 表与
     * {@code novel_tags} 表（标签 id 与插画共享 {@code tags} 池，所以 OR 语义与白名单可直接复用）。
     */
    public boolean isNovelVisibleToGuest(long novelId, GuestInviteSession session) {
        return workVisibilityService.isVisible(
                scopeFactory.fromSession(session), WorkType.NOVEL, novelId);
    }
}
