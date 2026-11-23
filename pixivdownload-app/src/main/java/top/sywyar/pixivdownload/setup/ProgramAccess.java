package top.sywyar.pixivdownload.setup;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import top.sywyar.pixivdownload.i18n.LocalizedException;
import top.sywyar.pixivdownload.plugin.api.web.AccessPolicy;
import top.sywyar.pixivdownload.plugin.api.web.HttpMethod;
import top.sywyar.pixivdownload.plugin.api.web.ProgramIdentity;
import top.sywyar.pixivdownload.plugin.api.web.ProgramPermission;
import top.sywyar.pixivdownload.plugin.api.web.WebRouteContribution;
import top.sywyar.pixivdownload.plugin.registry.route.RouteAccessRegistry;

import java.util.Map;
import java.util.Set;

/** 共享 HTTP 入口的目标授权；人类身份继续由原有路由和业务规则处理。 */
public final class ProgramAccess {
    public static final String SELF_PATH = "/api/security/program-keys/self";
    public static final WebRouteContribution SELF_ROUTE = new WebRouteContribution(SELF_PATH, AccessPolicy.ADMIN,
            Set.of(HttpMethod.GET), false).withProgramPermissions(Map.of(
                    HttpMethod.GET, new ProgramPermission("program-key.self", "auth.program.required")));

    private ProgramAccess() {}

    /** An authenticated key can inspect itself even if it has no business grants. */
    public static boolean isSelf(RouteAccessRegistry.RegisteredRoute route, HttpMethod method) {
        return method == HttpMethod.GET && "core".equals(route.pluginId()) && SELF_ROUTE.equals(route.route());
    }

    public static boolean permits(HttpServletRequest request, String owner, String permission) {
        return identity(request).map(identity -> identity.permits(permissionOwner(owner), permission))
                .orElse(true);
    }

    private static String permissionOwner(String owner) { return owner == null ? "core" : owner; }

    public static java.util.Optional<ProgramIdentity> identity(HttpServletRequest request) {
        return ProgramIdentity.from(request.getAttribute(ProgramIdentity.REQUEST_ATTRIBUTE));
    }

    public static void require(HttpServletRequest request, String owner, String permission) {
        require(request, owner, permission, () -> true);
    }

    /** A caller-supplied target may be named in the denial only after its owner has been confirmed. */
    public static void require(HttpServletRequest request, String owner, String permission,
                               java.util.function.BooleanSupplier knownTarget) {
        if (permits(request, owner, permission)) return;
        String id = permissionOwner(owner) + ":" + permission;
        if (!ProgramKeyStore.isPermissionId(id)) throw forbidden();
        boolean confirmed;
        try {
            confirmed = knownTarget.getAsBoolean();
        } catch (RuntimeException unavailable) {
            // Failure to resolve diagnostic metadata must leave the original permission denial in force.
            org.slf4j.LoggerFactory.getLogger(ProgramAccess.class).debug("Permission target lookup failed ({})",
                    unavailable.getClass().getSimpleName());
            throw forbidden();
        }
        if (confirmed) throw new MissingPermission(id);
        throw forbidden();
    }

    public static LocalizedException forbidden() {
        return new LocalizedException(HttpStatus.FORBIDDEN, "auth.program.forbidden",
                "The program key does not grant this operation");
    }

    public static final class MissingPermission extends LocalizedException {
        private final String permission;

        public MissingPermission(String owner, String permission) {
            this(permissionOwner(owner) + ":" + permission);
        }

        public MissingPermission(String id) {
            super(HttpStatus.FORBIDDEN, "auth.program.forbidden.permission",
                    "This program key lacks the {0} permission required for this operation", id);
            this.permission = id;
        }

        public String permission() { return permission; }
    }
}
