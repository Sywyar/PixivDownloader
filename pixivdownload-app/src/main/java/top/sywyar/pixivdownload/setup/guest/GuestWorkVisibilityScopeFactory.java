package top.sywyar.pixivdownload.setup.guest;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.core.work.model.WorkRestriction;
import top.sywyar.pixivdownload.core.work.model.WorkVisibilityScope;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 把宿主已验证的邀请会话投影为不携带 Servlet 或会话实现的作品可见性作用域。 */
@Component
public class GuestWorkVisibilityScopeFactory {

    public WorkVisibilityScope fromRequest(HttpServletRequest request) {
        return fromSession(extractSession(request));
    }

    WorkVisibilityScope fromSession(GuestInviteSession session) {
        if (session == null) {
            return WorkVisibilityScope.unrestricted();
        }
        return WorkVisibilityScope.restricted(
                new WorkRestriction(
                        allowedRatings(session),
                        session.tagUnrestricted(),
                        List.copyOf(session.tagIds()),
                        session.authorUnrestricted(),
                        List.copyOf(session.authorIds())),
                new WorkRestriction(
                        allowedRatings(session),
                        session.novelTagUnrestricted(),
                        List.copyOf(session.novelTagIds()),
                        session.novelAuthorUnrestricted(),
                        List.copyOf(session.novelAuthorIds())));
    }

    static GuestInviteSession extractSession(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        Object attribute = request.getAttribute(GuestInviteSession.REQUEST_ATTR);
        return attribute instanceof GuestInviteSession session ? session : null;
    }

    private static Set<Integer> allowedRatings(GuestInviteSession session) {
        Set<Integer> allowed = new LinkedHashSet<>();
        if (session.allowSfw()) allowed.add(0);
        if (session.allowR18()) allowed.add(1);
        if (session.allowR18g()) allowed.add(2);
        return Set.copyOf(allowed);
    }
}
