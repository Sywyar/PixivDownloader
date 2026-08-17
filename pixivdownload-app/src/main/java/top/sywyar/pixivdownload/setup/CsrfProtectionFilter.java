package top.sywyar.pixivdownload.setup;

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
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import top.sywyar.pixivdownload.web.ApiErrorWriter;
import top.sywyar.pixivdownload.common.web.SafeRequestPath;
import top.sywyar.pixivdownload.i18n.AppLocaleResolver;
import top.sywyar.pixivdownload.i18n.AppMessages;

import java.io.IOException;
import java.net.URI;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@Component
@Order(2)
@Slf4j
@RequiredArgsConstructor
public class CsrfProtectionFilter extends OncePerRequestFilter {

    private static final Pattern COLLECTION_ICON_PATH = Pattern.compile("^/api/collections/\\d+/icon$");
    private static final Pattern PLUGIN_MARKET_INSTALL_PATH =
            Pattern.compile("^/api/plugin-market/[^/]+/[^/]+/[^/]+/install$");
    private static final Pattern USERSCRIPT_WRITE_PATH = Pattern.compile(
            "^/api/(?:download/pixiv|download/status|download/queue/[^/]+/cancel|download/queue/clear"
                    + "|sse/close/aggregated/[^/]+|quota/(?:init|pack)|batch/state|novel/download"
                    + "|novel/browser-import/[0-9]+|novel/series/[^/]+/merge"
                    + "|downloaded/batch|gallery/novels/downloaded-batch)$");
    private static final String SESSION_COOKIE = "pixiv_session";
    private static final String VISITOR_COOKIE = "pixiv_user_id";

    private final AppLocaleResolver localeResolver;
    private final AppMessages messages;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if ("TRACE".equalsIgnoreCase(request.getMethod())) {
            response.setHeader(HttpHeaders.ALLOW, "GET, HEAD, OPTIONS, POST, PUT, DELETE, PATCH");
            if (prefersHtmlErrorPage(request)) {
                response.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
            } else {
                String message = messages.getOrDefault(localeResolver.resolveLocale(request),
                        "http.status.405", "Method Not Allowed");
                ApiErrorWriter.write(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
                        "http.status.405", message);
            }
            return;
        }
        if (!requiresSameOriginCheck(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        if (hasSameOriginSignal(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        if (isTrustedUserscriptSource(request)
                && isUserscriptWriteEndpoint(request)
                && !hasAmbientCredential(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        if (!requiresExplicitOriginSignal(request)
                && !hasBrowserOriginSignal(request)
                && !hasAmbientCredential(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        log.warn("Rejected cross-origin write request: method={}, path={}, origin={}, referer={}",
                request.getMethod(), request.getRequestURI(),
                request.getHeader(HttpHeaders.ORIGIN), request.getHeader(HttpHeaders.REFERER));
        sendJsonError(request, response);
    }

    static boolean requiresSameOriginCheck(HttpServletRequest request) {
        String method = request.getMethod();
        if (method == null) {
            return false;
        }
        String normalizedMethod = method.toUpperCase(Locale.ROOT);
        return !Set.of("GET", "HEAD", "OPTIONS").contains(normalizedMethod);
    }

    private static boolean requiresExplicitOriginSignal(HttpServletRequest request) {
        String method = request.getMethod();
        String path = SafeRequestPath.resolve(request).orElse(null);
        if (method == null || path == null) {
            return false;
        }
        String normalizedMethod = method.toUpperCase(Locale.ROOT);

        if ((path.equals("/api/schedule") || path.startsWith("/api/schedule/"))
                && ("POST".equals(normalizedMethod)
                || "PUT".equals(normalizedMethod)
                || "DELETE".equals(normalizedMethod))) {
            return true;
        }

        if (("POST".equals(normalizedMethod) || "PUT".equals(normalizedMethod))
                && path.startsWith("/api/plugins/")) {
            return true;
        }
        if ("POST".equals(normalizedMethod) && PLUGIN_MARKET_INSTALL_PATH.matcher(path).matches()) {
            return true;
        }
        if (("POST".equals(normalizedMethod) || "DELETE".equals(normalizedMethod))
                && COLLECTION_ICON_PATH.matcher(path).matches()) {
            return true;
        }
        if ("POST".equals(normalizedMethod)
                && (path.equals("/api/narration/cast/voice/reference")
                || path.equals("/api/narration/cast/voice/reference/generate"))) {
            return true;
        }
        return "DELETE".equals(normalizedMethod)
                && path.equals("/api/narration/cast/voice/reference");
    }

    private static boolean hasBrowserOriginSignal(HttpServletRequest request) {
        return StringUtils.hasText(request.getHeader(HttpHeaders.ORIGIN))
                || StringUtils.hasText(request.getHeader(HttpHeaders.REFERER))
                || StringUtils.hasText(request.getHeader("Sec-Fetch-Site"));
    }

    private static boolean hasAmbientCredential(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return false;
        }
        for (Cookie cookie : cookies) {
            if (SESSION_COOKIE.equals(cookie.getName())
                    || AuthFilter.INVITE_COOKIE.equals(cookie.getName())
                    || VISITOR_COOKIE.equals(cookie.getName())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isTrustedUserscriptSource(HttpServletRequest request) {
        String source = request.getHeader(HttpHeaders.ORIGIN);
        if (!StringUtils.hasText(source)) {
            source = request.getHeader(HttpHeaders.REFERER);
        }
        if (!StringUtils.hasText(source)) {
            return false;
        }
        try {
            URI uri = URI.create(source);
            return "https".equalsIgnoreCase(uri.getScheme())
                    && ("www.pixiv.net".equalsIgnoreCase(uri.getHost())
                    || "pixiv.net".equalsIgnoreCase(uri.getHost()));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean isUserscriptWriteEndpoint(HttpServletRequest request) {
        return SafeRequestPath.resolve(request)
                .map(path -> USERSCRIPT_WRITE_PATH.matcher(path).matches())
                .orElse(false);
    }

    private static boolean hasSameOriginSignal(HttpServletRequest request) {
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        if (StringUtils.hasText(origin)) {
            return isSameOrigin(request, origin);
        }
        String referer = request.getHeader(HttpHeaders.REFERER);
        if (StringUtils.hasText(referer)) {
            return isSameOrigin(request, referer);
        }
        return false;
    }

    private static boolean isSameOrigin(HttpServletRequest request, String source) {
        URI sourceUri;
        try {
            sourceUri = URI.create(source);
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (!StringUtils.hasText(sourceUri.getScheme()) || !StringUtils.hasText(sourceUri.getHost())) {
            return false;
        }

        String expectedScheme = request.getScheme();
        String expectedHost = request.getServerName();
        int expectedPort = effectivePort(expectedScheme, request.getServerPort());
        int actualPort = effectivePort(sourceUri.getScheme(), sourceUri.getPort());

        return sourceUri.getScheme().equalsIgnoreCase(expectedScheme)
                && sourceUri.getHost().equalsIgnoreCase(expectedHost)
                && actualPort == expectedPort;
    }

    private static int effectivePort(String scheme, int port) {
        if (port >= 0) {
            return port;
        }
        if ("http".equalsIgnoreCase(scheme)) {
            return 80;
        }
        if ("https".equalsIgnoreCase(scheme)) {
            return 443;
        }
        return -1;
    }

    private void sendJsonError(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (prefersHtmlErrorPage(request)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        String message = messages.getOrDefault(localeResolver.resolveLocale(request),
                "auth.csrf.invalid", "Request origin verification failed");
        ApiErrorWriter.write(response, HttpServletResponse.SC_FORBIDDEN, "auth.csrf.invalid", message);
    }

    /** 非 API 请求明确接受 HTML 时交给容器错误页；来源校验本身不因此放宽。 */
    private static boolean prefersHtmlErrorPage(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri != null && uri.startsWith("/api/")) {
            return false;
        }
        String accept = request.getHeader(HttpHeaders.ACCEPT);
        return accept != null && accept.contains(MediaType.TEXT_HTML_VALUE);
    }
}
