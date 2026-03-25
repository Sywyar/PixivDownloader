package top.sywyar.pixivdownload.setup;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import top.sywyar.pixivdownload.quota.UserQuotaService;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Component
@Order(1)
@Slf4j
public class AuthFilter extends OncePerRequestFilter {

    @Autowired
    private SetupService setupService;

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res,
                                    FilterChain chain) throws IOException, ServletException {
        String path   = req.getRequestURI();
        String method = req.getMethod();

        // OPTIONS 预检请求直接放行（CORS 处理）
        if ("OPTIONS".equalsIgnoreCase(method)) {
            chain.doFilter(req, res);
            return;
        }

        // 公开路径：setup/login 页面、setup/auth API
        if (isPublic(path)) {
            chain.doFilter(req, res);
            return;
        }

        // /api/downloaded/ 接口：POST /move/ 仅限本地 IP；其余接口本地 IP 直接放行，非本地走 session 校验
        if (path.startsWith("/api/downloaded/")) {
            if ("POST".equalsIgnoreCase(method) && path.contains("/downloaded/move/")) {
                if (!isLocalAddress(req.getRemoteAddr())) {
                    res.sendError(403, "Forbidden: local access only");
                    return;
                }
                chain.doFilter(req, res);
                return;
            }
            if (isLocalAddress(req.getRemoteAddr())) {
                chain.doFilter(req, res);
                return;
            }
            // 非本地 IP 继续走下方 session 校验
        }

        // 未完成初始配置 → 跳转 setup 页面
        if (!setupService.isSetupComplete()) {
            if (isApi(path)) {
                res.sendError(503, "Setup required");
            } else {
                res.sendRedirect("/setup.html");
            }
            return;
        }

        // 多人模式：无需认证，但为用户分配 UUID cookie（用于配额追踪）
        if ("multi".equals(setupService.getMode())) {
            ensureUserUuidCookie(req, res);
            chain.doFilter(req, res);
            return;
        }

        // 自用模式：校验 session
        String token = extractToken(req);
        if (setupService.isValidSession(token)) {
            chain.doFilter(req, res);
        } else {
            if (isApi(path)) {
                res.sendError(401, "Unauthorized");
            } else {
                String redirect = URLEncoder.encode(path, StandardCharsets.UTF_8);
                res.sendRedirect("/login.html?redirect=" + redirect);
            }
        }
    }

    private boolean isPublic(String path) {
        return path.equals("/setup.html")
            || path.equals("/login.html")
            || path.equals("/favicon.ico")
            || path.startsWith("/api/setup/")
            || path.startsWith("/api/auth/")
            || path.startsWith("/api/quota/")
            || path.startsWith("/api/archive/")
            || path.equals("/api/download/status");        // 健康检查，供外部工具探测服务是否在线
    }

    private boolean isApi(String path) {
        return path.startsWith("/api/");
    }

    private boolean isLocalAddress(String remoteAddr) {
        return "127.0.0.1".equals(remoteAddr)
            || "0:0:0:0:0:0:0:1".equals(remoteAddr)
            || "::1".equals(remoteAddr);
    }

    private String extractToken(HttpServletRequest req) {
        Cookie[] cookies = req.getCookies();
        if (cookies != null) {
            for (Cookie c : cookies) {
                if ("pixiv_session".equals(c.getName())) return c.getValue();
            }
        }
        return req.getHeader("X-Session-Token");
    }

    /** 多人模式：若没有 pixiv_user_id cookie，则基于 IP+UA 生成并写入 */
    private void ensureUserUuidCookie(HttpServletRequest req, HttpServletResponse res) {
        Cookie[] cookies = req.getCookies();
        if (cookies != null) {
            for (Cookie c : cookies) {
                if ("pixiv_user_id".equals(c.getName()) && c.getValue() != null
                        && !c.getValue().isBlank()) {
                    return; // 已有 UUID，无需重新生成
                }
            }
        }
        // 检查自定义请求头（油猴脚本场景）
        String headerUuid = req.getHeader("X-User-UUID");
        String uuid = (headerUuid != null && !headerUuid.isBlank())
                ? headerUuid
                : UserQuotaService.generateUuidFromFingerprint(
                        req.getRemoteAddr(), req.getHeader("User-Agent"));
        Cookie cookie = new Cookie("pixiv_user_id", uuid);
        cookie.setPath("/");
        cookie.setMaxAge(30 * 24 * 3600);
        res.addCookie(cookie);
    }
}
