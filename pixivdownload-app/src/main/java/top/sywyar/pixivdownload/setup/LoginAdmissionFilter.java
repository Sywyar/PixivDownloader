package top.sywyar.pixivdownload.setup;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import top.sywyar.pixivdownload.common.web.*;
import top.sywyar.pixivdownload.i18n.*;
import top.sywyar.pixivdownload.plugin.security.HookRequestFilter;
import top.sywyar.pixivdownload.web.ApiErrorWriter;

/** 认证入口的来源和 CSRF 检查完成后，先限制预算，再进入任何插件回调或凭据查询。 */
@Component
@Order(3)
@RequiredArgsConstructor
public final class LoginAdmissionFilter extends OncePerRequestFilter {
    private final LoginRateLimitService limits;
    private final AppLocaleResolver locales;
    private final AppMessages messages;
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!"POST".equals(request.getMethod()) || SafeRequestPath.resolve(request)
                .filter(Set.of("/api/auth/login", "/api/auth/invite-redeem")::contains).isEmpty()) {
            chain.doFilter(request, response); return;
        }
        if (!limits.isAllowed(request.getRemoteAddr())) {
            reject(request, response, 429, "setup.login.rate-limit.exceeded"); return;
        }
        if (!HookRequestFilter.isPreparation(request)) {
            var body = org.springframework.web.util.WebUtils.getNativeRequest(request, BoundedBodyRequest.class);
            if (body == null) { body = new BoundedBodyRequest(request); request = body; }
            try { body.readBody(16384); }
            catch (IllegalArgumentException exceeded) { reject(request, response, 413, "http.status.413"); return; }
        }
        chain.doFilter(request, response);
    }
    private void reject(HttpServletRequest request, HttpServletResponse response, int status, String code) throws IOException {
        ApiErrorWriter.write(response, status, code, messages.getOrDefault(locales.resolveLocale(request), code, code));
    }
}
