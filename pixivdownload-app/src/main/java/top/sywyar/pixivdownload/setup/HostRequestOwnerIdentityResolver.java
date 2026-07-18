package top.sywyar.pixivdownload.setup;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.common.UuidUtils;
import top.sywyar.pixivdownload.plugin.api.web.RequestOwnerIdentity;
import top.sywyar.pixivdownload.plugin.api.web.RequestOwnerIdentityResolver;
import top.sywyar.pixivdownload.setup.guest.GuestInviteSession;

import java.util.Objects;
import java.util.Optional;

/** 基于宿主模式 / 管理员会话与 UUID 规则的可信请求 owner 解析器。 */
@Component
@RequiredArgsConstructor
public class HostRequestOwnerIdentityResolver implements RequestOwnerIdentityResolver {

    private final SetupService setupService;

    @Override
    public RequestOwnerIdentity resolve(HttpServletRequest request) {
        Objects.requireNonNull(request, "request");
        if (setupService.hasAdminScope(request)) {
            return RequestOwnerIdentity.adminScope();
        }
        return RequestOwnerIdentity.owner(UuidUtils.extractOrGenerateUuid(request));
    }

    @Override
    public Optional<String> resolveExistingOwnerUuid(HttpServletRequest request) {
        Objects.requireNonNull(request, "request");
        return Optional.ofNullable(UuidUtils.extractExistingUuid(request));
    }

    @Override
    public Optional<String> resolveInvitedGuestRateLimitSubject(HttpServletRequest request) {
        Objects.requireNonNull(request, "request");
        if (setupService.isAdminLoggedIn(request)) {
            return Optional.empty();
        }
        Object attribute = request.getAttribute(GuestInviteSession.REQUEST_ATTR);
        return attribute instanceof GuestInviteSession session
                ? Optional.of("invite:" + session.id())
                : Optional.empty();
    }

    @Override
    public boolean isAdminAuthenticated(HttpServletRequest request) {
        Objects.requireNonNull(request, "request");
        return setupService.isAdminLoggedIn(request);
    }
}
