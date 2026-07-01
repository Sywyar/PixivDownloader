package top.sywyar.pixivdownload.plugin.lifecycle;

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
import top.sywyar.pixivdownload.i18n.AppLocaleResolver;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.plugin.api.web.HttpMethod;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import top.sywyar.pixivdownload.plugin.recovery.RecoveryModeGate;
import top.sywyar.pixivdownload.plugin.registry.RouteAccessRegistry;

/**
 * 插件静默（quiesce）请求网关（servlet 过滤器，{@code @Order(0)} 早于 {@code AuthFilter}）：当某外置插件被
 * {@link PluginLifecycleService#quiesce} 标记为 {@link PluginRuntimePhase#QUIESCED} 时，命中其<b>仍已声明</b>路由
 * 的新请求被转为明确的「插件不可用」503 响应，而不是落到下游正常处理（更不会误落到访客默认放行）。这实现了
 * 热卸载静默协议的「停止向该插件路由新请求」一步——quiesce 后路由声明仍在（尚未注销），故必须由本网关拦截；
 * 一旦插件 {@code stop}（路由声明注销），其 URL 即「未声明」、由 {@code AuthFilter} 统一 404，不再走本网关。
 *
 * <p><b>正常运行下完全透明</b>：无任何插件处于 quiesce 态时（{@link PluginLifecycleState#quiescedPluginIds()} 为空）
 * 直接放行，零开销、不改变任何既有路由 / 鉴权行为（鉴权仍由 {@code AuthFilter} 按 {@link RouteAccessRegistry}
 * 独立执行，金标准不受影响）。
 *
 * <p>请求归属判定经 {@link RouteAccessRegistry#resolve}（按最具体声明解析所属插件），仅当解析到的所属插件正处于
 * quiesce 态才拦截——故与其它插件、核心路由正交：被 quiesce 插件路由覆盖、但有更具体核心路由命中的请求仍正常处理。
 * 与恢复模式访问拦截（{@code RecoveryModeGate}）正交：后者针对「必选插件缺失致全壳降级」，本网关针对「某具体插件
 * 正在停用」，两者各自独立返回 503。
 */
@Component
@Order(0)
public class PluginQuiesceGate extends OncePerRequestFilter {

    private final RouteAccessRegistry routeAccessRegistry;
    private final PluginLifecycleState lifecycleState;
    private final AppLocaleResolver localeResolver;
    private final AppMessages messages;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PluginQuiesceGate(RouteAccessRegistry routeAccessRegistry, PluginLifecycleState lifecycleState,
                             AppLocaleResolver localeResolver, AppMessages messages) {
        this.routeAccessRegistry = routeAccessRegistry;
        this.lifecycleState = lifecycleState;
        this.localeResolver = localeResolver;
        this.messages = messages;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        // 快速短路：无 quiesce 插件时完全透明（绝大多数请求走这里）。OPTIONS 预检放行。
        if (lifecycleState.quiescedPluginIds().isEmpty() || "OPTIONS".equalsIgnoreCase(req.getMethod())) {
            chain.doFilter(req, res);
            return;
        }
        if (isQuiescedRoute(req)) {
            block(req, res);
            return;
        }
        chain.doFilter(req, res);
    }

    /** 该请求是否命中某个正处于 quiesce 态的外置插件所拥有的路由。 */
    private boolean isQuiescedRoute(HttpServletRequest req) {
        HttpMethod method = toHttpMethod(req.getMethod());
        if (method == null) {
            return false;
        }
        Optional<RouteAccessRegistry.RegisteredRoute> owner = safeResolve(req.getRequestURI(), method);
        return owner.isPresent() && lifecycleState.isQuiesced(owner.get().pluginId());
    }

    /** 解析所属路由；遇到声明歧义等异常时返回空、让正常链路处理（不在网关误拦）。 */
    private Optional<RouteAccessRegistry.RegisteredRoute> safeResolve(String path, HttpMethod method) {
        try {
            return routeAccessRegistry.resolve(path, method);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static HttpMethod toHttpMethod(String method) {
        if (method == null) {
            return null;
        }
        try {
            return HttpMethod.valueOf(method.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void block(HttpServletRequest req, HttpServletResponse res) throws IOException {
        String message = messages.getOrDefault(localeResolver.resolveLocale(req),
                "plugin.unavailable.quiesced", "插件正在停用中，暂时不可用，请稍后重试");
        res.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        res.setHeader(HttpHeaders.RETRY_AFTER, "30");
        res.setCharacterEncoding(StandardCharsets.UTF_8.name());
        String path = req.getRequestURI();
        if (path != null && path.startsWith("/api/")) {
            res.setContentType(MediaType.APPLICATION_JSON_VALUE);
            res.getWriter().write(objectMapper.writeValueAsString(new ErrorResponse(message)));
        } else {
            res.setContentType(MediaType.TEXT_HTML_VALUE);
            res.getWriter().write(unavailableHtml(message));
        }
    }

    private static String unavailableHtml(String message) {
        String safe = message.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        return "<!DOCTYPE html><html lang=\"zh\"><head><meta charset=\"UTF-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<title>Plugin unavailable</title></head><body>"
                + "<main style=\"max-width:32rem;margin:4rem auto;font-family:sans-serif;line-height:1.6\">"
                + "<p>" + safe + "</p></main></body></html>";
    }
}
