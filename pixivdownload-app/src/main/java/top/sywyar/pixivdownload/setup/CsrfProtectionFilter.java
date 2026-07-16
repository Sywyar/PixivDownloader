package top.sywyar.pixivdownload.setup;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
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
import top.sywyar.pixivdownload.common.ErrorResponse;
import top.sywyar.pixivdownload.common.web.SafeRequestPath;
import top.sywyar.pixivdownload.i18n.AppLocaleResolver;
import top.sywyar.pixivdownload.i18n.AppMessages;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

@Component
@Order(2)
@Slf4j
@RequiredArgsConstructor
public class CsrfProtectionFilter extends OncePerRequestFilter {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern COLLECTION_ICON_PATH = Pattern.compile("^/api/collections/\\d+/icon$");
    private static final Pattern PLUGIN_MARKET_INSTALL_PATH =
            Pattern.compile("^/api/plugin-market/[^/]+/[^/]+/[^/]+/install$");

    private final AppLocaleResolver localeResolver;
    private final AppMessages messages;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!requiresSameOriginCheck(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        if (hasSameOriginSignal(request)) {
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
        String message = messages.getOrDefault(localeResolver.resolveLocale(request),
                "auth.csrf.invalid", "Request origin verification failed");
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(MAPPER.writeValueAsString(new ErrorResponse(message)));
    }
}
