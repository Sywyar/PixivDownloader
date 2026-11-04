package top.sywyar.pixivdownload.setup;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** 为正常响应和前置网关短路响应统一添加浏览器安全策略。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SecurityResponseHeadersFilter extends OncePerRequestFilter {

    private final top.sywyar.pixivdownload.plugin.registry.web.PageContributionRegistry pages;
    private final top.sywyar.pixivdownload.plugin.web.page.PagePublicationRequest publications;

    public SecurityResponseHeadersFilter(top.sywyar.pixivdownload.plugin.registry.web.PageContributionRegistry pages,
            top.sywyar.pixivdownload.plugin.web.page.PagePublicationRequest publications) {
        this.pages = pages; this.publications = publications;
    }

    private static final String CONTENT_SECURITY_POLICY = "default-src 'self'; base-uri 'self';"
            + " object-src 'none'; script-src 'self'; script-src-attr 'none';"
            + " style-src 'self' 'unsafe-inline' https://fonts.googleapis.com;"
            + " font-src 'self' https://fonts.gstatic.com; img-src 'self' data: blob:;"
            + " media-src 'self' blob:; connect-src 'self' https://layout-survey.sywyar.top"
            + " https://us.i.posthog.com https://eu.i.posthog.com"
            + " https://fonts.googleapis.com https://fonts.gstatic.com; frame-src 'self';"
            + " worker-src 'none'; manifest-src 'self'; form-action 'self'; frame-ancestors %s";
    private static final String PERMISSIONS_POLICY = "accelerometer=(), camera=(), geolocation=(),"
            + " gyroscope=(), magnetometer=(), microphone=(), payment=(), usb=()";
    private static final String SEC_FETCH_DEST = "Sec-Fetch-Dest";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String frameAncestors = "iframe".equalsIgnoreCase(request.getHeader(SEC_FETCH_DEST))
                ? "'self'"
                : "'none'";
        String csp = CONTENT_SECURITY_POLICY.formatted(frameAncestors);
        String path = top.sywyar.pixivdownload.common.web.SafeRequestPath.resolve(request).orElse("");
        var matches = pages.pages().stream().filter(page -> page.definition().path().equals(path)).toList();
        if (matches.size() == 1) {
            try {
                var permissions = publications.permissions(matches.get(0).definition().id(), request);
                var directives = new java.util.LinkedHashMap<String, java.util.LinkedHashSet<String>>();
                for (String directive : csp.split(";")) {
                    var parts = directive.strip().split(" +");
                    directives.put(parts[0], new java.util.LinkedHashSet<>(java.util.List.of(parts).subList(1, parts.length)));
                }
                for (var permission : permissions) for (var source : permission.profile().sources()) {
                    String name = switch (source.kind()) {
                        case SCRIPT -> "script-src"; case FRAME -> "frame-src"; case CONNECT -> "connect-src";
                        case STYLE -> "style-src"; case IMAGE -> "img-src"; case FONT -> "font-src"; case WORKER -> "worker-src";
                    };
                    var values = directives.get(name);
                    values.remove("'none'"); values.add(source.origin().equals("self") ? "'self'" : source.origin());
                }
                csp = directives.entrySet().stream().map(entry -> entry.getKey() + " " + String.join(" ", entry.getValue()))
                        .collect(java.util.stream.Collectors.joining("; "));
                response.setHeader("X-Pixiv-Page-Revision", publications.revision(request));
            } catch (IllegalStateException unavailable) {
                // 未知发布状态只保留安全基线；操作 Gate 独立拒绝，不推导空策略。
                response.setHeader("X-Pixiv-Page-State", "unavailable");
            }
        }
        response.setHeader("Content-Security-Policy", csp);
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Permissions-Policy", PERMISSIONS_POLICY);
        response.addHeader(HttpHeaders.VARY, SEC_FETCH_DEST);
        chain.doFilter(request, response);
    }
}
