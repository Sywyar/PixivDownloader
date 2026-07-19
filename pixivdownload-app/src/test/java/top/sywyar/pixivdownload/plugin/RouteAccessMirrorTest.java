package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.api.web.AccessPolicy;
import top.sywyar.pixivdownload.plugin.api.web.WebRouteContribution;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.registry.RouteAccessRegistry;

/**
 * 路由访问策略安全分类不变量守卫。
 * <p>
 * {@link RouteAccessRegistry} 的不可变快照在 {@code AuthFilter} 构造期按访问策略派生：
 * <ul>
 *   <li>monitor 受保护清单 ← AccessPolicy ∈ {@code {ADMIN, INVITED_GUEST}}；</li>
 *   <li>访客邀请白名单     ← AccessPolicy ∈ {@code {INVITED_GUEST, VISITOR_AND_INVITED_GUEST}}；</li>
 *   <li>公开清单 ← {@code PUBLIC}；本地放行特例 ← {@code LOCAL}；</li>
 *   <li>{@code VISITOR} / {@code GUI} ← 不派生进上述任何清单（VISITOR 落默认会话 / 访客分支、GUI 由内联分支判定）。</li>
 * </ul>
 * 本测试守护「声明端的访问策略 → 安全分类不变量」：INVITED_GUEST 既受 monitor 保护又对访客开放；
 * ADMIN 仅 monitor、绝不入访客 / 公开；VISITOR_AND_INVITED_GUEST 仅对访客开放、绝不入 monitor；
 * VISITOR / GUI 是「声明但仍由内联流程分支判定」的直通策略、绝不进任何派生清单。AuthFilter 的过滤行为本身
 * 由金标准 {@code AuthFilterTest}（含未声明路由 404 覆盖）守护；全 URL 声明覆盖由
 * {@code RouteDeclarationCoverageTest} 守护。
 */
@DisplayName("RouteAccessRegistry 访问策略安全分类不变量")
class RouteAccessMirrorTest {

    private static final RouteAccessRegistry REGISTRY =
            new RouteAccessRegistry(new PluginRegistry(withExternalRouteFixtures(BuiltInPlugins.createAll())));

    /** AuthFilter 派生口径：monitor 受保护 ← ADMIN 或 INVITED_GUEST。 */
    private static boolean isMonitorPolicy(AccessPolicy policy) {
        return policy == AccessPolicy.ADMIN || policy == AccessPolicy.INVITED_GUEST;
    }

    private static List<PixivFeaturePlugin> withExternalRouteFixtures(List<PixivFeaturePlugin> plugins) {
        java.util.ArrayList<PixivFeaturePlugin> out = new java.util.ArrayList<>(plugins);
        out.add(new TestGalleryPlugin());
        out.add(new TestNovelGalleryPlugin());
        out.add(douyinRouteFixture());
        return out;
    }

    private static PixivFeaturePlugin douyinRouteFixture() {
        return new PixivFeaturePlugin() {
            @Override public String id() { return "douyin"; }
            @Override public String displayName() { return "plugin.name"; }
            @Override public String description() { return "plugin.summary"; }
            @Override public PluginKind kind() { return PluginKind.FEATURE; }
            @Override public List<WebRouteContribution> routes() {
                return List.of(
                        WebRouteContribution.admin("/pixiv-douyin-gallery.html"),
                        WebRouteContribution.admin("/pixiv-douyin-gallery/**"),
                        WebRouteContribution.admin("/pixiv-douyin.html"),
                        WebRouteContribution.admin("/pixiv-douyin/**"),
                        WebRouteContribution.admin("/api/douyin/gallery/**"));
            }
        };
    }

    /** AuthFilter 派生口径：访客邀请白名单 ← INVITED_GUEST 或 VISITOR_AND_INVITED_GUEST。 */
    private static boolean isGuestPolicy(AccessPolicy policy) {
        return policy == AccessPolicy.INVITED_GUEST || policy == AccessPolicy.VISITOR_AND_INVITED_GUEST;
    }

    private static boolean isPrefix(String pattern) {
        return pattern.endsWith("**");
    }

    /** {@code /x/**} → {@code /x/}；{@code /api/authors**} → {@code /api/authors}（与 AuthFilter 去 ** 派生一致）。 */
    private static String matcher(String pattern) {
        return isPrefix(pattern) ? pattern.substring(0, pattern.length() - 2) : pattern;
    }

    /** 与 AuthFilter 一致地把给定访问策略集合的全部路由折叠成匹配器字符串集合。 */
    private static Set<String> matchersForPolicies(Set<AccessPolicy> policies) {
        return REGISTRY.routes().stream()
                .map(RouteAccessRegistry.RegisteredRoute::route)
                .filter(route -> policies.contains(route.accessPolicy()))
                .map(route -> matcher(route.pathPattern()))
                .collect(Collectors.toSet());
    }

    @Test
    @DisplayName("内置路由只用 AuthFilter 已建模的八种访问策略（PUBLIC/ADMIN/INVITED_GUEST/VISITOR_AND_INVITED_GUEST/VISITOR/LOCAL/GUI/ACTUATOR_PUBLIC）")
    void onlyModeledAccessPoliciesAreUsed() {
        Set<AccessPolicy> modeled = Set.of(AccessPolicy.PUBLIC, AccessPolicy.ADMIN,
                AccessPolicy.INVITED_GUEST, AccessPolicy.VISITOR_AND_INVITED_GUEST,
                AccessPolicy.VISITOR, AccessPolicy.LOCAL, AccessPolicy.GUI, AccessPolicy.ACTUATOR_PUBLIC);
        assertThat(REGISTRY.routes()).allSatisfy(registered ->
                assertThat(modeled)
                        .as("路由 %s 用了 AuthFilter 未建模的访问策略 %s，登记前先扩展 AuthFilter 派生与本测试",
                                registered.route().pathPattern(), registered.route().accessPolicy())
                        .contains(registered.route().accessPolicy()));
    }

    @Test
    @DisplayName("直通策略（VISITOR / GUI / ACTUATOR_PUBLIC）路由：匹配器绝不出现在 monitor / 访客 / 公开 / 本地任一清单（声明但由内联 / 默认分支判定、不改访问行为）")
    void passThroughRoutesAreInert() {
        Set<String> monitorMatchers =
                matchersForPolicies(Set.of(AccessPolicy.ADMIN, AccessPolicy.INVITED_GUEST));
        Set<String> guestMatchers =
                matchersForPolicies(Set.of(AccessPolicy.INVITED_GUEST, AccessPolicy.VISITOR_AND_INVITED_GUEST));
        Set<String> publicMatchers = matchersForPolicies(Set.of(AccessPolicy.PUBLIC));
        Set<String> localMatchers = matchersForPolicies(Set.of(AccessPolicy.LOCAL));
        List<WebRouteContribution> passThrough = new java.util.ArrayList<>();
        passThrough.addAll(byPolicy(AccessPolicy.VISITOR));
        passThrough.addAll(byPolicy(AccessPolicy.GUI));
        passThrough.addAll(byPolicy(AccessPolicy.ACTUATOR_PUBLIC));
        assertThat(passThrough).as("应有 VISITOR / GUI / ACTUATOR_PUBLIC 直通路由（下载提交 / 装配 / 导航 / 配额 / Pixiv 代理 / GUI / actuator 等）").isNotEmpty();
        passThrough.forEach(route -> {
            String m = matcher(route.pathPattern());
            assertThat(monitorMatchers).as("直通路由 %s 不得进入 monitor 清单", route.pathPattern()).doesNotContain(m);
            assertThat(guestMatchers).as("直通路由 %s 不得进入访客白名单", route.pathPattern()).doesNotContain(m);
            assertThat(publicMatchers).as("直通路由 %s 不得进入公开清单", route.pathPattern()).doesNotContain(m);
            assertThat(localMatchers).as("直通路由 %s 不得进入本地放行清单", route.pathPattern()).doesNotContain(m);
        });
    }

    @Test
    @DisplayName("ACTUATOR_PUBLIC 策略：只能由 core 声明、只能用于 actuator 公开探针路径（普通插件不得拿它声明业务路由）")
    void actuatorPublicPolicyIsCoreOnlyAndProbePathsOnly() {
        Set<String> allowedProbePaths = Set.of(
                "/actuator/health", "/actuator/health/liveness",
                "/actuator/health/readiness", "/actuator/info");
        List<RouteAccessRegistry.RegisteredRoute> actuatorRoutes = REGISTRY.routes().stream()
                .filter(registered -> registered.route().accessPolicy() == AccessPolicy.ACTUATOR_PUBLIC)
                .toList();
        assertThat(actuatorRoutes).as("应声明 actuator 公开探针路由").isNotEmpty();
        actuatorRoutes.forEach(registered -> {
            assertThat(registered.pluginId())
                    .as("ACTUATOR_PUBLIC 路由 %s 只能由 core 声明", registered.route().pathPattern())
                    .isEqualTo("core");
            assertThat(allowedProbePaths)
                    .as("ACTUATOR_PUBLIC 只能用于 actuator 探针路径，而非业务路由 %s", registered.route().pathPattern())
                    .contains(registered.route().pathPattern());
        });
        // 反向：以上探针路径只能用 ACTUATOR_PUBLIC（防误标成其它策略而落进某派生清单）。
        REGISTRY.routes().stream()
                .filter(registered -> allowedProbePaths.contains(registered.route().pathPattern()))
                .forEach(registered -> assertThat(registered.route().accessPolicy())
                        .as("actuator 探针路径 %s 应声明为 ACTUATOR_PUBLIC", registered.route().pathPattern())
                        .isEqualTo(AccessPolicy.ACTUATOR_PUBLIC));
    }

    @Test
    @DisplayName("INVITED_GUEST 路由：既进入 monitor 受保护清单，又进入访客邀请白名单")
    void invitedGuestRoutesAreBothMonitorAndGuest() {
        List<WebRouteContribution> invitedGuest = byPolicy(AccessPolicy.INVITED_GUEST);
        assertThat(invitedGuest).as("应有 INVITED_GUEST 路由（页面 + /api/gallery/ + 下载数据 / 缩略图等）").isNotEmpty();
        invitedGuest.forEach(route -> {
            assertThat(isMonitorPolicy(route.accessPolicy()))
                    .as("INVITED_GUEST 路由 %s 应受 monitor 保护", route.pathPattern()).isTrue();
            assertThat(isGuestPolicy(route.accessPolicy()))
                    .as("INVITED_GUEST 路由 %s 应在访客白名单", route.pathPattern()).isTrue();
        });
    }

    @Test
    @DisplayName("ADMIN 路由：仅进入 monitor 清单，匹配器绝不出现在访客白名单或公开清单（admin-only 不变量）")
    void adminOnlyRoutesNeverEnterGuestOrPublic() {
        Set<String> guestMatchers =
                matchersForPolicies(Set.of(AccessPolicy.INVITED_GUEST, AccessPolicy.VISITOR_AND_INVITED_GUEST));
        Set<String> publicMatchers = matchersForPolicies(Set.of(AccessPolicy.PUBLIC));
        List<WebRouteContribution> adminOnly = byPolicy(AccessPolicy.ADMIN);
        assertThat(adminOnly).as("应有 ADMIN 路由（插件管理 / 插件市场 / 监控页等）").isNotEmpty();
        adminOnly.forEach(route -> {
            assertThat(isMonitorPolicy(route.accessPolicy()))
                    .as("ADMIN 路由 %s 应受 monitor 保护", route.pathPattern()).isTrue();
            assertThat(guestMatchers)
                    .as("admin-only 路由 %s 的匹配器不得出现在访客白名单", route.pathPattern())
                    .doesNotContain(matcher(route.pathPattern()));
            assertThat(publicMatchers)
                    .as("admin-only 路由 %s 的匹配器不得出现在公开清单", route.pathPattern())
                    .doesNotContain(matcher(route.pathPattern()));
        });
    }

    @Test
    @DisplayName("VISITOR_AND_INVITED_GUEST 路由：进入访客白名单、但匹配器绝不出现在 monitor 清单")
    void visitorAndInvitedGuestRoutesAreGuestButNotMonitor() {
        Set<String> monitorMatchers =
                matchersForPolicies(Set.of(AccessPolicy.ADMIN, AccessPolicy.INVITED_GUEST));
        List<WebRouteContribution> open = byPolicy(AccessPolicy.VISITOR_AND_INVITED_GUEST);
        assertThat(open).as("应有 VISITOR_AND_INVITED_GUEST 路由（只读代理 / 下载状态前缀 + 跨页共享静态依赖）").isNotEmpty();
        open.forEach(route -> {
            assertThat(isGuestPolicy(route.accessPolicy()))
                    .as("VISITOR_AND_INVITED_GUEST 路由 %s 应在访客白名单", route.pathPattern()).isTrue();
            assertThat(monitorMatchers)
                    .as("VISITOR_AND_INVITED_GUEST 路由 %s 的匹配器不得出现在 monitor 清单", route.pathPattern())
                    .doesNotContain(matcher(route.pathPattern()));
        });
    }

    @Test
    @DisplayName("代表性内置路由的归属插件与访问策略符合预期（防误改策略 / 归属，覆盖每种策略 + 新声明 URL）")
    void representativeRoutesHaveExpectedOwnerAndPolicy() {
        // 核心声明：下载历史与本地资产 / 公开与共享静态 / 本地放行特例 / 横切 API。
        // 下载工作台已外置；提交、队列、状态、Pixiv 抓取代理、SSE、userscript 入口不在 core-only 快照中。
        assertOwnerPolicy("/api/downloaded/batch", "core", AccessPolicy.ADMIN);
        assertOwnerPolicy("/api/downloaded/statistics", "core", AccessPolicy.INVITED_GUEST);
        assertOwnerPolicy("/api/downloaded/image/**", "core", AccessPolicy.INVITED_GUEST);
        assertOwnerPolicy("/api/authors**", "core", AccessPolicy.INVITED_GUEST);
        assertOwnerPolicy("/api/collections**", "core", AccessPolicy.INVITED_GUEST);
        // 插件管理后端 API：状态查询 + 外置插件运行期生命周期动词，仅管理员（admin-only）。
        assertOwnerPolicy("/api/plugins/**", "core", AccessPolicy.ADMIN);
        assertOwnerPolicy("/js/pixiv-side-modules.js", "core", AccessPolicy.VISITOR_AND_INVITED_GUEST);
        // 通用页面区块渲染器：与 /api/page-sections 同口径显式声明（不靠 /js/** 的 VISITOR 兜底），受邀访客可加载。
        assertOwnerPolicy("/js/pixiv-page-sections.js", "core", AccessPolicy.VISITOR_AND_INVITED_GUEST);
        // 通用下钻渲染器：与 /api/drilldowns 同口径显式声明（不靠 /js/** 的 VISITOR 兜底），受邀访客可加载。
        assertOwnerPolicy("/js/pixiv-drilldowns.js", "core", AccessPolicy.VISITOR_AND_INVITED_GUEST);
        assertOwnerPolicy("/favicon.ico", "core", AccessPolicy.PUBLIC);
        // 插件管理页（admin-only）+ 其页面专属静态资源；与 /api/plugins/** 同归核心、同 ADMIN。
        assertOwnerPolicy("/plugin-manage.html", "core", AccessPolicy.ADMIN);
        assertOwnerPolicy("/plugin-manage/**", "core", AccessPolicy.ADMIN);
        assertOwnerPolicy("/api/downloaded/**", "core", AccessPolicy.LOCAL);
        // 核心新声明的横切 / 公开 / 直通 / GUI / 本地 URL（本前置包补齐的「全 URL 声明」）
        assertOwnerPolicy("/api/auth/**", "core", AccessPolicy.PUBLIC);
        assertOwnerPolicy("/api/i18n/**", "core", AccessPolicy.PUBLIC);
        assertOwnerPolicy("/index.html", "core", AccessPolicy.PUBLIC);
        assertOwnerPolicy("/api/navigation", "core", AccessPolicy.VISITOR_AND_INVITED_GUEST);
        assertOwnerPolicy("/api/drilldowns", "core", AccessPolicy.VISITOR_AND_INVITED_GUEST);
        assertOwnerPolicy("/api/quota/**", "core", AccessPolicy.VISITOR);
        assertOwnerPolicy("/api/archive/**", "core", AccessPolicy.VISITOR);
        assertOwnerPolicy("/api/setup/**", "core", AccessPolicy.VISITOR);
        assertOwnerPolicy("/js/**", "core", AccessPolicy.VISITOR);
        assertOwnerPolicy("/vendor/**", "core", AccessPolicy.VISITOR);
        assertOwnerPolicy("/api/gui/**", "core", AccessPolicy.GUI);
        assertOwnerPolicy("/actuator/health", "core", AccessPolicy.ACTUATOR_PUBLIC);
        assertOwnerPolicy("/proxy.pac", "core", AccessPolicy.LOCAL);
        assertOwnerPolicy("/setup.html", "core", AccessPolicy.LOCAL);
        // 功能插件声明：画廊 / 小说画廊页面 + /api/gallery 子面按控制器归属拆分，互不越界。
        assertOwnerPolicy("/pixiv-gallery.html", "gallery", AccessPolicy.INVITED_GUEST);
        assertOwnerPolicy("/api/gallery/artwork**", "gallery", AccessPolicy.INVITED_GUEST);
        assertOwnerPolicy("/api/gallery/tags**", "gallery", AccessPolicy.INVITED_GUEST);
        assertOwnerPolicy("/pixiv-novel.html", "novel", AccessPolicy.INVITED_GUEST);
        assertOwnerPolicy("/pixiv-novel-gallery.html", "novel", AccessPolicy.INVITED_GUEST);
        assertOwnerPolicy("/pixiv-novel/**", "novel", AccessPolicy.INVITED_GUEST);
        assertOwnerPolicy("/pixiv-novel-gallery/**", "novel", AccessPolicy.INVITED_GUEST);
        assertOwnerPolicy("/api/gallery/novel/**", "novel", AccessPolicy.INVITED_GUEST);
        assertOwnerPolicy("/api/gallery/novels/**", "novel", AccessPolicy.INVITED_GUEST);
        assertOwnerPolicy("/api/gallery/novels", "novel", AccessPolicy.INVITED_GUEST);
        assertOwnerPolicy("/pixiv-douyin-gallery.html", "douyin", AccessPolicy.ADMIN);
        assertOwnerPolicy("/pixiv-douyin-gallery/**", "douyin", AccessPolicy.ADMIN);
        assertOwnerPolicy("/pixiv-douyin.html", "douyin", AccessPolicy.ADMIN);
        assertOwnerPolicy("/pixiv-douyin/**", "douyin", AccessPolicy.ADMIN);
        assertOwnerPolicy("/api/douyin/gallery/**", "douyin", AccessPolicy.ADMIN);
        assertOwnerPolicy("/api/pixiv/novel/*/meta", "novel", AccessPolicy.VISITOR_AND_INVITED_GUEST);
        assertOwnerPolicy("/api/pixiv/novel/*/bookmark-count", "novel", AccessPolicy.VISITOR_AND_INVITED_GUEST);
        assertOwnerPolicy("/api/pixiv/novel/series/*", "novel", AccessPolicy.VISITOR);
        assertOwnerPolicy("/api/pixiv/novel-search**", "novel", AccessPolicy.VISITOR);
        assertOwnerPolicy("/api/pixiv/user/*/novels", "novel", AccessPolicy.VISITOR);
        assertOwnerPolicy("/api/pixiv/user/*/novel-cards", "novel", AccessPolicy.VISITOR);
        assertOwnerPolicy("/api/pixiv/me/novel-bookmarks", "novel", AccessPolicy.VISITOR);
        // 小说下载端点归小说插件、新址 + 旧址兼容垫片一律 VISITOR（复刻 /api/download/pixiv 现状）。
        assertOwnerPolicy("/api/novel/download", "novel", AccessPolicy.VISITOR);
        assertOwnerPolicy("/api/novel/status/**", "novel", AccessPolicy.VISITOR);
        assertOwnerPolicy("/api/novel/translate-status/**", "novel", AccessPolicy.VISITOR);
        assertOwnerPolicy("/api/novel/*/downloaded", "novel", AccessPolicy.VISITOR);
        assertOwnerPolicy("/api/novel/series/*/merge", "novel", AccessPolicy.VISITOR);
        assertOwnerPolicy("/api/novel/series/*/merged", "novel", AccessPolicy.VISITOR_AND_INVITED_GUEST);
        assertOwnerPolicy("/api/novel/*/translate", "novel", AccessPolicy.ADMIN);
        assertOwnerPolicy("/api/novel/translate-lang-probe", "novel", AccessPolicy.ADMIN);
        assertOwnerPolicy("/api/novel/series/*/translate-title", "novel", AccessPolicy.ADMIN);
        assertOwnerPolicy("/api/novel/series/*/novel-ids", "novel", AccessPolicy.ADMIN);
        assertOwnerPolicy("/api/download/pixiv/novel", "novel", AccessPolicy.VISITOR);
        assertOwnerPolicy("/api/download/novel/status/**", "novel", AccessPolicy.VISITOR);
        assertOwnerPolicy("/pixiv-novel-download/**", "novel", AccessPolicy.VISITOR);
        // 统计 stats 与 duplicate 已外置：经外置插件 contribution 声明、不在内置快照（其 ADMIN 策略与
        // classloader-aware 接入由外置加载的集成测试覆盖）。
        assertRouteAbsent("/api/duplicates/**");
        assertRouteAbsent("/pixiv-duplicates.html");
        assertRouteAbsent("/pixiv-duplicates/**");
        // 插件市场：市场页 + 页面专属静态资源 + 后端市场 API，归 plugin-market 插件、ADMIN（admin-only，绝不入访客 / 公开）。
        assertOwnerPolicy("/plugin-market.html", "plugin-market", AccessPolicy.ADMIN);
        assertOwnerPolicy("/plugin-market/**", "plugin-market", AccessPolicy.ADMIN);
        assertOwnerPolicy("/api/plugin-market/**", "plugin-market", AccessPolicy.ADMIN);
        // 外置 required download-workbench 缺席时，core-only 快照不得声明下载工作台 / 计划任务宿主 URL。
        assertRouteAbsent("/api/schedule/**");
        assertRouteAbsent("/api/download/extensions");
        assertRouteAbsent("/pixiv-batch.html");
        assertRouteAbsent("/pixiv-batch/**");
        assertRouteAbsent("/api/download/pixiv");
        assertRouteAbsent("/api/batch/**");
        assertRouteAbsent("/api/download/status/active");
        assertRouteAbsent("/api/download/status/**");
        assertRouteAbsent("/api/download/status");
        assertRouteAbsent("/api/pixiv/artwork/**");
        assertRouteAbsent("/api/pixiv/user/**");
        assertRouteAbsent("/api/pixiv/search**");
        assertRouteAbsent("/api/pixiv/series/**");
        assertRouteAbsent("/api/pixiv/me/**");
        assertRouteAbsent("/api/scripts**");
        assertRouteAbsent("/api/sse/**");
    }

    private static List<WebRouteContribution> byPolicy(AccessPolicy policy) {
        return REGISTRY.routes().stream()
                .map(RouteAccessRegistry.RegisteredRoute::route)
                .filter(route -> route.accessPolicy() == policy)
                .toList();
    }

    private static void assertOwnerPolicy(String pattern, String pluginId, AccessPolicy policy) {
        assertThat(REGISTRY.routes())
                .as("路由 %s 应由插件 %s 以 %s 声明", pattern, pluginId, policy)
                .anySatisfy(registered -> {
                    assertThat(registered.pluginId()).isEqualTo(pluginId);
                    assertThat(registered.route().pathPattern()).isEqualTo(pattern);
                    assertThat(registered.route().accessPolicy()).isEqualTo(policy);
                });
    }

    private static void assertRouteAbsent(String pattern) {
        assertThat(REGISTRY.routes().stream()
                .map(registered -> registered.route().pathPattern())
                .toList())
                .as("core-only 内置快照不应声明外置插件路由 %s", pattern)
                .doesNotContain(pattern);
    }
}
