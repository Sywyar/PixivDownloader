package top.sywyar.pixivdownload.setup;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import top.sywyar.pixivdownload.i18n.LocalizedException;
import top.sywyar.pixivdownload.plugin.api.web.ProgramIdentity;

/** 共享 HTTP 入口的目标授权；人类身份继续由原有路由和业务规则处理。 */
public final class ProgramAccess {
    private ProgramAccess() {}

    public static boolean permits(HttpServletRequest request, String owner, String permission) {
        return identity(request).map(identity -> identity.permits(owner == null ? "core" : owner, permission))
                .orElse(true);
    }

    public static java.util.Optional<ProgramIdentity> identity(HttpServletRequest request) {
        return ProgramIdentity.from(request.getAttribute(ProgramIdentity.REQUEST_ATTRIBUTE));
    }

    public static void require(HttpServletRequest request, String owner, String permission) {
        if (!permits(request, owner, permission)) throw new LocalizedException(HttpStatus.FORBIDDEN,
                "auth.program.forbidden", "The program key does not grant this operation");
    }
}
