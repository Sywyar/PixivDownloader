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
import top.sywyar.pixivdownload.common.NetworkUtils;
import top.sywyar.pixivdownload.common.SessionUtils;
import top.sywyar.pixivdownload.common.UuidUtils;
import top.sywyar.pixivdownload.download.response.ErrorResponse;
import top.sywyar.pixivdownload.i18n.AppLocaleResolver;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.quota.RateLimitService;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
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
            "/api/gallery/",
            "/api/collections"
    );

    private final SetupService setupService;
    private final StaticResourceRateLimitService staticResourceRateLimitService;
    private final RateLimitService rateLimitService;
    private final AppLocaleResolver localeResolver;
    private final AppMessages messages;

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res,
                                    FilterChain chain) throws IOException, ServletException {
        String path = req.getRequestURI();
        String method = req.getMethod();

        if ("OPTIONS".equalsIgnoreCase(method)) {
            chain.doFilter(req, res);
            return;
        }

        if (path.equals("/redirect")) {
            String canvasParam = req.getParameter("canvas");
            boolean canvasSupported = "true".equalsIgnoreCase(canvasParam);
            if (setupService.isIntroMode()) {
                String target = canvasSupported ? "/intro-canary.html" : "/intro.html";
                res.sendRedirect(target);
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
                && !NetworkUtils.isLocalAddress(req.getRemoteAddr())) {
            sendTextError(req, res, 403, "auth.local-only", "Forbidden: local access only");
            return;
        }

        if (isPublic(path)) {
            chain.doFilter(req, res);
            return;
        }

        if (path.equals("/setup.html")) {
            if (!NetworkUtils.isLocalAddress(req.getRemoteAddr())) {
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

        if (isMonitorProtected(path)) {
            String token = SessionUtils.extractToken(req);
            if (!setupService.isValidSession(token)) {
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

        if (path.startsWith("/api/downloaded/") || path.equals("/api/download/status")) {
            if ("POST".equalsIgnoreCase(method) && path.contains("/downloaded/move/")) {
                if (!NetworkUtils.isLocalAddress(req.getRemoteAddr())) {
                    sendJsonError(req, res, 403, "auth.local-only", "Forbidden: local access only");
                    return;
                }
                chain.doFilter(req, res);
                return;
            }
            if (NetworkUtils.isLocalAddress(req.getRemoteAddr())) {
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
        return path.equals("/")
                || path.equals("/index")
                || path.equals("/login.html")
                || path.equals("/index.html")
                || path.equals("/intro.html")
                || path.equals("/intro-canary.html")
                || path.equals("/favicon.ico")
                || path.equals("/js/pixiv-i18n.js")
                || path.equals("/js/pixiv-theme.js")
                || path.startsWith("/api/setup/")
                || path.startsWith("/api/auth/")
                || path.startsWith("/api/i18n/")
                || path.startsWith("/api/gui/")
                || path.startsWith("/api/scripts/")
                || path.startsWith("/vendor/");
    }

    private boolean isSetupOnlyStaticResource(String path) {
        return path.equals("/js/pixiv-theme.js");
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
        return isStaticResource(path)
                && setupService.isSetupComplete()
                && "multi".equals(setupService.getMode())
                && !setupService.isAdminLoggedIn(req);
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
