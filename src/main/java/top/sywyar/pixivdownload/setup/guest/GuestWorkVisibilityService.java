package top.sywyar.pixivdownload.setup.guest;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.plugin.api.work.model.WorkRestriction;
import top.sywyar.pixivdownload.plugin.api.work.model.WorkType;
import top.sywyar.pixivdownload.plugin.api.work.service.WorkVisibilityService;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * {@link WorkVisibilityService} 的核心实现：代理 {@link GuestAccessGuard}，按
 * {@link WorkType} 路由到插画/小说两套守卫方法，判定语义与直接调用守卫完全一致。
 *
 * <p>{@link #restrictionFrom} 的投影逻辑与 {@code core.metadata.GuestRestriction#from} /
 * {@code forNovel} 等价；插件调用方切换到本接口后由这里统一承担派生。
 */
@Component
@RequiredArgsConstructor
public class GuestWorkVisibilityService implements WorkVisibilityService {

    private final GuestAccessGuard guestAccessGuard;

    @Override
    public void requireVisible(HttpServletRequest request, WorkType workType, long workId) {
        switch (workType) {
            case ARTWORK -> guestAccessGuard.requireVisible(request, workId);
            case NOVEL -> guestAccessGuard.requireNovelVisible(request, workId);
        }
    }

    @Override
    public boolean isVisibleToGuest(HttpServletRequest request, WorkType workType, long workId) {
        GuestInviteSession session = GuestAccessGuard.extractSession(request);
        if (session == null) return true;
        return switch (workType) {
            case ARTWORK -> guestAccessGuard.isVisibleToGuest(workId, session);
            case NOVEL -> guestAccessGuard.isNovelVisibleToGuest(workId, session);
        };
    }

    @Override
    public WorkRestriction restrictionFrom(HttpServletRequest request, WorkType workType) {
        GuestInviteSession session = GuestAccessGuard.extractSession(request);
        if (session == null) return null;
        return switch (workType) {
            case ARTWORK -> new WorkRestriction(
                    allowedRatings(session),
                    session.tagUnrestricted(),
                    List.copyOf(session.tagIds()),
                    session.authorUnrestricted(),
                    List.copyOf(session.authorIds()));
            case NOVEL -> new WorkRestriction(
                    allowedRatings(session),
                    session.novelTagUnrestricted(),
                    List.copyOf(session.novelTagIds()),
                    session.novelAuthorUnrestricted(),
                    List.copyOf(session.novelAuthorIds()));
        };
    }

    private static Set<Integer> allowedRatings(GuestInviteSession session) {
        Set<Integer> allowed = new LinkedHashSet<>();
        if (session.allowSfw()) allowed.add(0);
        if (session.allowR18()) allowed.add(1);
        if (session.allowR18g()) allowed.add(2);
        return Set.copyOf(allowed);
    }
}
