package top.sywyar.pixivdownload.plugin.lifecycle;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import top.sywyar.pixivdownload.common.ErrorResponse;
import top.sywyar.pixivdownload.common.web.SafeRequestPath;
import top.sywyar.pixivdownload.i18n.AppLocaleResolver;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.plugin.api.web.HttpMethod;
import top.sywyar.pixivdownload.plugin.lifecycle.request.PluginRequestLease;
import top.sywyar.pixivdownload.plugin.lifecycle.request.PluginRequestLeaseRegistry;
import top.sywyar.pixivdownload.plugin.lifecycle.request.PluginRequestOwner;
import top.sywyar.pixivdownload.plugin.recovery.RecoveryModeGate;
import top.sywyar.pixivdownload.plugin.registry.RouteAccessRegistry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;

/**
 * 插件静默（quiesce）请求网关（servlet 过滤器，{@code @Order(0)} 早于 {@code AuthFilter}）：当某外置插件被
 * {@link PluginLifecycleService#quiesce} 标记为 {@link PluginRuntimePhase#QUIESCED} 时，命中其<b>仍已声明</b>路由
 * 的新请求被转为明确的「插件不可用」503 响应，而不是落到下游正常处理（更不会误落到访客默认放行）。这实现了
 * 热卸载静默协议的「停止向该插件路由新请求」一步——quiesce 后路由声明仍在（尚未注销），故必须由本网关拦截；
 * 一旦插件 {@code stop}（路由声明注销），其 URL 即「未声明」、由 {@code AuthFilter} 统一 404，不再走本网关。
 *
 * <p><b>正常运行下不改变鉴权结论</b>：核心 / 内置路由透明放行；外置插件路由在进入过滤链前取得当前 serving
 * 代的宿主请求租约，同步请求在过滤链返回时释放，Servlet async 请求在最终 complete 时释放。鉴权仍由
 * {@code AuthFilter} 按 {@link RouteAccessRegistry} 独立执行。
 *
 * <p>请求归属判定经 {@link RouteAccessRegistry#resolve}（按最具体声明解析所属插件），仅当解析到的所属插件正处于
 * quiesce 态才拦截——故与其它插件、核心路由正交：被 quiesce 插件路由覆盖、但有更具体核心路由命中的请求仍正常处理。
 * 路由归属解析失败、serving 已撤回或 async 完成边界无法可靠观测时均 fail-closed，绝不把仍在途的请求误报为已排空。
 * 与恢复模式访问拦截（{@code RecoveryModeGate}）正交：后者针对「必选插件缺失致全壳降级」，本网关针对「某具体插件
 * 正在停用」，两者各自独立返回 503。
 */
@Component
@Order(0)
public class PluginQuiesceGate extends OncePerRequestFilter {

    private final RouteAccessRegistry routeAccessRegistry;
    private final PluginLifecycleState lifecycleState;
    private final PluginRequestLeaseRegistry requestLeaseRegistry;
    private final AppLocaleResolver localeResolver;
    private final AppMessages messages;
    private final Runnable decisionReturnProbe;
    private final Runnable afterLeaseActivationProbe;
    private final Runnable beforeDownstreamProbe;
    private final Runnable afterAsyncListenerAttachProbe;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public PluginQuiesceGate(RouteAccessRegistry routeAccessRegistry,
                             PluginLifecycleState lifecycleState,
                             PluginRequestLeaseRegistry requestLeaseRegistry,
                             AppLocaleResolver localeResolver, AppMessages messages) {
        this(routeAccessRegistry, lifecycleState, requestLeaseRegistry, localeResolver, messages,
                () -> {
                }, () -> {
                }, () -> {
                }, () -> {
                });
    }

    PluginQuiesceGate(RouteAccessRegistry routeAccessRegistry,
                      PluginLifecycleState lifecycleState,
                      PluginRequestLeaseRegistry requestLeaseRegistry,
                      AppLocaleResolver localeResolver,
                      AppMessages messages,
                      Runnable decisionReturnProbe,
                      Runnable afterLeaseActivationProbe,
                      Runnable beforeDownstreamProbe,
                      Runnable afterAsyncListenerAttachProbe) {
        this.routeAccessRegistry = routeAccessRegistry;
        this.lifecycleState = lifecycleState;
        this.requestLeaseRegistry = requestLeaseRegistry;
        this.localeResolver = localeResolver;
        this.messages = messages;
        this.decisionReturnProbe = java.util.Objects.requireNonNull(
                decisionReturnProbe, "route decision return probe");
        this.afterLeaseActivationProbe = java.util.Objects.requireNonNull(
                afterLeaseActivationProbe, "request lease activation probe");
        this.beforeDownstreamProbe = java.util.Objects.requireNonNull(
                beforeDownstreamProbe, "request downstream probe");
        this.afterAsyncListenerAttachProbe = java.util.Objects.requireNonNull(
                afterAsyncListenerAttachProbe, "async listener attachment probe");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        // Every declared method, including explicit plugin OPTIONS handlers, follows the same serving lease boundary.
        RouteDecision decision = routeDecision(req);
        if (decision.hasRequestLease()) {
            decisionReturnProbe.run();
        }
        if (decision == RouteDecision.BLOCK) {
            block(req, res);
            return;
        }
        if (decision == RouteDecision.PASS) {
            chain.doFilter(req, res);
            return;
        }

        AsyncRequestLease requestLease = decision.requestLease();
        if (requestLease == null) {
            throw new IllegalStateException("leased plugin route without request lease");
        }
        doFilterWithLease(req, res, chain, requestLease);
    }

    /** Resolve ownership and preallocate the exact request scope without activating it across the call boundary. */
    private RouteDecision routeDecision(HttpServletRequest req) {
        HttpMethod method = toHttpMethod(req.getMethod());
        if (method == null) {
            return RouteDecision.PASS;
        }
        Optional<String> path = SafeRequestPath.resolve(req);
        if (path.isEmpty()) {
            return lifecycleState.snapshot().isEmpty() ? RouteDecision.PASS : RouteDecision.BLOCK;
        }
        RouteResolution resolution = safeResolve(path.get(), method);
        if (resolution.failed()) {
            return !lifecycleState.snapshot().isEmpty()
                    ? RouteDecision.BLOCK : RouteDecision.PASS;
        }
        Optional<RouteAccessRegistry.RegisteredRoute> resolved = resolution.route();
        if (resolved.isEmpty()) {
            return RouteDecision.PASS;
        }

        RouteAccessRegistry.RegisteredRoute route = resolved.orElseThrow();
        PluginRequestOwner requestOwner = route.requestOwner();
        if (requestOwner == null) {
            return lifecycleState.phase(route.pluginId()).isPresent()
                    ? RouteDecision.BLOCK : RouteDecision.PASS;
        }
        String pluginId = requestOwner.pluginId();
        Optional<PluginRuntimePhase> phase = lifecycleState.phase(pluginId);
        if (phase.isEmpty()) {
            return RouteDecision.BLOCK;
        }
        if (!phase.orElseThrow().acceptsNewRequests()) {
            return RouteDecision.BLOCK;
        }

        Optional<PluginRequestLease> prepared = requestLeaseRegistry.prepareLease(requestOwner);
        if (prepared.isEmpty()) {
            return RouteDecision.BLOCK;
        }
        PluginRequestLease lease = prepared.orElseThrow();
        AsyncRequestLease asyncLease = new AsyncRequestLease(lease);
        return RouteDecision.leased(asyncLease);
    }

    private void doFilterWithLease(HttpServletRequest req,
                                   HttpServletResponse res,
                                   FilterChain chain,
                                   AsyncRequestLease scope) throws ServletException, IOException {
        FailureTracker failures = new FailureTracker();
        try {
            if (!scope.activate(requestLeaseRegistry)) {
                block(req, res);
            } else {
                afterLeaseActivationProbe.run();
                beforeDownstreamProbe.run();
                chain.doFilter(req, res);
            }
        } catch (Throwable failure) {
            failures.record(failure);
        } finally {
            finishRequestScope(req, scope, failures);
        }
        failures.rethrow();
    }

    /**
     * Completes the exact synchronous lease or establishes a known async listener handoff. A fatal failure in the
     * ambiguous {@link AsyncContext#addListener} window is retried with the same idempotent listener; duplicate
     * registration is harmless because the underlying lease close is exact and idempotent.
     */
    private void finishRequestScope(HttpServletRequest request,
                                    AsyncRequestLease scope,
                                    FailureTracker failures) {
        boolean finished = false;
        while (!finished) {
            try {
                if (!request.isAsyncStarted()) {
                    scope.close();
                } else {
                    scope.attach(request.getAsyncContext(), afterAsyncListenerAttachProbe);
                }
                finished = true;
            } catch (Throwable failure) {
                failures.record(failure);
                if (scope.isAsyncAttached()) {
                    finished = true;
                    continue;
                }
                if (isFatal(failure)) {
                    continue;
                }

                boolean recoveryFatal = false;
                try {
                    if (request.isAsyncStarted()) {
                        request.getAsyncContext().complete();
                    }
                } catch (Throwable completionFailure) {
                    failures.record(completionFailure);
                    recoveryFatal = isFatal(completionFailure);
                }

                boolean confirmedComplete = false;
                try {
                    confirmedComplete = !request.isAsyncStarted();
                } catch (Throwable observationFailure) {
                    failures.record(observationFailure);
                    recoveryFatal |= isFatal(observationFailure);
                }

                if (confirmedComplete) {
                    try {
                        scope.close();
                        finished = true;
                    } catch (Throwable closeFailure) {
                        failures.record(closeFailure);
                        finished = !scope.isActive();
                    }
                } else if (recoveryFatal) {
                    // A fatal recovery interruption leaves ownership ambiguous; retry listener attachment exactly.
                    finished = false;
                } else {
                    // No listener handoff and no confirmed completion: retain the lease and fail closed.
                    finished = true;
                }
            }
        }
    }

    /** Resolve ownership without allowing ambiguity or a nonfatal host error to bypass request leasing. */
    private RouteResolution safeResolve(String path, HttpMethod method) {
        try {
            return new RouteResolution(routeAccessRegistry.resolve(path, method), false);
        } catch (Throwable failure) {
            rethrowFatal(failure);
            return new RouteResolution(Optional.empty(), true);
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

    private static void addSuppressedSafely(Throwable target, Throwable suppressed) {
        if (target == suppressed) {
            return;
        }
        try {
            target.addSuppressed(suppressed);
        } catch (Throwable ignored) {
            // Preserve the request failure even when diagnostic attachment itself fails.
        }
    }

    private static void rethrowFatal(Throwable failure) {
        if (failure instanceof VirtualMachineError fatal) {
            throw fatal;
        }
        if (failure instanceof ThreadDeath fatal) {
            throw fatal;
        }
    }

    private static boolean isFatal(Throwable failure) {
        return failure instanceof VirtualMachineError || failure instanceof ThreadDeath;
    }

    private void block(HttpServletRequest req, HttpServletResponse res) throws IOException {
        String message = messages.getOrDefault(localeResolver.resolveLocale(req),
                "plugin.unavailable.quiesced", "插件正在停用中，暂时不可用，请稍后重试");
        res.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        res.setHeader(HttpHeaders.RETRY_AFTER, "30");
        res.setCharacterEncoding(StandardCharsets.UTF_8.name());
        String path = SafeRequestPath.resolve(req).orElse(req.getRequestURI());
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

    private record RouteResolution(Optional<RouteAccessRegistry.RegisteredRoute> route, boolean failed) {
    }

    private static final class RouteDecision {
        private static final RouteDecision PASS = new RouteDecision(null);
        private static final RouteDecision BLOCK = new RouteDecision(null);

        private final AsyncRequestLease requestLease;

        private RouteDecision(AsyncRequestLease requestLease) {
            this.requestLease = requestLease;
        }

        private static RouteDecision leased(AsyncRequestLease requestLease) {
            return new RouteDecision(requestLease);
        }

        private AsyncRequestLease requestLease() {
            return requestLease;
        }

        private boolean hasRequestLease() {
            return requestLease != null;
        }
    }

    /** Keeps the lease until the complete event; timeout/error alone may still be followed by an async dispatch. */
    private static final class AsyncRequestLease implements AsyncListener, AutoCloseable {
        private final PluginRequestLease lease;
        private volatile boolean asyncAttached;
        private Runnable listenerAttachProbe = () -> {
        };

        private AsyncRequestLease(PluginRequestLease lease) {
            this.lease = lease;
        }

        private boolean activate(PluginRequestLeaseRegistry registry) {
            return registry.activate(lease);
        }

        private boolean isActive() {
            return lease.isActive();
        }

        private boolean isAsyncAttached() {
            return asyncAttached;
        }

        private void attach(AsyncContext context, Runnable postAttachProbe) {
            listenerAttachProbe = postAttachProbe;
            context.addListener(this);
            asyncAttached = true;
            postAttachProbe.run();
        }

        @Override
        public void onComplete(AsyncEvent event) {
            close();
        }

        @Override
        public void onTimeout(AsyncEvent event) {
            // The application may dispatch again from a timeout callback; onComplete is the safe release boundary.
        }

        @Override
        public void onError(AsyncEvent event) {
            // The container may perform an error dispatch; onComplete is the safe release boundary.
        }

        @Override
        public void onStartAsync(AsyncEvent event) {
            // Retry fatal ambiguity with the same listener; an ordinary rejection keeps the lease fail-closed.
            Throwable firstFatal = null;
            while (true) {
                try {
                    event.getAsyncContext().addListener(this);
                    listenerAttachProbe.run();
                    break;
                } catch (Throwable failure) {
                    if (!isFatal(failure)) {
                        rethrowUnchecked(failure);
                    }
                    if (firstFatal == null) {
                        firstFatal = failure;
                    }
                }
            }
            rethrowUnchecked(firstFatal);
        }

        @Override
        public void close() {
            lease.close();
        }
    }

    /** Preallocated failure priority so cleanup can finish before the first fatal error is rethrown. */
    private static final class FailureTracker {
        private Throwable firstFailure;
        private Throwable firstFatal;

        private void record(Throwable failure) {
            if (failure == null) {
                return;
            }
            if (firstFailure == null) {
                firstFailure = failure;
                if (isFatal(failure)) {
                    firstFatal = failure;
                }
                return;
            }
            if (firstFatal == null && isFatal(failure)) {
                firstFatal = failure;
                addSuppressedSafely(failure, firstFailure);
                return;
            }
            addSuppressedSafely(firstFatal == null ? firstFailure : firstFatal, failure);
        }

        private void rethrow() throws IOException, ServletException {
            Throwable failure = firstFatal == null ? firstFailure : firstFatal;
            if (failure == null) {
                return;
            }
            if (failure instanceof IOException ioFailure) {
                throw ioFailure;
            }
            if (failure instanceof ServletException servletFailure) {
                throw servletFailure;
            }
            rethrowUnchecked(failure);
        }
    }

    private static void rethrowUnchecked(Throwable failure) {
        if (failure == null) {
            return;
        }
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new IllegalStateException("plugin request scope cleanup failed", failure);
    }
}
