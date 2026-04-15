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
import top.sywyar.pixivdownload.quota.RateLimitService;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Component
@Order(1)
@Slf4j
@RequiredArgsConstructor
public class AuthFilter extends OncePerRequestFilter {

    private final SetupService setupService;
    private final RateLimitService rateLimitService;

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res,
                                    FilterChain chain) throws IOException, ServletException {
        String path = req.getRequestURI();
        String method = req.getMethod();

        // OPTIONS 预检请求直接放行（CORS 处理）
        if ("OPTIONS".equalsIgnoreCase(method)) {
            chain.doFilter(req, res);
            return;
        }

        // /redirect：根据 canvas 参数和 introMode 决定重定向目标（需在 isPublic 之前处理）
        if (path.equals("/redirect")) {
            String canvasParam = req.getParameter("canvas");
            boolean canvasSupported = "true".equalsIgnoreCase(canvasParam);
            if (setupService.isIntroMode()) {
                String target = canvasSupported ? "/intro-canary.html" : "/intro.html";
                res.sendRedirect(target);
            } else {
                res.sendRedirect("/pixiv-batch.html");
            }
            return;
        }

        // 公开路径：login/intro 页面、setup/auth API（setup.html 单独做本地 IP 校验）
        if (isPublic(path)) {
            chain.doFilter(req, res);
            return;
        }

        // setup.html 仅限本地 IP 访问
        if (path.equals("/setup.html")) {
            if (!NetworkUtils.isLocalAddress(req.getRemoteAddr())) {
                sendJsonError(res, 403, "Forbidden: local access only");
                return;
            }
            chain.doFilter(req, res);
            return;
        }

        // /api/downloaded/ 接口：POST /move/ 仅限本地 IP；其余接口本地 IP 直接放行，非本地走 session 校验
        if (path.startsWith("/api/downloaded/") || path.equals("/api/download/status")) {
            if ("POST".equalsIgnoreCase(method) && path.contains("/downloaded/move/")) {
                if (!NetworkUtils.isLocalAddress(req.getRemoteAddr())) {
                    sendJsonError(res, 403, "Forbidden: local access only");
                    return;
                }
                chain.doFilter(req, res);
                return;
            }
            if (NetworkUtils.isLocalAddress(req.getRemoteAddr())) {
                chain.doFilter(req, res);
                return;
            }
            // 非本地 IP 继续走下方 session 校验
        }

        // 未完成初始配置 → 跳转 setup 页面
        if (!setupService.isSetupComplete()) {
            if (isApi(path)) {
                sendJsonError(res, 503, "Setup required");
            } else {
                res.sendRedirect("/setup.html");
            }
            return;
        }

        // 多人模式：无需认证，但为用户分配 UUID cookie（用于配额追踪）
        if ("multi".equals(setupService.getMode())) {
            // 速率限制：对所有 API 请求按 UUID 计数，超出时返回 429
            if (isApi(path)) {
                String uuid = UuidUtils.extractOrGenerateUuid(req);
                if (!rateLimitService.isAllowed(uuid)) {
                    sendJsonError(res, 429, "Too Many Requests");
                    return;
                }
            }
            ensureUserUuidCookie(req, res);
            chain.doFilter(req, res);
            return;
        }

        // 自用模式：校验 session
        String token = SessionUtils.extractToken(req);
        if (setupService.isValidSession(token)) {
            chain.doFilter(req, res);
        } else {
            if (isApi(path)) {
                sendJsonError(res, 401, "Unauthorized");
            } else {
                String redirect = URLEncoder.encode(path, StandardCharsets.UTF_8);
                // solo 模式未登录：先尝试 index.html（检测 html-in-canvas 支持重定向到 intro-canary，否则 login）
                res.sendRedirect("/login.html?redirect=" + redirect);
            }
        }
    }

    private boolean isPublic(String path) {
        return path.equals("/login.html")
                || path.equals("/index.html")
                || path.equals("/intro.html")
                || path.equals("/intro-canary.html")
                || path.equals("/favicon.ico")
                || path.startsWith("/api/setup/")
                || path.startsWith("/api/auth/")
                // GUI 接口：无 session（GUI 在 solo 模式下无 token），控制器内部校验 localhost
                || path.startsWith("/api/gui/");
    }

    private boolean isApi(String path) {
        return path.startsWith("/api/");
    }

    private void sendJsonError(HttpServletResponse res, int status, String message) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        res.setStatus(status);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding("UTF-8");
        res.getWriter().write(mapper.writeValueAsString(new ErrorResponse(message)));
    }

    /**
     * 多人模式：若没有 pixiv_user_id cookie，则基于请求头或 IP+UA 生成并写入
     */
    private void ensureUserUuidCookie(HttpServletRequest req, HttpServletResponse res) {
        // 已有 cookie，无需重新生成
        Cookie[] cookies = req.getCookies();
        if (cookies != null) {
            for (Cookie c : cookies) {
                if ("pixiv_user_id".equals(c.getName()) && c.getValue() != null
                        && !c.getValue().isBlank()) {
                    return;
                }
            }
        }
        // 从请求头或 IP+UA 指纹获取/生成 UUID，并写入 cookie
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
