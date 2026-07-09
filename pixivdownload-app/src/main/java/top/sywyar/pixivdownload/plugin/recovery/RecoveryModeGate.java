package top.sywyar.pixivdownload.plugin.recovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import top.sywyar.pixivdownload.common.ErrorResponse;
import top.sywyar.pixivdownload.common.web.SafeRequestPath;
import top.sywyar.pixivdownload.i18n.AppLocaleResolver;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.plugin.runtime.status.RecoveryModeReason;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;

/**
 * 恢复模式访问拦截（servlet 过滤器，{@code @Order(0)} 早于 {@link top.sywyar.pixivdownload.setup.AuthFilter}）：当
 * {@link RecoveryModeService} 判定核心壳处于恢复模式（存在未满足的必选插件）时，只放行诊断 / 修复必需的入口，拦截
 * 普通业务页面、下载 API 与油猴脚本入口，给出「缺少必须插件，请安装或修复」的明确提示。
 *
 * <p><b>正常运行时本过滤器完全透明</b>：{@link RecoveryModeService#isActive()} 为 {@code false} 时直接放行，不改变
 * 任何既有路由 / 鉴权行为（鉴权仍由 {@code AuthFilter} 执行）。仅当必选插件未满足时本过滤器才生效。
 *
 * <p>恢复模式下放行的最小入口：容器健康探针（{@code /actuator/health*}、{@code /actuator/info}）、插件状态 / 安装 /
 * 修复 API（{@code /api/plugins/**}）、插件市场页面 / 静态 / API（{@code /plugin-market.html}、{@code /plugin-market/**}、
 * {@code /api/plugin-market/**}）、本机 GUI（{@code /api/gui/**}，仍受 {@code AuthFilter} 的本地 + 令牌校验）、i18n
 * 文案（{@code /api/i18n/**}）、核心认证与 setup 入口（{@code /api/auth/**}、{@code /api/setup/**} 与 {@code /login.html}、
 * {@code /setup.html} 页面外壳及其同名目录静态资源）以及插件市场渲染所需的基础静态资源（{@code /js/}、{@code /css/}、
 * {@code /vendor/}）与导航端点（{@code /api/navigation}）。核心认证 / setup / 市场入口仅放行到后续 {@code AuthFilter} /
 * controller，本过滤器不重复实现鉴权——本机校验、登录限流、session 逻辑与管理员鉴权仍由既有链路负责。其余一律拦截：
 * API 返回 503 JSON，非 API 请求重定向到插件市场，避免在缺少下载插件时误开放下载 / 业务功能或油猴脚本入口。
 */
@Component
@Order(0)
public class RecoveryModeGate extends OncePerRequestFilter {

    private static final String PLUGIN_MARKET_PATH = "/plugin-market.html";

    private final RecoveryModeService recoveryModeService;
    private final AppLocaleResolver localeResolver;
    private final AppMessages messages;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RecoveryModeGate(RecoveryModeService recoveryModeService,
                            AppLocaleResolver localeResolver, AppMessages messages) {
        this.recoveryModeService = recoveryModeService;
        this.localeResolver = localeResolver;
        this.messages = messages;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        if (!recoveryModeService.isActive()) {
            chain.doFilter(req, res);
            return;
        }
        Optional<String> path = SafeRequestPath.resolve(req);
        if ("OPTIONS".equalsIgnoreCase(req.getMethod())) {
            chain.doFilter(req, res);
            return;
        }
        if (path.isPresent() && isAllowedInRecoveryMode(path.get())) {
            chain.doFilter(req, res);
            return;
        }
        block(req, res, path.orElse(req.getRequestURI()));
    }

    /** 恢复模式下仍放行的最小入口集合（诊断 / 修复 / 健康 / 核心认证与 setup / 基础静态）。 */
    private boolean isAllowedInRecoveryMode(String path) {
        return isHealthProbe(path)
                || path.startsWith("/api/plugins/")
                || isPluginMarketEntry(path)
                || path.startsWith("/api/gui/")
                || path.startsWith("/api/i18n/")
                || path.equals("/api/navigation")
                || isCoreAuthOrSetupApi(path)
                || isRecoveryStaticResource(path);
    }

    /**
     * 核心认证 / setup 必需 API（{@code /api/auth/**}、{@code /api/setup/**}）：仅放行到后续 {@code AuthFilter} /
     * controller，本过滤器不重复实现鉴权，安全仍由既有本机校验、登录限流、session 逻辑负责。
     */
    private static boolean isCoreAuthOrSetupApi(String path) {
        return path.startsWith("/api/auth/")
                || path.startsWith("/api/setup/");
    }

    private static boolean isHealthProbe(String path) {
        return path.equals("/actuator/health")
                || path.startsWith("/actuator/health/")
                || path.equals("/actuator/info");
    }

    private static boolean isPluginMarketEntry(String path) {
        return path.equals(PLUGIN_MARKET_PATH)
                || path.startsWith("/plugin-market/")
                || path.equals("/api/plugin-market")
                || path.startsWith("/api/plugin-market/");
    }

    /**
     * 渲染恢复提示与核心认证 / setup 所需的基础静态资源及页面外壳：登录 / setup 页面外壳与其同名目录静态资源
     * （{@code /login.html}、{@code /login/}、{@code /setup.html}、{@code /setup/}）、首页外壳、站点图标与共享
     * {@code /js/}、{@code /css/}、{@code /vendor/}；不含业务页面 / 油猴脚本 / {@code /userscripts/} 静态。
     */
    private static boolean isRecoveryStaticResource(String path) {
        return path.equals("/")
                || path.equals("/index.html")
                || path.equals("/favicon.ico")
                || path.equals("/login.html")
                || path.startsWith("/login/")
                || path.equals("/setup.html")
                || path.startsWith("/setup/")
                || path.startsWith("/js/")
                || path.startsWith("/css/")
                || path.startsWith("/vendor/");
    }

    private void block(HttpServletRequest req, HttpServletResponse res, String path) throws IOException {
        res.setCharacterEncoding(StandardCharsets.UTF_8.name());
        if (path.startsWith("/api/")) {
            String message = resolveMessage(localeResolver.resolveLocale(req));
            res.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            res.setHeader(HttpHeaders.RETRY_AFTER, "120");
            res.setContentType(MediaType.APPLICATION_JSON_VALUE);
            res.getWriter().write(objectMapper.writeValueAsString(new ErrorResponse(message)));
        } else {
            res.sendRedirect(PLUGIN_MARKET_PATH);
        }
    }

    /** 取首个未满足必选插件的提示文案 key；缺省回落到通用提示 key。 */
    private String resolveMessage(Locale locale) {
        String key = recoveryModeService.decision().firstReason()
                .map(RecoveryModeReason::messageKey)
                .filter(k -> k != null && !k.isBlank())
                .orElse("plugin.recovery.blocked");
        return messages.getOrDefault(locale, key, "缺少必须的下载插件，请先安装或修复后重试");
    }
}
