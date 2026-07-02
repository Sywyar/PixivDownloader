package top.sywyar.pixivdownload.onboarding;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 记录用户对下载页的访问，驱动 GUI「首页」引导自动推进。
 *
 * <p>排在 {@link top.sywyar.pixivdownload.setup.AuthFilter}（{@code @Order(1)}）之后：
 * 只有当鉴权放行、页面真正被打开时才记录，未登录被重定向到 login 的请求不计入。</p>
 */
@Component
@Order(2)
@RequiredArgsConstructor
public class OnboardingVisitFilter extends OncePerRequestFilter {

    private static final String BATCH_PAGE = "/pixiv-batch.html";

    private final OnboardingProgressService progressService;

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res,
                                    FilterChain chain) throws ServletException, IOException {
        chain.doFilter(req, res);

        if (!"GET".equalsIgnoreCase(req.getMethod()) || res.getStatus() >= 400) {
            return;
        }
        String path = req.getRequestURI();
        if (BATCH_PAGE.equals(path)) {
            progressService.recordBatchVisit();
        }
    }
}
