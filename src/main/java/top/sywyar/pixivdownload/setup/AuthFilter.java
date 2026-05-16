package top.sywyar.pixivdownload.setup;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.beans.factory.ObjectProvider;
import top.sywyar.pixivdownload.common.NetworkUtils;
import top.sywyar.pixivdownload.common.SessionUtils;
import top.sywyar.pixivdownload.common.UuidUtils;
import top.sywyar.pixivdownload.download.response.ErrorResponse;
import top.sywyar.pixivdownload.gui.GuiTokenHolder;
import top.sywyar.pixivdownload.gui.GuiTokenService;
import top.sywyar.pixivdownload.i18n.AppLocaleResolver;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.maintenance.MaintenanceCoordinator;
import top.sywyar.pixivdownload.quota.RateLimitService;
import top.sywyar.pixivdownload.setup.guest.GuestInviteService;
import top.sywyar.pixivdownload.setup.guest.GuestInviteSession;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
@Order(1)
@Slf4j
@RequiredArgsConstructor
public class AuthFilter extends OncePerRequestFilter {

    private static final Set<String> MONITOR_EXACT_PATHS = Set.of(
            "/monitor.html",
            "/pixiv-gallery.html",
            "/pixiv-artwork.html",
            "/pixiv-showcase.html",
            "/pixiv-novel.html",
            "/pixiv-novel-gallery.html",
            "/pixiv-series.html",
            "/pixiv-invite-manage.html",
            "/pixiv-invite-detail.html",
            "/api/downloaded/statistics",
            "/api/downloaded/history",
            "/api/downloaded/history/paged",
            "/api/downloaded/batch",
            "/api/downloaded/by-move-folder",
            "/api/download/status/active"
    );

    private static final List<String> MONITOR_PREFIX_PATHS = List.of(
            "/api/downloaded/thumbnail/",
            "/api/downloaded/rawfile/",
            "/api/downloaded/image/",
            "/api/authors",
            "/api/series",
            "/api/gallery/",
            "/api/collections",
            "/api/admin/",
            "/monitor/",
            "/pixiv-gallery/",
            "/pixiv-artwork/",
            "/pixiv-showcase/",
            "/pixiv-novel/",
            "/pixiv-novel-gallery/",
            "/pixiv-series/",
            "/pixiv-invite-manage/",
            "/pixiv-invite-detail/"
    );

    private static final List<String> PUBLIC_PAGE_STATIC_PREFIX_PATHS = List.of(
            "/index/",
            "/intro/",
            "/intro-canary/",
            "/login/",
            "/vendor/fonts/"
    );

    private static final Set<String> PUBLIC_STATIC_EXACT_PATHS = Set.of(
            "/favicon.ico",
            "/js/pixiv-i18n.js",
            "/js/pixiv-lang-switcher.js",
            "/js/pixiv-theme.js"
    );

    private static final Set<String> GUEST_ALLOWED_STATIC_EXACT = Set.of(
            "/css/admin-visibility.css",
            "/css/lang-theme-switcher.css",
            "/js/invite-modals.js",
            "/js/pixiv-i18n.js",
            "/js/pixiv-lang-switcher.js",
            "/js/pixiv-novel-render.js",
            "/js/pixiv-theme.js"
    );

    /** 访客邀请会话被允许访问的精确路径。 */
    private static final Set<String> GUEST_ALLOWED_EXACT = Set.of(
            "/pixiv-gallery.html",
            "/pixiv-artwork.html",
            "/pixiv-showcase.html",
            "/pixiv-novel.html",
            "/pixiv-novel-gallery.html",
            "/pixiv-series.html",
            "/api/downloaded/statistics",
            "/api/downloaded/history",
            "/api/downloaded/history/paged",
            "/api/downloaded/by-move-folder",
            "/api/download/status/active"
    );

    /** 访客邀请会话被允许访问的前缀路径（仅 GET）。 */
    private static final List<String> GUEST_ALLOWED_PREFIX = List.of(
            "/api/downloaded/thumbnail/",
            "/api/downloaded/rawfile/",
            "/api/downloaded/image/",
            "/api/download/status/",
            "/api/authors",
            "/api/series",
            "/api/gallery/",
            "/api/collections",
            "/api/pixiv/artwork/",
            "/api/pixiv/novel/",
            "/pixiv-gallery/",
            "/pixiv-artwork/",
            "/pixiv-showcase/",
            "/pixiv-novel/",
            "/pixiv-novel-gallery/",
            "/pixiv-series/"
    );

    /** 访客邀请 cookie 名（浏览器会话 cookie，不带 Max-Age）。 */
    public static final String INVITE_COOKIE = "pixiv_invite_token";

    private final SetupService setupService;
    private final StaticResourceRateLimitService staticResourceRateLimitService;
    private final RateLimitService rateLimitService;
    private final AppLocaleResolver localeResolver;
    private final AppMessages messages;
    private final ObjectProvider<MaintenanceCoordinator> maintenanceCoordinatorProvider;
    private final GuestInviteService guestInviteService;
    private final GuiTokenService guiTokenService;

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res,
                                    FilterChain chain) throws IOException, ServletException {
        String path = req.getRequestURI();
        String method = req.getMethod();

        if ("OPTIONS".equalsIgnoreCase(method)) {
            chain.doFilter(req, res);
            return;
        }

        // GUI 路径：必须同时满足本地请求 + 有效的 GUI 令牌，通过后跳过所有后续过滤逻辑。
        // 置于维护检查之前，确保 GUI 在维护窗口内仍可操控后端。
        if (path.startsWith("/api/gui/")) {
            if (!isValidGuiRequest(req)) {
                sendJsonError(req, res, 403, "auth.local-only", "Forbidden: local access only");
                return;
            }
            chain.doFilter(req, res);
            return;
        }

        // 维护窗口：非本地管理员一律 503（避免维护中错改数据）
        MaintenanceCoordinator maintenance = maintenanceCoordinatorProvider.getIfAvailable();
        if (maintenance != null && maintenance.isPaused()
                && !(NetworkUtils.isLocalRequest(req)
                        && setupService.isAdminLoggedIn(req))) {
            res.setStatus(503);
            res.setHeader(HttpHeaders.RETRY_AFTER, "60");
            res.setContentType(MediaType.APPLICATION_JSON_VALUE);
            res.setCharacterEncoding(StandardCharsets.UTF_8.name());
            String message = messages.getOrDefault(localeResolver.resolveLocale(req),
                    "auth.maintenance", "服务正在维护，请稍后再试");
            res.getWriter().write(new ObjectMapper()
                    .writeValueAsString(new ErrorResponse(message)));
            return;
        }

        if (path.equals("/redirect")) {
            if (setupService.isIntroMode()) {
                res.sendRedirect("/intro.html");
            } else if ("multi".equals(setupService.getMode())) {
                res.sendRedirect("/pixiv-batch.html");
            } else {
                res.sendRedirect("/pixiv-gallery.html");
            }
            return;
        }

        if (shouldApplyStaticResourceRateLimit(req, path)) {
            if (!staticResourceRateLimitService.isAllowed(req.getRemoteAddr())) {
                log.warn(messages.getForLog("static-resource.log.rate-limit.exceeded", req.getRemoteAddr(), path));
                sendTextError(req, res, 429, "auth.too-many-requests", "Too Many Requests");
                return;
            }
        }

        if (isSetupOnlyStaticResource(path)
                && !setupService.isSetupComplete()
                && !NetworkUtils.isLocalRequest(req)) {
            sendTextError(req, res, 403, "auth.local-only", "Forbidden: local access only");
            return;
        }

        // 邀请兑换通过 GET /invite?code=...：服务端尝试发 cookie 并 302 到画廊
        if (path.equals("/invite")) {
            handleInviteRedeemRedirect(req, res);
            return;
        }

        if (isPublic(path)) {
            chain.doFilter(req, res);
            return;
        }

        if (isSetupPagePath(path)) {
            if (!NetworkUtils.isLocalRequest(req)) {
                sendJsonError(req, res, 403, "auth.local-only", "Forbidden: local access only");
                return;
            }
            chain.doFilter(req, res);
            return;
        }

        if (!setupService.isSetupComplete()) {
            if (isApi(path)) {
                sendJsonError(req, res, 503, "auth.setup-required", "Setup required");
            } else {
                res.sendRedirect("/setup.html");
            }
            return;
        }

        // 解析访客邀请会话（若 cookie 有效）：挂到 request attribute，用于后续过滤与单作品守卫
        GuestInviteSession guestSession = resolveGuestInviteSession(req, res);
        if (guestSession != null) {
            req.setAttribute(GuestInviteSession.REQUEST_ATTR, guestSession);
        }

        if (guestSession != null && isAllowedForGuestInvite(path, method)) {
            if (isApi(path)) {
                String key = rateLimitService.resolveLimitKey(req);
                if (!rateLimitService.isAllowed(key)) {
                    sendJsonError(req, res, 429, "auth.too-many-requests", "Too Many Requests");
                    return;
                }
            }
            guestInviteService.recordHit(guestSession.id());
            chain.doFilter(req, res);
            return;
        }

        if (isMonitorProtected(path)) {
            String token = SessionUtils.extractToken(req);
            boolean adminValid = setupService.isValidSession(token);
            if (!adminValid) {
                if (guestSession != null) {
                    // guest 携带 cookie 但越界：禁止访问
                    sendJsonError(req, res, 403, "guest.invite.forbidden",
                            "该资源不在你的可见范围内");
                    return;
                }
                if (isApi(path)) {
                    sendJsonError(req, res, 401, "auth.unauthorized", "Unauthorized");
                } else {
                    String redirect = URLEncoder.encode(path, StandardCharsets.UTF_8);
                    res.sendRedirect("/login.html?redirect=" + redirect);
                }
                return;
            }
            if ("multi".equals(setupService.getMode())) {
                ensureUserUuidCookie(req, res);
            }
            chain.doFilter(req, res);
            return;
        }

        // 已识别为访客但未命中受保护路径（即非 monitor 范围内）：禁止越界（除非是 isPublic 路径，已在前面放行）
        if (guestSession != null) {
            sendJsonError(req, res, 403, "guest.invite.forbidden",
                    "该资源不在你的可见范围内");
            return;
        }

        if (path.startsWith("/api/downloaded/") || path.equals("/api/download/status")) {
            if ("POST".equalsIgnoreCase(method) && path.contains("/downloaded/move/")) {
                if (!NetworkUtils.isLocalRequest(req)) {
                    sendJsonError(req, res, 403, "auth.local-only", "Forbidden: local access only");
                    return;
                }
                chain.doFilter(req, res);
                return;
            }
            if (NetworkUtils.isLocalRequest(req)) {
                chain.doFilter(req, res);
                return;
            }
        }

        if ("multi".equals(setupService.getMode())) {
            boolean isAdmin = setupService.isAdminLoggedIn(req);
            if (!isAdmin && isApi(path)) {
                String uuid = UuidUtils.extractOrGenerateUuid(req);
                if (!rateLimitService.isAllowed(uuid)) {
                    sendJsonError(req, res, 429, "auth.too-many-requests", "Too Many Requests");
                    return;
                }
            }
            ensureUserUuidCookie(req, res);
            chain.doFilter(req, res);
            return;
        }

        String token = SessionUtils.extractToken(req);
        if (setupService.isValidSession(token)) {
            chain.doFilter(req, res);
        } else if (isApi(path)) {
            sendJsonError(req, res, 401, "auth.unauthorized", "Unauthorized");
        } else {
            String redirect = URLEncoder.encode(path, StandardCharsets.UTF_8);
            res.sendRedirect("/login.html?redirect=" + redirect);
        }
    }

    private boolean isMonitorProtected(String path) {
        if (MONITOR_EXACT_PATHS.contains(path)) {
            return true;
        }
        for (String prefix : MONITOR_PREFIX_PATHS) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private boolean isPublic(String path) {
        if (isAlwaysPublicApi(path) || path.equals("/invite")) {
            return true;
        }
        if (setupService.isSetupComplete() && "solo".equals(setupService.getMode())) {
            return isSoloPublicPath(path);
        }
        return isDefaultPublicPath(path);
    }

    private boolean isAlwaysPublicApi(String path) {
        return path.startsWith("/api/auth/")
                || path.startsWith("/api/i18n/");
    }

    private boolean isValidGuiRequest(HttpServletRequest req) {
        if (!NetworkUtils.isTrustedLocalRequest(req)) {
            return false;
        }
        String token = guiTokenService.getToken();
        if (token == null) {
            return false;
        }
        return token.equals(req.getHeader(GuiTokenHolder.HEADER_NAME));
    }

    private boolean isDefaultPublicPath(String path) {
        return path.equals("/")
                || path.equals("/index")
                || path.equals("/login.html")
                || path.equals("/index.html")
                || path.equals("/intro.html")
                || path.equals("/intro-canary.html")
                || PUBLIC_STATIC_EXACT_PATHS.contains(path)
                || isPublicPageStaticResource(path)
                || path.startsWith("/api/setup/")
                || path.startsWith("/api/auth/")
                || path.startsWith("/api/i18n/")
                || path.startsWith("/api/scripts/")
                || path.equals("/invite");
    }

    private boolean isSoloPublicPath(String path) {
        return isIntroLoginPublicPageOrResource(path)
                || path.equals("/invite");
    }

    private boolean isIntroLoginPublicPageOrResource(String path) {
        return path.equals("/")
                || path.equals("/index")
                || path.equals("/index.html")
                || path.equals("/login.html")
                || path.equals("/intro.html")
                || path.equals("/intro-canary.html")
                || PUBLIC_STATIC_EXACT_PATHS.contains(path)
                || isPublicPageStaticResource(path);
    }

    private boolean isSetupOnlyStaticResource(String path) {
        return path.equals("/js/pixiv-lang-switcher.js")
                || path.equals("/js/pixiv-theme.js")
                || path.startsWith("/setup/");
    }

    private boolean isPublicPageStaticResource(String path) {
        for (String prefix : PUBLIC_PAGE_STATIC_PREFIX_PATHS) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private boolean isSetupPagePath(String path) {
        return path.equals("/setup.html") || path.startsWith("/setup/");
    }

    private boolean isApi(String path) {
        return path.startsWith("/api/");
    }

    private boolean isStaticResource(String path) {
        if (path == null || path.isBlank() || path.equals("/redirect") || path.startsWith("/api/")) {
            return false;
        }
        if (path.equals("/") || path.equals("/index")
                || path.startsWith("/js/")
                || path.startsWith("/vendor/")
                || path.startsWith("/userscripts/")) {
            return true;
        }
        int lastSlash = path.lastIndexOf('/');
        int lastDot = path.lastIndexOf('.');
        return lastDot > lastSlash;
    }

    private boolean shouldApplyStaticResourceRateLimit(HttpServletRequest req, String path) {
        if (!isStaticResource(path) && !path.equals("/invite")) {
            return false;
        }
        if (!setupService.isSetupComplete() || setupService.isAdminLoggedIn(req)) {
            return false;
        }
        String mode = setupService.getMode();
        if ("multi".equals(mode)) {
            return isStaticResource(path);
        }
        if ("solo".equals(mode)) {
            return isSoloRateLimitedPublicResource(path);
        }
        return false;
    }

    private boolean isSoloRateLimitedPublicResource(String path) {
        return isIntroLoginPublicPageOrResource(path)
                || path.equals("/invite")
                || isGuestPublicPageOrStaticResource(path);
    }

    private boolean isGuestPublicPageOrStaticResource(String path) {
        if (GUEST_ALLOWED_STATIC_EXACT.contains(path)) {
            return true;
        }
        if (!isStaticResource(path)) {
            return false;
        }
        if (GUEST_ALLOWED_EXACT.contains(path)) {
            return true;
        }
        for (String prefix : GUEST_ALLOWED_PREFIX) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private void sendJsonError(HttpServletRequest req, HttpServletResponse res,
                               int status, String messageCode, String defaultMessage) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        String message = messages.getOrDefault(localeResolver.resolveLocale(req), messageCode, defaultMessage);
        res.setStatus(status);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding(StandardCharsets.UTF_8.name());
        res.getWriter().write(mapper.writeValueAsString(new ErrorResponse(message)));
    }

    private void sendTextError(HttpServletRequest req, HttpServletResponse res,
                               int status, String messageCode, String defaultMessage) throws IOException {
        String message = messages.getOrDefault(localeResolver.resolveLocale(req), messageCode, defaultMessage);
        res.setStatus(status);
        res.setContentType(MediaType.TEXT_PLAIN_VALUE);
        res.setCharacterEncoding(StandardCharsets.UTF_8.name());
        res.setHeader(HttpHeaders.RETRY_AFTER, "60");
        res.getWriter().write(message);
    }

    private GuestInviteSession resolveGuestInviteSession(HttpServletRequest req, HttpServletResponse res) {
        Cookie[] cookies = req.getCookies();
        if (cookies == null) return null;
        String code = null;
        for (Cookie c : cookies) {
            if (INVITE_COOKIE.equals(c.getName()) && c.getValue() != null && !c.getValue().isBlank()) {
                code = c.getValue();
                break;
            }
        }
        if (code == null) return null;
        Optional<GuestInviteSession> resolved;
        try {
            resolved = guestInviteService.resolveByCode(code);
        } catch (Exception e) {
            log.warn("Failed to resolve invite cookie: {}", e.getMessage());
            return null;
        }
        if (resolved.isPresent()) return resolved.get();
        // 失效：让浏览器丢掉无效的 cookie
        ResponseCookie cleared = ResponseCookie.from(INVITE_COOKIE, "")
                .path("/").httpOnly(true).sameSite("Strict").maxAge(0).build();
        res.addHeader(HttpHeaders.SET_COOKIE, cleared.toString());
        return null;
    }

    private boolean isAllowedForGuestInvite(String path, String method) {
        if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
            return false;
        }
        if (GUEST_ALLOWED_STATIC_EXACT.contains(path)) return true;
        if (GUEST_ALLOWED_EXACT.contains(path)) return true;
        for (String prefix : GUEST_ALLOWED_PREFIX) {
            if (path.startsWith(prefix)) return true;
        }
        return false;
    }

    private void handleInviteRedeemRedirect(HttpServletRequest req, HttpServletResponse res) throws IOException {
        String code = req.getParameter("code");
        if (code == null || code.isBlank()) {
            res.sendRedirect("/login.html");
            return;
        }
        Optional<GuestInviteSession> session;
        try {
            session = guestInviteService.resolveByCode(code);
        } catch (Exception e) {
            log.warn("Invite redeem (GET) failed: {}", e.getMessage());
            res.sendRedirect("/login.html?inviteError=1");
            return;
        }
        if (session.isEmpty()) {
            res.sendRedirect("/login.html?inviteError=1");
            return;
        }
        ResponseCookie cookie = ResponseCookie.from(INVITE_COOKIE, session.get().code())
                .path("/").httpOnly(true).sameSite("Strict").build();
        res.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        res.sendRedirect("/pixiv-gallery.html");
    }

    private void ensureUserUuidCookie(HttpServletRequest req, HttpServletResponse res) {
        Cookie[] cookies = req.getCookies();
        if (cookies != null) {
            for (Cookie c : cookies) {
                if ("pixiv_user_id".equals(c.getName()) && c.getValue() != null
                        && !c.getValue().isBlank()) {
                    return;
                }
            }
        }

        String uuid = UuidUtils.extractOrGenerateUuid(req);
        ResponseCookie cookie = ResponseCookie.from("pixiv_user_id", uuid)
                .path("/")
                .maxAge(Duration.ofDays(30))
                .sameSite("Strict")
                .httpOnly(true)
                .build();
        res.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
