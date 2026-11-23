package top.sywyar.pixivdownload.setup;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.web.util.ServletRequestPathUtils;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.beans.factory.annotation.Qualifier;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.beans.factory.ObjectProvider;
import top.sywyar.pixivdownload.config.InstanceCookies;
import top.sywyar.pixivdownload.common.GuiTokenProvider;
import top.sywyar.pixivdownload.common.NetworkUtils;
import top.sywyar.pixivdownload.common.SessionUtils;
import top.sywyar.pixivdownload.common.UuidUtils;
import top.sywyar.pixivdownload.web.ApiErrorWriter;
import top.sywyar.pixivdownload.plugin.api.gui.GuiActionInvocationHeaders;
import top.sywyar.pixivdownload.common.web.SafeRequestPath;
import top.sywyar.pixivdownload.i18n.AppLocaleResolver;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.maintenance.MaintenanceCoordinator;
import top.sywyar.pixivdownload.plugin.BuiltInPlugins;
import top.sywyar.pixivdownload.plugin.registry.web.LandingRegistry;
import top.sywyar.pixivdownload.plugin.registry.web.NavigationRegistry;
import top.sywyar.pixivdownload.plugin.api.web.NavigationMarkers;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.registry.route.RouteAccessRegistry;
import top.sywyar.pixivdownload.plugin.registry.route.StartupRouteRegistry;
import top.sywyar.pixivdownload.plugin.api.web.AccessPolicy;
import top.sywyar.pixivdownload.plugin.api.web.ApiErrorResponse;
import top.sywyar.pixivdownload.plugin.api.web.Audience;
import top.sywyar.pixivdownload.plugin.api.web.HttpMethod;
import top.sywyar.pixivdownload.plugin.api.web.StartupRouteContext;
import top.sywyar.pixivdownload.plugin.api.web.WebRouteContribution;
import top.sywyar.pixivdownload.quota.RateLimitService;
import top.sywyar.pixivdownload.setup.guest.GuestInviteService;
import top.sywyar.pixivdownload.setup.guest.GuestInviteSession;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@Component
@Order(1)
@Slf4j
public class AuthFilter extends OncePerRequestFilter {

    /** 访客邀请 cookie 名（浏览器会话 cookie，不带 Max-Age）。 */
    public static final String INVITE_COOKIE = GuestInviteSession.COOKIE_NAME;

    /** 程序密钥的 RFC 6750 质询；realm 是协议值，不随界面语言变化。 */
    private static final String BEARER_CHALLENGE = "Bearer realm=\"PixivDownloader\"";

    private final SetupService setupService;
    private final StaticResourceRateLimitService staticResourceRateLimitService;
    private final RateLimitService rateLimitService;
    private final AppLocaleResolver localeResolver;
    private final AppMessages messages;
    private final ObjectProvider<MaintenanceCoordinator> maintenanceCoordinatorProvider;
    private final GuestInviteService guestInviteService;
    private final GuiTokenProvider guiTokenProvider;
    private final ProgramKeyService programKeys;
    @Autowired @Qualifier("requestMappingHandlerMapping")
    private ObjectProvider<RequestMappingHandlerMapping> mvcMappings;

    // 一次请求只解析一次有效路由，公开、邀请、管理员与本地放行共享同一方法和特异性结论。
    // 派生清单仅用于静态资源限流分类，不参与授权。
    private final RouteAccessRegistry routeAccessRegistry;

    /** 默认启动落点注册中心：{@code /redirect} 据此按模式选定首选插件落点（缺失则回退 / 兜底）。 */
    private final StartupRouteRegistry startupRouteRegistry;
    private final NavigationRegistry navigationRegistry;

    /** 落点注册中心：GET {@code /invite} 兑换成功后据此按受邀访客落点优先级解析目标页（缺失则回登录页），与导航排序解耦。 */
    private final LandingRegistry landingRegistry;

    /** 最近一次派生结果（含其来源快照引用）；仅当 registry 快照引用变化时按需重算，避免每个请求重复派生。 */
    private volatile DerivedRouteAccess derivedRouteAccess;

    @Value("${server.ssl.enabled:false}")
    private boolean sslEnabled;

    /**
     * 运行期构造：注入 Spring 管理的 {@link RouteAccessRegistry}（反映已启用插件，请求侧读取其不可变快照）、
     * {@link StartupRouteRegistry}（{@code /redirect} 默认落点）与 {@link LandingRegistry}（GET 邀请兑换落点）。
     */
    public AuthFilter(SetupService setupService,
                      StaticResourceRateLimitService staticResourceRateLimitService,
                      RateLimitService rateLimitService,
                      AppLocaleResolver localeResolver,
                      AppMessages messages,
                      ObjectProvider<MaintenanceCoordinator> maintenanceCoordinatorProvider,
                      GuestInviteService guestInviteService,
                      GuiTokenProvider guiTokenProvider,
                      RouteAccessRegistry routeAccessRegistry,
                      StartupRouteRegistry startupRouteRegistry,
                      LandingRegistry landingRegistry,
                      NavigationRegistry navigationRegistry) {
        this(setupService, staticResourceRateLimitService, rateLimitService, localeResolver, messages, maintenanceCoordinatorProvider, guestInviteService, guiTokenProvider, routeAccessRegistry, startupRouteRegistry, landingRegistry, navigationRegistry, null);
    }

    @Autowired
    public AuthFilter(SetupService setupService,
                      StaticResourceRateLimitService staticResourceRateLimitService,
                      RateLimitService rateLimitService,
                      AppLocaleResolver localeResolver,
                      AppMessages messages,
                      ObjectProvider<MaintenanceCoordinator> maintenanceCoordinatorProvider,
                      GuestInviteService guestInviteService,
                      GuiTokenProvider guiTokenProvider,
                      RouteAccessRegistry routeAccessRegistry,
                      StartupRouteRegistry startupRouteRegistry,
                      LandingRegistry landingRegistry,
                      NavigationRegistry navigationRegistry,
                      ProgramKeyService programKeys) {
        this.programKeys = programKeys;
        this.setupService = setupService;
        this.staticResourceRateLimitService = staticResourceRateLimitService;
        this.rateLimitService = rateLimitService;
        this.localeResolver = localeResolver;
        this.messages = messages;
        this.maintenanceCoordinatorProvider = maintenanceCoordinatorProvider;
        this.guestInviteService = guestInviteService;
        this.guiTokenProvider = guiTokenProvider;
        this.routeAccessRegistry = routeAccessRegistry;
        this.startupRouteRegistry = startupRouteRegistry;
        this.landingRegistry = landingRegistry;
        this.navigationRegistry = navigationRegistry;
    }

    /**
     * 单元测试 / 启动期校验构造（自定义 {@link RouteAccessRegistry}）：启动落点与落点 registry 从内置插件清单构建，
     * 与运行期一致；供 {@code AuthFilterRegistrySnapshotTest} 注入定制路由 registry 而落点 / 邀请兑换落点行为不变。
     */
    public AuthFilter(SetupService setupService,
                      StaticResourceRateLimitService staticResourceRateLimitService,
                      RateLimitService rateLimitService,
                      AppLocaleResolver localeResolver,
                      AppMessages messages,
                      ObjectProvider<MaintenanceCoordinator> maintenanceCoordinatorProvider,
                      GuestInviteService guestInviteService,
                      GuiTokenProvider guiTokenProvider,
                      RouteAccessRegistry routeAccessRegistry) {
        this(setupService, staticResourceRateLimitService, rateLimitService, localeResolver,
                messages, maintenanceCoordinatorProvider, guestInviteService, guiTokenProvider,
                routeAccessRegistry,
                new StartupRouteRegistry(new PluginRegistry(BuiltInPlugins.createAll())),
                new LandingRegistry(new PluginRegistry(BuiltInPlugins.createAll())),
                new NavigationRegistry(new PluginRegistry(BuiltInPlugins.createAll())));
    }

    /**
     * Spring 上下文外构造（单元测试 / 启动期校验）：从内置插件清单构建与运行期一致的路由 / 落点 registry，
     * 与 {@code RouteAccessMirrorTest} / {@code RouteAccessRegistryTest} 用同一组合根，
     * 因此过滤与默认落点行为与运行期注册完全等价。
     */
    public AuthFilter(SetupService setupService,
                      StaticResourceRateLimitService staticResourceRateLimitService,
                      RateLimitService rateLimitService,
                      AppLocaleResolver localeResolver,
                      AppMessages messages,
                      ObjectProvider<MaintenanceCoordinator> maintenanceCoordinatorProvider,
                      GuestInviteService guestInviteService,
                      GuiTokenProvider guiTokenProvider) {
        this(setupService, staticResourceRateLimitService, rateLimitService, localeResolver,
                messages, maintenanceCoordinatorProvider, guestInviteService, guiTokenProvider,
                new RouteAccessRegistry(new PluginRegistry(BuiltInPlugins.createAll())));
    }

    /** monitor 受保护 ← 阻挡匿名访客的策略（管理员专属 + 受邀访客只读）。 */
    private static boolean isMonitorPolicy(AccessPolicy policy) {
        return policy == AccessPolicy.ADMIN || policy == AccessPolicy.INVITED_GUEST;
    }

    /** 访客邀请白名单 ← 放行受邀访客只读的策略。 */
    private static boolean isGuestPolicy(AccessPolicy policy) {
        return policy == AccessPolicy.INVITED_GUEST || policy == AccessPolicy.VISITOR_AND_INVITED_GUEST;
    }

    /**
     * 由路由快照派生的静态资源限流分类，不作为授权依据。
     * {@code sourceSnapshot} 是派生它的快照引用，请求侧据此判断快照是否被 register/unregister 整体替换、
     * 决定是否需要重新派生（见 {@link #currentAccess()}）。
     */
    private record DerivedRouteAccess(
            List<RouteAccessRegistry.RegisteredRoute> sourceSnapshot,
            List<String> publicPageStaticPrefixPaths,
            Set<String> publicStaticExactPaths,
            Set<String> guestAllowedStaticExact,
            List<String> guestAllowedPrefix) {
    }

    /**
     * 请求侧读取当前路由访问派生清单：直接取 {@link RouteAccessRegistry} 的不可变快照引用，
     * 仅当快照被 register/unregister 整体替换（引用变化）时才重新派生并缓存，
     * 因此插件注册 / 注销后过滤判定随新快照更新，又不必每个请求重算。
     */
    private DerivedRouteAccess currentAccess() {
        List<RouteAccessRegistry.RegisteredRoute> snapshot = routeAccessRegistry.routes();
        DerivedRouteAccess cached = this.derivedRouteAccess;
        if (cached == null || cached.sourceSnapshot() != snapshot) {
            cached = derive(snapshot);
            this.derivedRouteAccess = cached;
        }
        return cached;
    }

    /** 仅派生公开与邀请访客静态资源的限流分类；授权使用本次请求已解析的有效路由。 */
    private static DerivedRouteAccess derive(List<RouteAccessRegistry.RegisteredRoute> routes) {
        return new DerivedRouteAccess(
                routes,
                prefixPaths(routes, policy -> policy == AccessPolicy.PUBLIC),
                exactPaths(routes, policy -> policy == AccessPolicy.PUBLIC, method -> true),
                exactPaths(routes, AuthFilter::isGuestPolicy,
                        methods -> !methods.contains(HttpMethod.POST), AuthFilter::isStaticResource),
                prefixPaths(routes, AuthFilter::isGuestPolicy));
    }

    private static boolean isPrefixPattern(String pattern) {
        return pattern.endsWith("**");
    }

    /** {@code /x/**} → 历史前缀 {@code /x/}；{@code /api/authors**} → {@code /api/authors}（去末尾两字符）。 */
    private static String toPrefixMatcher(String pattern) {
        return pattern.substring(0, pattern.length() - 2);
    }

    private static Set<String> exactPaths(List<RouteAccessRegistry.RegisteredRoute> routes,
                                          Predicate<AccessPolicy> policyFilter,
                                          Predicate<Set<HttpMethod>> methodFilter) {
        return exactPaths(routes, policyFilter, methodFilter, path -> true);
    }

    private static Set<String> exactPaths(List<RouteAccessRegistry.RegisteredRoute> routes,
                                          Predicate<AccessPolicy> policyFilter,
                                          Predicate<Set<HttpMethod>> methodFilter,
                                          Predicate<String> pathFilter) {
        return routes.stream()
                .map(RouteAccessRegistry.RegisteredRoute::route)
                .filter(route -> !isPrefixPattern(route.pathPattern()))
                .filter(route -> policyFilter.test(route.accessPolicy()))
                .filter(route -> methodFilter.test(route.methods()))
                .map(WebRouteContribution::pathPattern)
                .filter(pathFilter)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static List<String> prefixPaths(List<RouteAccessRegistry.RegisteredRoute> routes,
                                            Predicate<AccessPolicy> policyFilter) {
        return routes.stream()
                .map(RouteAccessRegistry.RegisteredRoute::route)
                .filter(route -> isPrefixPattern(route.pathPattern()))
                .filter(route -> policyFilter.test(route.accessPolicy()))
                .map(route -> toPrefixMatcher(route.pathPattern()))
                .distinct()
                .collect(Collectors.toUnmodifiableList());
    }

    private static boolean startsWithAny(String path, List<String> prefixes) {
        for (String prefix : prefixes) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res,
                                    FilterChain chain) throws IOException, ServletException {
        String method = req.getMethod();

        if ("OPTIONS".equalsIgnoreCase(method)) {
            chain.doFilter(req, res);
            return;
        }

        Optional<String> safePath = SafeRequestPath.resolve(req);
        if (safePath.isEmpty()) {
            sendJsonError(req, res, HttpServletResponse.SC_NOT_FOUND,
                    "error.request.not-found", "Requested resource not found");
            return;
        }
        String path = safePath.get();
        Optional<RouteAccessRegistry.RegisteredRoute> resolvedRoute =
                routeAccessRegistry.resolve(path, toHttpMethod(method));
        AccessPolicy policy = resolvedRoute.map(route -> route.route().accessPolicy()).orElse(null);

        // 容器探针端点：health / info 永远放行，且置于维护窗口与限流检查之前，
        // 确保维护期间探针不会因 503 而被编排器误判为不健康。仅这两个端点对外暴露
        // （management.endpoints.web.exposure.include），不会泄露配置/环境变量。
        if (isPublicActuatorEndpoint(path)) {
            chain.doFilter(req, res);
            return;
        }
        if (isActuatorEndpoint(path)) {
            sendJsonError(req, res, HttpServletResponse.SC_NOT_FOUND,
                    "error.request.not-found", "Requested resource not found");
            return;
        }

        // 维护窗口：所有请求看到维护提示页，API 调用返回 503
        MaintenanceCoordinator maintenance = maintenanceCoordinatorProvider.getIfAvailable();
        if (maintenance != null && maintenance.isPaused()) {
            if (isMaintenancePageResource(path)) {
                chain.doFilter(req, res);
                return;
            }
            if (isApi(path)) {
                res.setHeader(HttpHeaders.RETRY_AFTER, "60");
                String message = messages.getOrDefault(localeResolver.resolveLocale(req),
                        "auth.maintenance", "服务正在维护，请稍后再试");
                ApiErrorWriter.write(res, 503, "auth.maintenance", message);
            } else {
                res.sendRedirect("/maintenance.html");
            }
            return;
        }

        boolean inspectingOwnKey = resolvedRoute.filter(route -> ProgramAccess.isSelf(route, toHttpMethod(method))).isPresent();
        if (inspectingOwnKey && req.getHeader(HttpHeaders.AUTHORIZATION) == null) {
            res.setHeader(HttpHeaders.WWW_AUTHENTICATE, BEARER_CHALLENGE);
            sendJsonError(req, res, 401, "auth.program.required", "This operation requires a program key");
            return;
        }

        // 显式程序凭据不回退会话、本机或匿名身份；业务权限仍绑定本次有效路由。
        if (req.getHeader(HttpHeaders.AUTHORIZATION) != null) {
            var headers = req.getHeaders(HttpHeaders.AUTHORIZATION);
            String authorization = headers.nextElement();
            if (headers.hasMoreElements() || authorization.length() > 128
                    || !authorization.startsWith("Bearer ") || programKeys == null
                    || SessionUtils.extractToken(req) != null || GuestInviteSession.isGuestRequest(req)
                    || req.getHeader(GuiTokenProvider.HEADER_NAME) != null) {
                res.setHeader(HttpHeaders.WWW_AUTHENTICATE, BEARER_CHALLENGE + ", error=\"invalid_request\"");
                sendJsonError(req, res, 401, "auth.program.invalid", "Program credentials are invalid or mixed with another identity");
                return;
            }
            top.sywyar.pixivdownload.plugin.api.web.ProgramIdentity identity;
            try { identity = programKeys.authenticate(authorization.substring(7), req.getRemoteAddr(), req.isSecure()); }
            catch (ProgramKeyStore.HttpsRequired insecure) {
                sendJsonError(req, res, 403, "auth.program.https-required", "Use HTTPS for this program key outside the local network");
                return;
            }
            catch (IOException unavailable) {
                sendJsonError(req, res, 503, "auth.program.unavailable", "Program key storage is unavailable");
                return;
            }
            if (identity == null) {
                res.setHeader(HttpHeaders.WWW_AUTHENTICATE, BEARER_CHALLENGE + ", error=\"invalid_token\"");
                sendJsonError(req, res, 401, "auth.program.invalid", "Program credentials or source IP are not accepted");
                return;
            }
            var permission = resolvedRoute.map(route -> route.route().programPermissions().get(toHttpMethod(method))).orElse(null);
            if (permission == null) {
                if (resolvedRoute.isEmpty()) {
                    sendJsonError(req, res, 404, "error.request.not-found", "Requested resource not found");
                    return;
                }
                if (isApi(path) && mvcMappings != null) {
                    // A route policy may cover several MVC methods. Consult the dispatcher only to classify a denial.
                    ServletRequestPathUtils.parseAndCache(req);
                    boolean missing = false;
                    try {
                        missing = mvcMappings.getObject().getHandler(req) == null;
                    } catch (HttpRequestMethodNotSupportedException unsupported) {
                        res.setHeader(HttpHeaders.ALLOW, unsupported.getHeaders().getFirst(HttpHeaders.ALLOW));
                        sendJsonError(req, res, 405, "http.status.405", "Method not allowed");
                        return;
                    } catch (Exception unavailable) {
                        // A media or other mapping condition cannot grant program access.
                        log.debug("MVC lookup for denied program request failed", unavailable);
                    }
                    if (missing && resolvedRoute.get().route().pathPattern().contains("*")) {
                        sendJsonError(req, res, 404, "error.request.not-found", "Requested resource not found");
                        return;
                    }
                }
                sendJsonError(req, res, 403, "auth.program.unsupported", "This operation does not accept program keys");
                return;
            }
            String owner = resolvedRoute.orElseThrow().pluginId();
            if (!inspectingOwnKey && !identity.permits(owner, permission.id())) {
                // 声明了程序权限的路由直接给出所需权限，调用方据此请管理员授权，而不是猜测缺少哪一项。
                sendProgramCredentialError(req, res, 403, "auth.program.forbidden", "auth.program.forbidden.permission",
                        "This program key lacks the {0} permission required for this operation",
                        owner + ":" + permission.id());
                return;
            }
            req.setAttribute(top.sywyar.pixivdownload.plugin.api.web.ProgramIdentity.REQUEST_ATTRIBUTE, identity);
            chain.doFilter(req, res);
            return;
        }

        // 公开身份检查与所有业务路由共享访客身份；失效邀请也不能回退到管理员或本机权限。
        GuestInviteSession guestSession = resolveGuestInviteSession(req);
        if (guestSession != null) {
            req.setAttribute(GuestInviteSession.REQUEST_ATTR, guestSession);
        }
        if (GuestInviteSession.isGuestRequest(req) && !isPublic(path, policy)
                && !path.equals("/invite") && !path.equals("/redirect")
                && (guestSession == null || !isAllowedForGuestInvite(resolvedRoute, method))) {
            sendJsonError(req, res, 403, "guest.invite.forbidden", "该资源不在你的可见范围内");
            return;
        }

        // GUI 路径：必须同时满足本地请求 + 有效的 GUI 令牌，通过后跳过所有后续过滤逻辑。
        if (path.startsWith("/api/gui/")) {
            if (!isValidGuiRequest(req)) {
                sendJsonError(req, res, 403, "auth.local-only", "Forbidden: local access only");
                return;
            }
            if (!isValidDeclaredGuiActionOwner(req, path, resolvedRoute)) {
                sendJsonError(req, res, 403, "auth.local-only", "Forbidden: GUI action owner mismatch");
                return;
            }
            chain.doFilter(req, res);
            return;
        }

        if (path.equals("/redirect")) {
            if (setupService.isAdminLoggedIn(req)) {
                res.sendRedirect(navigationRegistry.preferredHref(
                        NavigationMarkers.PREFERRED_DOWNLOAD_WORKBENCH, Audience.ADMIN).orElse("/home.html"));
            } else if (setupService.isIntroMode()) {
                res.sendRedirect("/intro.html");
            } else {
                StartupRouteContext startupContext = "multi".equals(setupService.getMode())
                        ? StartupRouteContext.MULTI : StartupRouteContext.SOLO;
                res.sendRedirect(startupRouteRegistry.resolvePath(startupContext).orElse("/login.html"));
            }
            return;
        }

        // 代理自动配置（PAC）：仅本地客户端可获取。它会暴露后端配置的代理 host:port，
        // 属于本地配置（语义同 setup 向导），因此本地放行、绕过鉴权与限流；非本地请求拒绝。
        if (path.equals("/proxy.pac")) {
            if (!NetworkUtils.isLocalRequest(req)) {
                sendTextError(req, res, 403, "auth.local-only", "Forbidden: local access only");
                return;
            }
            chain.doFilter(req, res);
            return;
        }

        if (shouldApplyStaticResourceRateLimit(req, path)) {
            // 邀请访客按邀请码限流（两种模式均生效）；其余未登录流量按客户端 IP 限流。
            boolean allowed = guestSession != null
                    ? staticResourceRateLimitService.isAllowedForInvite("invite:" + guestSession.code())
                    : staticResourceRateLimitService.isAllowed(req.getRemoteAddr());
            if (!allowed) {
                log.warn(messages.getForLog("static-resource.log.rate-limit.exceeded", req.getRemoteAddr(), path));
                sendTextError(req, res, 429, "auth.too-many-requests", "Too Many Requests");
                return;
            }
        }

        if (isSetupOnlyStaticResource(path)
                && !setupService.isSetupComplete()
                && !NetworkUtils.isLocalRequest(req)) {
            sendTextError(req, res, 403, "auth.local-only", "Forbidden: local access only");
            return;
        }

        // 邀请链接仅预填兑换界面；建立访客会话必须经过受保护的 POST。
        if (path.equals("/invite")) {
            handleInviteRedeemRedirect(req, res);
            return;
        }

        if (isPublic(path, policy)) {
            chain.doFilter(req, res);
            return;
        }

        if (isSetupPagePath(path)) {
            if (!NetworkUtils.isLocalRequest(req)) {
                sendJsonError(req, res, 403, "auth.local-only", "Forbidden: local access only");
                return;
            }
            chain.doFilter(req, res);
            return;
        }

        if (!setupService.isSetupComplete()) {
            if (isApi(path)) {
                sendJsonError(req, res, 503, "auth.setup-required", "Setup required");
            } else {
                res.sendRedirect("/setup.html");
            }
            return;
        }

        if (guestSession != null && isAllowedForGuestInvite(resolvedRoute, method)) {
            if (isApi(path)) {
                if (!rateLimitService.isAllowedForInvite("invite:" + guestSession.code())) {
                    res.setHeader(HttpHeaders.RETRY_AFTER, "60");
                    sendJsonError(req, res, 429, "auth.too-many-requests", "Too Many Requests");
                    return;
                }
            }
            guestInviteService.recordHit(guestSession.id());
            chain.doFilter(req, res);
            return;
        }

        if (isMonitorPolicy(policy)) {
            String token = SessionUtils.extractToken(req);
            boolean adminValid = setupService.isValidSession(token);
            if (!adminValid) {
                rejectUnauthenticated(req, res, path, resolvedRoute);
                return;
            }
            if ("multi".equals(setupService.getMode())) {
                ensureUserUuidCookie(req, res);
            }
            chain.doFilter(req, res);
            return;
        }

        if (policy == AccessPolicy.LOCAL) {
            if (isLocalOnlyMove(path, toHttpMethod(method))) {
                if (!NetworkUtils.isLocalRequest(req)) {
                    sendJsonError(req, res, 403, "auth.local-only", "Forbidden: local access only");
                    return;
                }
                chain.doFilter(req, res);
                return;
            }
            if (NetworkUtils.isLocalRequest(req)) {
                chain.doFilter(req, res);
                return;
            }
        }

        // 全 URL 声明守卫：命中不了任何「path + method」已声明路由的请求统一 404（不再回落到访客默认放行）。
        // 真正流程性分支（actuator / 维护窗口 / GUI / proxy.pac / setup / /redirect / invite / 公开路径）已在前面
        // 各自返回；走到这里要么命中某条 VISITOR / LOCAL 等已声明路由（落默认会话 / 访客分支、保持旧可观察行为），
        // 要么是未声明伪路径（404）。method-aware：仅声明某方法的 URL 用别的方法访问视为未声明（除非另有更宽的
        // 全方法声明覆盖）。真实 controller 方法 / 静态资源由 RouteDeclarationCoverageTest 守卫均已声明、不会误伤。
        if (resolvedRoute.isEmpty()) {
            sendJsonError(req, res, HttpServletResponse.SC_NOT_FOUND,
                    "error.request.not-found", "Requested resource not found");
            return;
        }

        if ("multi".equals(setupService.getMode())) {
            boolean isAdmin = setupService.isAdminLoggedIn(req);
            if (!isAdmin && isApi(path)) {
                String uuid = UuidUtils.extractOrGenerateUuid(req);
                if (!rateLimitService.isAllowed(uuid)) {
                    res.setHeader(HttpHeaders.RETRY_AFTER, "60");
                    sendJsonError(req, res, 429, "auth.too-many-requests", "Too Many Requests");
                    return;
                }
            }
            ensureUserUuidCookie(req, res);
            chain.doFilter(req, res);
            return;
        }

        String token = SessionUtils.extractToken(req);
        if (setupService.isValidSession(token)) {
            chain.doFilter(req, res);
        } else {
            rejectUnauthenticated(req, res, path, resolvedRoute);
        }
    }

    /**
     * 没有有效管理员会话：页面导航跳转登录，API 回 401。本次方法接受程序密钥时，401 另按 RFC 6750 给出 Bearer
     * 质询、所需权限与用法说明，调用方据此改用程序密钥，而不必模拟需要人工验证的登录。
     */
    private void rejectUnauthenticated(HttpServletRequest req, HttpServletResponse res, String path,
                                       Optional<RouteAccessRegistry.RegisteredRoute> resolvedRoute) throws IOException {
        if (!isApi(path)) {
            String redirect = URLEncoder.encode(path, StandardCharsets.UTF_8);
            res.sendRedirect("/login.html?redirect=" + redirect);
            return;
        }
        Optional<String> programPermission = resolvedRoute.flatMap(route ->
                Optional.ofNullable(route.route().programPermissions().get(toHttpMethod(req.getMethod())))
                        .map(permission -> route.pluginId() + ":" + permission.id()));
        if (programPermission.isEmpty()) {
            sendJsonError(req, res, 401, "auth.unauthorized", "Unauthorized");
            return;
        }
        res.setHeader(HttpHeaders.WWW_AUTHENTICATE, BEARER_CHALLENGE);
        sendProgramCredentialError(req, res, 401, "auth.unauthorized", "auth.unauthorized.program-key",
                "Sign in as administrator, or send a program key with the {0} permission as Authorization: Bearer <key>",
                programPermission.get());
    }

    /**
     * 程序凭据错误：稳定 {@code code} 不变，说明与结构化字段给出本次路由要求的完整程序权限。
     * SDK 只允许 {@code /api/} 路由声明程序权限，因此这里始终走 JSON 错误通道。
     */
    private void sendProgramCredentialError(HttpServletRequest req, HttpServletResponse res, int status, String code,
                                            String messageKey, String defaultMessage, String permission) throws IOException {
        String message = messages.getOrDefault(localeResolver.resolveLocale(req), messageKey, defaultMessage, permission);
        ApiErrorWriter.write(res, status, new ProgramCredentialError(code, message, permission));
    }

    /** 在标准 {@code code} / {@code error} 之外携带本次路由要求的程序权限 ID（{@code owner:局部权限}）。 */
    public record ProgramCredentialError(@Schema(allowableValues = {"auth.unauthorized", "auth.program.forbidden", "DOWNLOAD_TASK_FORBIDDEN", "CREDENTIAL_FORBIDDEN"}) String code, String error, String programPermission) implements ApiErrorResponse {
    }

    /** 请求方法字符串 → contribution 的 {@link HttpMethod}；未知方法返回 {@code null}（仅命中空方法集声明）。 */
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

    private boolean isPublic(String path, AccessPolicy policy) {
        boolean completedSolo = setupService.isSetupComplete() && "solo".equals(setupService.getMode());
        if (policy == AccessPolicy.PUBLIC) {
            return true;
        }
        // setup API 保留初始化及 multi 模式的引导语义，不能覆盖更具体的权限声明。
        return policy == AccessPolicy.VISITOR && path.startsWith("/api/setup/")
                && !completedSolo;
    }

    /**
     * 对外公开的 actuator 探针端点：仅 health（含 liveness/readiness 子组）与 info。
     * 其余 actuator 端点未在 exposure 中暴露，命中此处也不会路由到任何处理器。
     */
    private boolean isPublicActuatorEndpoint(String path) {
        return path.equals("/actuator/health")
                || path.equals("/actuator/health/liveness")
                || path.equals("/actuator/health/readiness")
                || path.equals("/actuator/info");
    }

    private boolean isActuatorEndpoint(String path) {
        return path.equals("/actuator") || path.startsWith("/actuator/");
    }

    private boolean isMaintenancePageResource(String path) {
        return path.equals("/maintenance.html") || path.startsWith("/maintenance/");
    }

    private boolean isValidGuiRequest(HttpServletRequest req) {
        return GuiTokenProvider.accepts(guiTokenProvider, req);
    }

    /**
     * 声明式 GUI 配置动作额外携带聚合时绑定的插件 owner；仅这类请求需要把 owner 与当前路由快照再次比对。
     * 普通 GUI 调用没有该请求头，保持原有本机 + GUI token 边界。
     *
     * <p>这里要求当前有效路由本身就是精确路径、接受 POST、策略为 GUI 且 owner 相同。这样即使插件的
     * contribution getter 有状态、在 GUI 聚合与后端实际发布之间返回了不同路由，也不能借宽前缀或其它
     * 插件已发布的端点发送聚合出的敏感配置 payload。
     */
    private boolean isValidDeclaredGuiActionOwner(HttpServletRequest req, String path,
                                                Optional<RouteAccessRegistry.RegisteredRoute> resolvedRoute) {
        String claimedOwner = req.getHeader(GuiActionInvocationHeaders.PLUGIN_OWNER);
        if (claimedOwner == null) {
            return true;
        }
        if (claimedOwner.isBlank()
                || !claimedOwner.equals(claimedOwner.trim())
                || !"POST".equalsIgnoreCase(req.getMethod())) {
            return false;
        }
        return resolvedRoute
                .filter(registered -> claimedOwner.equals(registered.pluginId()))
                .map(registered -> registered.route())
                .filter(route -> route.accessPolicy() == AccessPolicy.GUI)
                .filter(route -> path.equals(route.pathPattern()))
                .filter(route -> route.acceptsMethod(HttpMethod.POST))
                .isPresent();
    }

    private boolean isIntroLoginPublicPageOrResource(String path) {
        return path.equals("/")
                || path.equals("/index")
                || path.equals("/index.html")
                || path.equals("/login.html")
                || path.equals("/intro.html")
                || path.equals("/intro-canary.html")
                || currentAccess().publicStaticExactPaths().contains(path)
                || isPublicPageStaticResource(path);
    }

    private boolean isSetupOnlyStaticResource(String path) {
        return path.equals("/js/pixiv-lang-switcher.js")
                || path.equals("/js/pixiv-theme.js")
                || path.startsWith("/setup/");
    }

    private boolean isPublicPageStaticResource(String path) {
        return startsWithAny(path, currentAccess().publicPageStaticPrefixPaths());
    }

    private boolean isSetupPagePath(String path) {
        return path.equals("/setup.html") || path.startsWith("/setup/");
    }

    private boolean isApi(String path) {
        return path.startsWith("/api/");
    }

    private static boolean isStaticResource(String path) {
        if (path == null || path.isBlank() || path.equals("/redirect") || path.startsWith("/api/")) {
            return false;
        }
        if (path.equals("/") || path.equals("/index")
                || path.startsWith("/js/")
                || path.startsWith("/vendor/")
                || path.startsWith("/userscripts/")) {
            return true;
        }
        int lastSlash = path.lastIndexOf('/');
        int lastDot = path.lastIndexOf('.');
        return lastDot > lastSlash;
    }

    private boolean shouldApplyStaticResourceRateLimit(HttpServletRequest req, String path) {
        if (!isStaticResource(path) && !path.equals("/invite")) {
            return false;
        }
        if (!setupService.isSetupComplete() || setupService.isAdminLoggedIn(req)) {
            return false;
        }
        String mode = setupService.getMode();
        if ("multi".equals(mode)) {
            return isStaticResource(path);
        }
        if ("solo".equals(mode)) {
            return isSoloRateLimitedPublicResource(path);
        }
        return false;
    }

    private boolean isSoloRateLimitedPublicResource(String path) {
        return isIntroLoginPublicPageOrResource(path)
                || path.equals("/invite")
                || isGuestPublicPageOrStaticResource(path);
    }

    private boolean isGuestPublicPageOrStaticResource(String path) {
        DerivedRouteAccess access = currentAccess();
        if (access.guestAllowedStaticExact().contains(path)) {
            return true;
        }
        if (!isStaticResource(path)) {
            return false;
        }
        return startsWithAny(path, access.guestAllowedPrefix());
    }

    private void sendJsonError(HttpServletRequest req, HttpServletResponse res,
                               int status, String messageCode, String defaultMessage) throws IOException {
        if (prefersHtmlErrorPage(req)) {
            res.sendError(status);
            return;
        }
        String message = messages.getOrDefault(localeResolver.resolveLocale(req), messageCode, defaultMessage);
        ApiErrorWriter.write(res, status, messageCode, message);
    }

    private void sendTextError(HttpServletRequest req, HttpServletResponse res,
                               int status, String messageCode, String defaultMessage) throws IOException {
        if (prefersHtmlErrorPage(req)) {
            res.setHeader(HttpHeaders.RETRY_AFTER, "60");
            res.sendError(status);
            return;
        }
        String message = messages.getOrDefault(localeResolver.resolveLocale(req), messageCode, defaultMessage);
        res.setStatus(status);
        res.setContentType(MediaType.TEXT_PLAIN_VALUE);
        res.setCharacterEncoding(StandardCharsets.UTF_8.name());
        res.setHeader(HttpHeaders.RETRY_AFTER, "60");
        res.getWriter().write(message);
    }

    /**
     * 浏览器页面导航（GET、非 API、Accept 含 text/html）的拒绝响应交给容器错误派发，
     * 由 Spring Boot 静态错误视图渲染 {@code /error/<status>.html} 品牌化错误页；
     * API、fetch 与 CSS/JS 等资源加载仍保持原有 JSON / 文本响应契约不变。
     */
    private static boolean prefersHtmlErrorPage(HttpServletRequest req) {
        if (!"GET".equalsIgnoreCase(req.getMethod())) {
            return false;
        }
        String path = SafeRequestPath.resolve(req).orElse("");
        if (path.startsWith("/api/")) {
            return false;
        }
        String accept = req.getHeader(HttpHeaders.ACCEPT);
        return accept != null && accept.contains(MediaType.TEXT_HTML_VALUE);
    }

    private GuestInviteSession resolveGuestInviteSession(HttpServletRequest req) {
        Cookie[] cookies = req.getCookies();
        if (cookies == null) return null;
        String code = null;
        for (Cookie c : cookies) {
            if (InstanceCookies.name(req, INVITE_COOKIE).equals(c.getName()) && c.getValue() != null && !c.getValue().isBlank()) {
                code = c.getValue();
                break;
            }
        }
        if (code == null) return null;
        Optional<GuestInviteSession> resolved;
        try {
            resolved = guestInviteService.resolveSession(code);
        } catch (Exception e) {
            log.warn("Failed to resolve invite session: {}", e.getClass().getSimpleName());
            return null;
        }
        if (resolved.isPresent()) return resolved.get();
        // 保留访客身份，直到用户显式退出或重新登录，避免后续请求自动获得管理员权限。
        return null;
    }

    private boolean isAllowedForGuestInvite(Optional<RouteAccessRegistry.RegisteredRoute> resolvedRoute, String method) {
        HttpMethod httpMethod = toHttpMethod(method);
        return resolvedRoute.filter(route -> acceptsGuestInvite(route.route(), httpMethod)).isPresent();
    }

    /** Whether the effective route explicitly permits this method for an invited guest. */
    public static boolean acceptsGuestInvite(WebRouteContribution route,
                                             HttpMethod method) {
        return isGuestPolicy(route.accessPolicy()) && (method == HttpMethod.GET || method == HttpMethod.HEAD
                || method == HttpMethod.POST && route.methods().contains(HttpMethod.POST));
    }

    /** LOCAL move requests never fall back to remote session or visitor access. */
    public static boolean isLocalOnlyMove(String path, HttpMethod method) {
        return method == HttpMethod.POST && path.contains("/downloaded/move/");
    }

    private void handleInviteRedeemRedirect(HttpServletRequest req, HttpServletResponse res) throws IOException {
        String code = req.getParameter("code");
        res.setHeader("Cache-Control", "no-store");
        res.setHeader("Referrer-Policy", "no-referrer");
        if (code == null || code.length() > 64 || !code.trim().toUpperCase(java.util.Locale.ROOT).matches("[A-HJ-NP-Z2-9]{24}")) {
            res.sendRedirect("/login.html");
            return;
        }
        // fragment 仅供浏览器预填，不再次作为 URL 查询发送给服务器或资源来源。
        res.sendRedirect("/login.html#invite=" + code.trim().toUpperCase(java.util.Locale.ROOT));
    }

    private void ensureUserUuidCookie(HttpServletRequest req, HttpServletResponse res) {
        Cookie[] cookies = req.getCookies();
        if (cookies != null) {
            for (Cookie c : cookies) {
                if (InstanceCookies.name(req, "pixiv_user_id").equals(c.getName())
                        && UuidUtils.parseUuidV4(c.getValue()) != null) {
                    return;
                }
            }
        }

        String uuid = UuidUtils.extractOrGenerateUuid(req);
        ResponseCookie cookie = ResponseCookie.from(InstanceCookies.name(req, "pixiv_user_id"), uuid)
                .path("/")
                .maxAge(Duration.ofDays(30))
                .sameSite("Strict")
                .httpOnly(true)
                .secure(sslEnabled)
                .build();
        res.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
