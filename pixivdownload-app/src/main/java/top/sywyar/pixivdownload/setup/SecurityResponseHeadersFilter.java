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
        response.setHeader("Content-Security-Policy", CONTENT_SECURITY_POLICY.formatted(frameAncestors));
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Permissions-Policy", PERMISSIONS_POLICY);
        response.addHeader(HttpHeaders.VARY, SEC_FETCH_DEST);
        chain.doFilter(request, response);
    }
}
