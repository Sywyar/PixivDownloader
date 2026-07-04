package top.sywyar.pixivdownload.setup;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import top.sywyar.pixivdownload.common.GuiTokenProvider;
import top.sywyar.pixivdownload.common.NetworkUtils;
import top.sywyar.pixivdownload.common.SessionUtils;
import top.sywyar.pixivdownload.common.UuidUtils;
import top.sywyar.pixivdownload.common.ErrorResponse;
import top.sywyar.pixivdownload.i18n.AppLocaleResolver;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.maintenance.MaintenanceCoordinator;
import top.sywyar.pixivdownload.plugin.BuiltInPlugins;
import top.sywyar.pixivdownload.plugin.registry.LandingRegistry;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.registry.RouteAccessRegistry;
import top.sywyar.pixivdownload.plugin.registry.StartupRouteRegistry;
import top.sywyar.pixivdownload.plugin.api.web.AccessPolicy;
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
    public static final String INVITE_COOKIE = "pixiv_invite_token";

    /** request attribute 标记：邀请会话是否已在本次请求内解析过（用于缓存，避免重复查库）。 */
    private static final String GUEST_SESSION_RESOLVED_ATTR = "pixiv.guestInviteSessionResolved";

    private final SetupService setupService;
    private final StaticResourceRateLimitService staticResourceRateLimitService;
    private final RateLimitService rateLimitService;
    private final AppLocaleResolver localeResolver;
    private final AppMessages messages;
    private final ObjectProvider<MaintenanceCoordinator> maintenanceCoordinatorProvider;
    private final GuestInviteService guestInviteService;
    private final GuiTokenProvider guiTokenProvider;

    // ── 访问控制：请求侧消费 RouteAccessRegistry 的不可变快照（register/unregister 会整体替换快照引用，
    // 故插件注册 / 注销后过滤判定随新快照更新；安全边界不依赖构造期静态副本）。两条路径：
    //   ① monitor 受保护 + 「未声明即 404」 ← 经 routeAccessRegistry.resolve(path, method) /
    //      isDeclared(path, method) 解析「最具体声明 + 方法」的有效访问策略：窄声明（精确 / 长前缀 / 显式方法）
    //      覆盖宽前缀，宽 ADMIN 前缀不再吞掉其下更窄的非 monitor 端点；monitor ← AccessPolicy ∈ {ADMIN,
    //      INVITED_GUEST}；命中不了任何「path + method」声明的请求统一 404（不再回落访客默认放行）。
    //   ② 访客白名单 / 公开 / 本地放行清单 ← 仍由 currentAccess() 按访问策略从快照派生（见 derive()）：
    //      访客白名单 ← {INVITED_GUEST, VISITOR_AND_INVITED_GUEST}（GET/HEAD 收窄由下方谓词承载）；
    //      公开 ← PUBLIC；本地放行特例 ← LOCAL；VISITOR / GUI / ACTUATOR_PUBLIC 不派生进任何清单
    //      （VISITOR 落默认会话 / 访客分支、GUI 与 actuator 由内联分支判定，声明只为纳入归属 / 镜像 / 守卫）。
    // 前缀模式以 ** 结尾，去掉末尾 ** 即还原为 startsWith 前缀字符串（含 /api/authors 这类无尾斜杠前缀）。
    private final RouteAccessRegistry routeAccessRegistry;

    /** 默认启动落点注册中心：{@code /redirect} 据此按模式选定首选插件落点（缺失则回退 / 兜底）。 */
    private final StartupRouteRegistry startupRouteRegistry;

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
                      LandingRegistry landingRegistry) {
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
                new LandingRegistry(new PluginRegistry(BuiltInPlugins.createAll())));
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
     * 由 {@link RouteAccessRegistry} 某一不可变快照派生出的各访问清单（与历史八类硬编码清单同形态）。
     * {@code sourceSnapshot} 是派生它的快照引用，请求侧据此判断快照是否被 register/unregister 整体替换、
     * 决定是否需要重新派生（见 {@link #currentAccess()}）。
     */
    private record DerivedRouteAccess(
            List<RouteAccessRegistry.RegisteredRoute> sourceSnapshot,
            List<String> publicPageStaticPrefixPaths,
            Set<String> publicStaticExactPaths,
            Set<String> guestAllowedStaticExact,
            Set<String> guestAllowedExact,
            Set<String> guestAllowedPostExact,
            List<String> guestAllowedPrefix,
            List<String> localAccessApiPrefixes,
            Set<String> localAccessApiExact) {
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

    /**
     * 把一份路由快照按访问策略折叠成访客白名单 / 公开 / 本地放行清单（字段顺序与 {@link DerivedRouteAccess}
     * 一致）。monitor 受保护与「未声明即 404」不在此派生——由 {@link RouteAccessRegistry#resolve} /
     * {@link RouteAccessRegistry#isDeclared(String, HttpMethod)} 按「path + method 命中的最具体声明」解析。
     */
    private static DerivedRouteAccess derive(List<RouteAccessRegistry.RegisteredRoute> routes) {
        return new DerivedRouteAccess(
                routes,
                prefixPaths(routes, policy -> policy == AccessPolicy.PUBLIC),
                exactPaths(routes, policy -> policy == AccessPolicy.PUBLIC, method -> true),
                exactPaths(routes, AuthFilter::isGuestPolicy,
                        methods -> !methods.contains(HttpMethod.POST), AuthFilter::isStaticResource),
                exactPaths(routes, AuthFilter::isGuestPolicy,
                        methods -> !methods.contains(HttpMethod.POST), path -> !isStaticResource(path)),
                exactPaths(routes, AuthFilter::isGuestPolicy, methods -> methods.contains(HttpMethod.POST)),
                prefixPaths(routes, AuthFilter::isGuestPolicy),
                prefixPaths(routes, policy -> policy == AccessPolicy.LOCAL),
                exactPaths(routes, policy -> policy == AccessPolicy.LOCAL, method -> true));
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
        String path = req.getRequestURI();
        String method = req.getMethod();

        if ("OPTIONS".equalsIgnoreCase(method)) {
            chain.doFilter(req, res);
            return;
        }

        // 容器探针端点：health / info 永远放行，且置于维护窗口与限流检查之前，
        // 确保维护期间探针不会因 503 而被编排器误判为不健康。仅这两个端点对外暴露
        // （management.endpoints.web.exposure.include），不会泄露配置/环境变量。
        if (isPublicActuatorEndpoint(path)) {
            chain.doFilter(req, res);
            return;
        }
        if (isActuatorEndpoint(path)) {
            res.sendError(HttpServletResponse.SC_NOT_FOUND);
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
                res.setStatus(503);
                res.setHeader(HttpHeaders.RETRY_AFTER, "60");
                res.setContentType(MediaType.APPLICATION_JSON_VALUE);
                res.setCharacterEncoding(StandardCharsets.UTF_8.name());
                String message = messages.getOrDefault(localeResolver.resolveLocale(req),
                        "auth.maintenance", "服务正在维护，请稍后再试");
                res.getWriter().write(new ObjectMapper()
                        .writeValueAsString(new ErrorResponse(message)));
            } else {
                res.sendRedirect("/maintenance.html");
            }
            return;
        }

        // GUI 路径：必须同时满足本地请求 + 有效的 GUI 令牌，通过后跳过所有后续过滤逻辑。
        if (path.startsWith("/api/gui/")) {
            if (!isValidGuiRequest(req)) {
                sendJsonError(req, res, 403, "auth.local-only", "Forbidden: local access only");
                return;
            }
            chain.doFilter(req, res);
            return;
        }

        if (path.equals("/redirect")) {
            if (setupService.isIntroMode()) {
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
            GuestInviteSession inviteSession = resolveGuestInviteSessionCached(req, res);
            boolean allowed = inviteSession != null
                    ? staticResourceRateLimitService.isAllowedForInvite("invite:" + inviteSession.code())
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

        // 邀请兑换通过 GET /invite?code=...：服务端尝试发 cookie 并 302 到画廊
        if (path.equals("/invite")) {
            handleInviteRedeemRedirect(req, res);
            return;
        }

        if (isPublic(path)) {
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

        // 解析访客邀请会话（若 cookie 有效）：挂到 request attribute，用于后续过滤与单作品守卫
        GuestInviteSession guestSession = resolveGuestInviteSessionCached(req, res);

        if (guestSession != null && isAllowedForGuestInvite(path, method)) {
            if (isApi(path)) {
                if (!rateLimitService.isAllowedForInvite("invite:" + guestSession.code())) {
                    sendJsonError(req, res, 429, "auth.too-many-requests", "Too Many Requests");
                    return;
                }
            }
            guestInviteService.recordHit(guestSession.id());
            chain.doFilter(req, res);
            return;
        }

        if (isMonitorProtected(path, method)) {
            String token = SessionUtils.extractToken(req);
            boolean adminValid = setupService.isValidSession(token);
            if (!adminValid) {
                if (guestSession != null) {
                    // guest 携带 cookie 但越界：禁止访问
                    sendJsonError(req, res, 403, "guest.invite.forbidden",
                            "该资源不在你的可见范围内");
                    return;
                }
                if (isApi(path)) {
                    sendJsonError(req, res, 401, "auth.unauthorized", "Unauthorized");
                } else {
                    String redirect = URLEncoder.encode(path, StandardCharsets.UTF_8);
                    res.sendRedirect("/login.html?redirect=" + redirect);
                }
                return;
            }
            if ("multi".equals(setupService.getMode())) {
                ensureUserUuidCookie(req, res);
            }
            chain.doFilter(req, res);
            return;
        }

        // 已识别为访客但未命中受保护路径（即非 monitor 范围内）：禁止越界（除非是 isPublic 路径，已在前面放行）
        if (guestSession != null) {
            sendJsonError(req, res, 403, "guest.invite.forbidden",
                    "该资源不在你的可见范围内");
            return;
        }

        DerivedRouteAccess access = currentAccess();
        if (startsWithAny(path, access.localAccessApiPrefixes()) || access.localAccessApiExact().contains(path)
                || isNovelDownloadedCheck(path)) {
            if ("POST".equalsIgnoreCase(method) && path.contains("/downloaded/move/")) {
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
        if (!routeAccessRegistry.isDeclared(path, toHttpMethod(method))) {
            res.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        if ("multi".equals(setupService.getMode())) {
            boolean isAdmin = setupService.isAdminLoggedIn(req);
            if (!isAdmin && isApi(path)) {
                String uuid = UuidUtils.extractOrGenerateUuid(req);
                if (!rateLimitService.isAllowed(uuid)) {
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
        } else if (isApi(path)) {
            sendJsonError(req, res, 401, "auth.unauthorized", "Unauthorized");
        } else {
            String redirect = URLEncoder.encode(path, StandardCharsets.UTF_8);
            res.sendRedirect("/login.html?redirect=" + redirect);
        }
    }

    /**
     * 该请求（路径 + 方法）命中的<b>最具体</b>已声明路由的访问策略是否属 monitor 受保护（ADMIN / INVITED_GUEST）。
     * 经 {@link RouteAccessRegistry#resolve} 解析有效路由——更具体的窄声明（精确 / 长前缀 / 显式方法）覆盖更宽的
     * 前缀声明，故宽 ADMIN 前缀不会吞掉其下更窄的非 monitor 端点。小说下载判重端点与作品侧 /api/downloaded/{id}
     * 同属批量下载器的「跳过已下载」判重面，不纳入 monitor 保护（见 {@link #isNovelDownloadedCheck}）。
     */
    private boolean isMonitorProtected(String path, String method) {
        if (isNovelDownloadedCheck(path)) {
            return false;
        }
        return routeAccessRegistry.resolve(path, toHttpMethod(method))
                .map(registered -> isMonitorPolicy(registered.route().accessPolicy()))
                .orElse(false);
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

    /**
     * 小说下载判重端点 {@code GET /api/novel/{novelId}/downloaded}：只读，仅返回
     * {@code downloaded}/{@code deleted} 两个布尔，供批量下载器「跳过已下载」判重。它与作品侧
     * {@code /api/downloaded/{id}} 语义对等。若按 monitor 保护处理，multi 模式非管理员会在进入控制器前收到 401，
     * 导致 skipHistory 失效、已软删除小说被重下。
     * 故从 monitor 保护中排除并按 {@code /api/downloaded/} 同等规则放行（本地直通 + multi 非管理员限流放行）；
     * 控制器内仍按小说下载核心状态判定。
     */
    private boolean isNovelDownloadedCheck(String path) {
        return path.startsWith("/api/novel/") && path.endsWith("/downloaded");
    }

    private boolean isPublic(String path) {
        if (isAlwaysPublicApi(path) || path.equals("/invite")) {
            return true;
        }
        if (setupService.isSetupComplete() && "solo".equals(setupService.getMode())) {
            return isSoloPublicPath(path);
        }
        return isDefaultPublicPath(path);
    }

    private boolean isAlwaysPublicApi(String path) {
        return path.startsWith("/api/auth/")
                || path.startsWith("/api/i18n/")
                || path.startsWith("/api/onboarding/");
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
        if (!NetworkUtils.isTrustedLocalRequest(req)) {
            return false;
        }
        String token = guiTokenProvider.getToken();
        if (token == null) {
            return false;
        }
        return token.equals(req.getHeader(GuiTokenProvider.HEADER_NAME));
    }

    private boolean isDefaultPublicPath(String path) {
        return path.equals("/")
                || path.equals("/index")
                || path.equals("/login.html")
                || path.equals("/index.html")
                || path.equals("/intro.html")
                || path.equals("/intro-canary.html")
                || currentAccess().publicStaticExactPaths().contains(path)
                || isPublicPageStaticResource(path)
                || path.startsWith("/api/setup/")
                || path.startsWith("/api/auth/")
                || path.startsWith("/api/i18n/")
                || path.equals("/invite");
    }

    private boolean isSoloPublicPath(String path) {
        return isIntroLoginPublicPageOrResource(path)
                || path.equals("/invite");
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
        if (access.guestAllowedExact().contains(path)) {
            return true;
        }
        return startsWithAny(path, access.guestAllowedPrefix());
    }

    private void sendJsonError(HttpServletRequest req, HttpServletResponse res,
                               int status, String messageCode, String defaultMessage) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        String message = messages.getOrDefault(localeResolver.resolveLocale(req), messageCode, defaultMessage);
        res.setStatus(status);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding(StandardCharsets.UTF_8.name());
        res.getWriter().write(mapper.writeValueAsString(new ErrorResponse(message)));
    }

    private void sendTextError(HttpServletRequest req, HttpServletResponse res,
                               int status, String messageCode, String defaultMessage) throws IOException {
        String message = messages.getOrDefault(localeResolver.resolveLocale(req), messageCode, defaultMessage);
        res.setStatus(status);
        res.setContentType(MediaType.TEXT_PLAIN_VALUE);
        res.setCharacterEncoding(StandardCharsets.UTF_8.name());
        res.setHeader(HttpHeaders.RETRY_AFTER, "60");
        res.getWriter().write(message);
    }

    /**
     * 解析邀请会话并在 request 内缓存：静态资源限流与后续过滤分别需要该会话，
     * 缓存避免对携带邀请 cookie 的请求重复查库（无 cookie 时 {@link #resolveGuestInviteSession} 立即返回）。
     */
    private GuestInviteSession resolveGuestInviteSessionCached(HttpServletRequest req, HttpServletResponse res) {
        if (Boolean.TRUE.equals(req.getAttribute(GUEST_SESSION_RESOLVED_ATTR))) {
            Object cached = req.getAttribute(GuestInviteSession.REQUEST_ATTR);
            return cached instanceof GuestInviteSession session ? session : null;
        }
        GuestInviteSession session = resolveGuestInviteSession(req, res);
        req.setAttribute(GUEST_SESSION_RESOLVED_ATTR, Boolean.TRUE);
        if (session != null) {
            req.setAttribute(GuestInviteSession.REQUEST_ATTR, session);
        }
        return session;
    }

    private GuestInviteSession resolveGuestInviteSession(HttpServletRequest req, HttpServletResponse res) {
        Cookie[] cookies = req.getCookies();
        if (cookies == null) return null;
        String code = null;
        for (Cookie c : cookies) {
            if (INVITE_COOKIE.equals(c.getName()) && c.getValue() != null && !c.getValue().isBlank()) {
                code = c.getValue();
                break;
            }
        }
        if (code == null) return null;
        Optional<GuestInviteSession> resolved;
        try {
            resolved = guestInviteService.resolveByCode(code);
        } catch (Exception e) {
            log.warn("Failed to resolve invite cookie: {}", e.getMessage());
            return null;
        }
        if (resolved.isPresent()) return resolved.get();
        // 失效：让浏览器丢掉无效的 cookie
        ResponseCookie cleared = ResponseCookie.from(INVITE_COOKIE, "")
                .path("/").httpOnly(true).secure(sslEnabled).sameSite("Strict").maxAge(0).build();
        res.addHeader(HttpHeaders.SET_COOKIE, cleared.toString());
        return null;
    }

    private boolean isAllowedForGuestInvite(String path, String method) {
        DerivedRouteAccess access = currentAccess();
        if ("POST".equalsIgnoreCase(method)) {
            return access.guestAllowedPostExact().contains(path);
        }
        if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
            return false;
        }
        if (access.guestAllowedStaticExact().contains(path)) return true;
        if (access.guestAllowedExact().contains(path)) return true;
        return startsWithAny(path, access.guestAllowedPrefix());
    }

    private void handleInviteRedeemRedirect(HttpServletRequest req, HttpServletResponse res) throws IOException {
        String code = req.getParameter("code");
        if (code == null || code.isBlank()) {
            res.sendRedirect("/login.html");
            return;
        }
        Optional<GuestInviteSession> session;
        try {
            session = guestInviteService.resolveByCode(code);
        } catch (Exception e) {
            log.warn("Invite redeem (GET) failed: {}", e.getMessage());
            res.sendRedirect("/login.html?inviteError=1");
            return;
        }
        if (session.isEmpty()) {
            res.sendRedirect("/login.html?inviteError=1");
            return;
        }
        ResponseCookie cookie = ResponseCookie.from(INVITE_COOKIE, session.get().code())
                .path("/").httpOnly(true).secure(sslEnabled).sameSite("Strict").build();
        res.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        // 落点经独立的 LandingRegistry 按受邀访客落点优先级解析（画廊 priority 20 优先、禁用则回退小说 30），
        // 全部缺失回登录页提示。与导航排序解耦，且与 InviteRedeemController 同口径（避免送进会 404 的坏入口）。
        res.sendRedirect(landingRegistry.resolve(Audience.INVITED_GUEST)
                .orElse("/login.html?inviteError=1"));
    }

    private void ensureUserUuidCookie(HttpServletRequest req, HttpServletResponse res) {
        Cookie[] cookies = req.getCookies();
        if (cookies != null) {
            for (Cookie c : cookies) {
                if ("pixiv_user_id".equals(c.getName()) && c.getValue() != null
                        && !c.getValue().isBlank()) {
                    return;
                }
            }
        }

        String uuid = UuidUtils.extractOrGenerateUuid(req);
        ResponseCookie cookie = ResponseCookie.from("pixiv_user_id", uuid)
                .path("/")
                .maxAge(Duration.ofDays(30))
                .sameSite("Strict")
                .httpOnly(true)
                .secure(sslEnabled)
                .build();
        res.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
