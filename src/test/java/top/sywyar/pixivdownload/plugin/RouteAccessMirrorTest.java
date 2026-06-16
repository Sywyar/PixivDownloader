package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.web.AccessLevel;
import top.sywyar.pixivdownload.plugin.api.web.WebRouteContribution;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 路由访问级别安全分类不变量守卫。
 * <p>
 * AuthFilter 切换 registry 后，原八类硬编码清单（MONITOR_* / PUBLIC_* / GUEST_ALLOWED_*）已删除，
 * 改由 {@link RouteAccessRegistry} 不可变快照在 {@code AuthFilter} 构造期按访问级别派生：
 * <ul>
 *   <li>monitor 受保护清单 ← AccessLevel ∈ {@code {ADMIN_OR_SOLO, GUEST_READ}}；</li>
 *   <li>访客邀请白名单     ← AccessLevel ∈ {@code {GUEST_READ, GUEST_READ_OPEN}}；</li>
 *   <li>公开清单 ← {@code PUBLIC}；{@code /api/downloaded} 本地放行特例 ← {@code LOCAL_ONLY}。</li>
 * </ul>
 * 因此本测试从「registry ↔ AuthFilter 硬编码逐条对照」转为「registry 声明端自身的访问级别 → 安全分类不变量」守卫：
 * GUEST_READ 既受 monitor 保护又对访客开放；ADMIN_OR_SOLO 仅 monitor、绝不入访客 / 公开；
 * GUEST_READ_OPEN 仅对访客开放、绝不入 monitor（multi 普通访客 GET 亦可达）。AuthFilter 的过滤行为本身
 * 由金标准 {@code AuthFilterTest}（零修改通过的过滤行为基线）守护；本测试守护「声明端的访问级别没被改错 / 误开放」。
 */
@DisplayName("RouteAccessRegistry 访问级别安全分类不变量")
class RouteAccessMirrorTest {

    private static final RouteAccessRegistry REGISTRY =
            new RouteAccessRegistry(new PluginRegistry(BuiltInPlugins.createAll()));

    /** AuthFilter 派生口径：monitor 受保护 ← ADMIN_OR_SOLO 或 GUEST_READ。 */
    private static boolean isMonitorLevel(AccessLevel level) {
        return level == AccessLevel.ADMIN_OR_SOLO || level == AccessLevel.GUEST_READ;
    }

    /** AuthFilter 派生口径：访客邀请白名单 ← GUEST_READ 或 GUEST_READ_OPEN。 */
    private static boolean isGuestLevel(AccessLevel level) {
        return level == AccessLevel.GUEST_READ || level == AccessLevel.GUEST_READ_OPEN;
    }

    private static boolean isPrefix(String pattern) {
        return pattern.endsWith("**");
    }

    /** {@code /x/**} → {@code /x/}；{@code /api/authors**} → {@code /api/authors}（与 AuthFilter 去 ** 派生一致）。 */
    private static String matcher(String pattern) {
        return isPrefix(pattern) ? pattern.substring(0, pattern.length() - 2) : pattern;
    }

    /** 与 AuthFilter 一致地把给定访问级别集合的全部路由折叠成匹配器字符串集合。 */
    private static Set<String> matchersForLevels(Set<AccessLevel> levels) {
        return REGISTRY.routes().stream()
                .map(RouteAccessRegistry.RegisteredRoute::route)
                .filter(route -> levels.contains(route.accessLevel()))
                .map(route -> matcher(route.pathPattern()))
                .collect(Collectors.toSet());
    }

    @Test
    @DisplayName("内置路由只用 AuthFilter 已建模的六种访问级别（PUBLIC/ADMIN_OR_SOLO/GUEST_READ/GUEST_READ_OPEN/SESSION_OR_VISITOR/LOCAL_ONLY）")
    void onlyModeledAccessLevelsAreUsed() {
        Set<AccessLevel> modeled = Set.of(AccessLevel.PUBLIC, AccessLevel.ADMIN_OR_SOLO,
                AccessLevel.GUEST_READ, AccessLevel.GUEST_READ_OPEN,
                AccessLevel.SESSION_OR_VISITOR, AccessLevel.LOCAL_ONLY);
        assertThat(REGISTRY.routes()).allSatisfy(registered ->
                assertThat(modeled)
                        .as("路由 %s 用了 AuthFilter 未建模的访问级别 %s，登记前先扩展 AuthFilter 派生与本测试",
                                registered.route().pathPattern(), registered.route().accessLevel())
                        .contains(registered.route().accessLevel()));
    }

    @Test
    @DisplayName("SESSION_OR_VISITOR 路由：匹配器绝不出现在 monitor / 访客 / 公开 / 本地任一清单（下载工作台端点的纯归属声明、不改访问行为）")
    void sessionOrVisitorRoutesArePassThrough() {
        Set<String> monitorMatchers =
                matchersForLevels(Set.of(AccessLevel.ADMIN_OR_SOLO, AccessLevel.GUEST_READ));
        Set<String> guestMatchers =
                matchersForLevels(Set.of(AccessLevel.GUEST_READ, AccessLevel.GUEST_READ_OPEN));
        Set<String> publicMatchers = matchersForLevels(Set.of(AccessLevel.PUBLIC));
        Set<String> localMatchers = matchersForLevels(Set.of(AccessLevel.LOCAL_ONLY));
        List<WebRouteContribution> passThrough = byLevel(AccessLevel.SESSION_OR_VISITOR);
        assertThat(passThrough).as("应有 SESSION_OR_VISITOR 路由（小说下载新址 + 旧址兼容垫片 + 下载页扩展点装配端点 + 核心导航装配端点）").isNotEmpty();
        passThrough.forEach(route -> {
            String m = matcher(route.pathPattern());
            assertThat(monitorMatchers).as("SESSION_OR_VISITOR 路由 %s 不得进入 monitor 清单", route.pathPattern()).doesNotContain(m);
            assertThat(guestMatchers).as("SESSION_OR_VISITOR 路由 %s 不得进入访客白名单", route.pathPattern()).doesNotContain(m);
            assertThat(publicMatchers).as("SESSION_OR_VISITOR 路由 %s 不得进入公开清单", route.pathPattern()).doesNotContain(m);
            assertThat(localMatchers).as("SESSION_OR_VISITOR 路由 %s 不得进入本地放行清单", route.pathPattern()).doesNotContain(m);
        });
    }

    @Test
    @DisplayName("GUEST_READ 路由：既进入 monitor 受保护清单，又进入访客邀请白名单")
    void guestReadRoutesAreBothMonitorAndGuest() {
        List<WebRouteContribution> guestRead = byLevel(AccessLevel.GUEST_READ);
        assertThat(guestRead).as("应有 GUEST_READ 路由（页面 + /api/gallery/ + 下载数据 / 缩略图等）").isNotEmpty();
        guestRead.forEach(route -> {
            assertThat(isMonitorLevel(route.accessLevel()))
                    .as("GUEST_READ 路由 %s 应受 monitor 保护", route.pathPattern()).isTrue();
            assertThat(isGuestLevel(route.accessLevel()))
                    .as("GUEST_READ 路由 %s 应在访客白名单", route.pathPattern()).isTrue();
        });
    }

    @Test
    @DisplayName("ADMIN_OR_SOLO 路由：仅进入 monitor 清单，匹配器绝不出现在访客白名单或公开清单（admin-only 不变量）")
    void adminOnlyRoutesNeverEnterGuestOrPublic() {
        Set<String> guestMatchers =
                matchersForLevels(Set.of(AccessLevel.GUEST_READ, AccessLevel.GUEST_READ_OPEN));
        Set<String> publicMatchers = matchersForLevels(Set.of(AccessLevel.PUBLIC));
        List<WebRouteContribution> adminOnly = byLevel(AccessLevel.ADMIN_OR_SOLO);
        assertThat(adminOnly).as("应有 ADMIN_OR_SOLO 路由（stats/duplicate/schedule/admin/监控页等）").isNotEmpty();
        adminOnly.forEach(route -> {
            assertThat(isMonitorLevel(route.accessLevel()))
                    .as("ADMIN_OR_SOLO 路由 %s 应受 monitor 保护", route.pathPattern()).isTrue();
            assertThat(guestMatchers)
                    .as("admin-only 路由 %s 的匹配器不得出现在访客白名单", route.pathPattern())
                    .doesNotContain(matcher(route.pathPattern()));
            assertThat(publicMatchers)
                    .as("admin-only 路由 %s 的匹配器不得出现在公开清单", route.pathPattern())
                    .doesNotContain(matcher(route.pathPattern()));
        });
    }

    @Test
    @DisplayName("GUEST_READ_OPEN 路由：进入访客白名单、但匹配器绝不出现在 monitor 清单（新增访问级别不变量）")
    void guestReadOpenRoutesAreGuestButNotMonitor() {
        Set<String> monitorMatchers =
                matchersForLevels(Set.of(AccessLevel.ADMIN_OR_SOLO, AccessLevel.GUEST_READ));
        List<WebRouteContribution> open = byLevel(AccessLevel.GUEST_READ_OPEN);
        assertThat(open).as("应有 GUEST_READ_OPEN 路由（只读代理 / 下载状态前缀 + 跨页共享静态依赖）").isNotEmpty();
        open.forEach(route -> {
            assertThat(isGuestLevel(route.accessLevel()))
                    .as("GUEST_READ_OPEN 路由 %s 应在访客白名单", route.pathPattern()).isTrue();
            assertThat(monitorMatchers)
                    .as("GUEST_READ_OPEN 路由 %s 的匹配器不得出现在 monitor 清单", route.pathPattern())
                    .doesNotContain(matcher(route.pathPattern()));
        });
    }

    @Test
    @DisplayName("代表性内置路由的归属插件与访问级别符合预期（防误改级别 / 归属，含 active/前缀、batch/其余的现状不对称）")
    void representativeRoutesHaveExpectedOwnerAndLevel() {
        // 核心声明：下载域数据 / 图片字节 / 状态 / 代理 / 公开与共享静态 / 本地放行特例
        assertOwnerLevel("/api/downloaded/batch", "core", AccessLevel.ADMIN_OR_SOLO);
        assertOwnerLevel("/api/downloaded/statistics", "core", AccessLevel.GUEST_READ);
        assertOwnerLevel("/api/downloaded/image/**", "core", AccessLevel.GUEST_READ);
        assertOwnerLevel("/api/authors**", "core", AccessLevel.GUEST_READ);
        assertOwnerLevel("/api/collections**", "core", AccessLevel.GUEST_READ);
        assertOwnerLevel("/api/download/status/active", "core", AccessLevel.GUEST_READ);
        assertOwnerLevel("/api/download/status/**", "core", AccessLevel.GUEST_READ_OPEN);
        assertOwnerLevel("/api/download/status", "core", AccessLevel.LOCAL_ONLY);
        assertOwnerLevel("/api/pixiv/artwork/**", "core", AccessLevel.GUEST_READ_OPEN);
        assertOwnerLevel("/api/pixiv/novel/**", "core", AccessLevel.GUEST_READ_OPEN);
        assertOwnerLevel("/api/tts/**", "core", AccessLevel.ADMIN_OR_SOLO);
        assertOwnerLevel("/api/tts/edge/synthesize", "core", AccessLevel.GUEST_READ_OPEN);
        assertOwnerLevel("/js/pixiv-side-modules.js", "core", AccessLevel.GUEST_READ_OPEN);
        assertOwnerLevel("/favicon.ico", "core", AccessLevel.PUBLIC);
        assertOwnerLevel("/api/downloaded/**", "core", AccessLevel.LOCAL_ONLY);
        // 功能插件声明：画廊页面保持不动；/api/gallery 子面按控制器归属拆分——画廊占 artwork(s)/tags
        // 窄前缀，小说占 novel(s) 窄前缀（含列表裸端点 /api/gallery/novels 的精确声明），互不越界。
        assertOwnerLevel("/pixiv-gallery.html", "gallery", AccessLevel.GUEST_READ);
        assertOwnerLevel("/api/gallery/artwork**", "gallery", AccessLevel.GUEST_READ);
        assertOwnerLevel("/api/gallery/tags**", "gallery", AccessLevel.GUEST_READ);
        assertOwnerLevel("/pixiv-novel.html", "novel", AccessLevel.GUEST_READ);
        assertOwnerLevel("/api/gallery/novel/**", "novel", AccessLevel.GUEST_READ);
        assertOwnerLevel("/api/gallery/novels/**", "novel", AccessLevel.GUEST_READ);
        assertOwnerLevel("/api/gallery/novels", "novel", AccessLevel.GUEST_READ);
        // 小说下载端点归小说插件、新址 + 旧址兼容垫片一律 SESSION_OR_VISITOR（复刻 /api/download/pixiv 现状：
        // multi 访客可下载 / solo 需会话 / 邀请访客 403 / 不入 monitor，仅作归属声明、不改访问行为）。
        assertOwnerLevel("/api/novel/download", "novel", AccessLevel.SESSION_OR_VISITOR);
        assertOwnerLevel("/api/novel/status/**", "novel", AccessLevel.SESSION_OR_VISITOR);
        assertOwnerLevel("/api/novel/translate-status/**", "novel", AccessLevel.SESSION_OR_VISITOR);
        assertOwnerLevel("/api/download/pixiv/novel", "novel", AccessLevel.SESSION_OR_VISITOR);
        assertOwnerLevel("/api/download/novel/status/**", "novel", AccessLevel.SESSION_OR_VISITOR);
        assertOwnerLevel("/api/stats/**", "stats", AccessLevel.ADMIN_OR_SOLO);
        assertOwnerLevel("/api/duplicates/**", "duplicate", AccessLevel.ADMIN_OR_SOLO);
        // 计划任务管理 API 随 schedule 能力收编归下载工作台插件声明（访问级别不变：仅管理员）。
        assertOwnerLevel("/api/schedule/**", "download-workbench", AccessLevel.ADMIN_OR_SOLO);
        // 下载页扩展点装配端点归下载工作台、SESSION_OR_VISITOR（随下载页消费的只读装配接口，复刻现状：
        // multi 访客可读 / solo 需会话 / 邀请访客 403 / 不入 monitor，仅作归属声明、不改访问行为）。
        assertOwnerLevel("/api/download/extensions", "download-workbench", AccessLevel.SESSION_OR_VISITOR);
        // 核心导航装配端点归 core、SESSION_OR_VISITOR（NavigationController 读 NavigationRegistry 跨插件
        // 聚合导航项；复刻未声明时现状：multi 访客可读 / solo 需会话 / 邀请访客 403 / 不入 monitor）。
        assertOwnerLevel("/api/navigation", "core", AccessLevel.SESSION_OR_VISITOR);
    }

    private static List<WebRouteContribution> byLevel(AccessLevel level) {
        return REGISTRY.routes().stream()
                .map(RouteAccessRegistry.RegisteredRoute::route)
                .filter(route -> route.accessLevel() == level)
                .toList();
    }

    private static void assertOwnerLevel(String pattern, String pluginId, AccessLevel level) {
        assertThat(REGISTRY.routes())
                .as("路由 %s 应由插件 %s 以 %s 声明", pattern, pluginId, level)
                .anySatisfy(registered -> {
                    assertThat(registered.pluginId()).isEqualTo(pluginId);
                    assertThat(registered.route().pathPattern()).isEqualTo(pattern);
                    assertThat(registered.route().accessLevel()).isEqualTo(level);
                });
    }
}
