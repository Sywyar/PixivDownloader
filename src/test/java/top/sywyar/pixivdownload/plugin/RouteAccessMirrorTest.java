package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.AccessLevel;
import top.sywyar.pixivdownload.plugin.api.WebRouteContribution;
import top.sywyar.pixivdownload.setup.AuthFilter;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * 路由镜像一致性测试：{@link RouteAccessRegistry} 输出与 {@code AuthFilter} 硬编码清单逐条对照。
 * {@code AuthFilter} 在切换读取 registry 之前不动，本测试保证两边长期一致；
 * 切换完成、硬编码清单删除后，本测试转为 registry 自身单测。
 * <p>
 * 双向对照只覆盖已迁移到插件声明的路径子集（见 {@code PLUGIN_OWNED_*} 清单），
 * 后续插件包登记新路径时同步扩充这两张表即可，无需改对照逻辑。
 */
@DisplayName("RouteAccessRegistry 与 AuthFilter 硬编码清单镜像一致")
class RouteAccessMirrorTest {

    private static final RouteAccessRegistry REGISTRY =
            new RouteAccessRegistry(new PluginRegistry(BuiltInPlugins.createAll()));

    /** 已由插件声明接管的 AuthFilter 精确路径条目 → 声明方插件。 */
    private static final Map<String, String> PLUGIN_OWNED_MONITOR_EXACT = Map.of(
            "/pixiv-stats.html", "stats",
            "/pixiv-duplicates.html", "duplicate");

    /** 已由插件声明接管的 AuthFilter 前缀路径条目 → 声明方插件。 */
    private static final Map<String, String> PLUGIN_OWNED_MONITOR_PREFIX = Map.of(
            "/pixiv-stats/", "stats",
            "/api/stats/", "stats",
            "/pixiv-duplicates/", "duplicate",
            "/api/duplicates/", "duplicate");

    @Test
    @DisplayName("registry 中每条 ADMIN_OR_SOLO 路由都能在 AuthFilter monitor 清单中找到对应条目")
    void everyRegisteredRouteIsMirroredInAuthFilter() {
        Collection<String> monitorExact = authFilterPaths("MONITOR_EXACT_PATHS");
        Collection<String> monitorPrefix = authFilterPaths("MONITOR_PREFIX_PATHS");
        for (RouteAccessRegistry.RegisteredRoute registered : REGISTRY.routes()) {
            WebRouteContribution route = registered.route();
            if (route.accessLevel() != AccessLevel.ADMIN_OR_SOLO) {
                fail("访问级别 %s 尚无 AuthFilter 镜像映射，登记 %s 路由前先扩展本测试框架"
                        .formatted(route.accessLevel(), registered.pluginId()));
            }
            if (isPrefixPattern(route.pathPattern())) {
                assertThat(monitorPrefix)
                        .as("插件 %s 的前缀路由 %s 应在 MONITOR_PREFIX_PATHS 中",
                                registered.pluginId(), route.pathPattern())
                        .contains(toPrefix(route.pathPattern()));
            } else {
                assertThat(monitorExact)
                        .as("插件 %s 的精确路由 %s 应在 MONITOR_EXACT_PATHS 中",
                                registered.pluginId(), route.pathPattern())
                        .contains(route.pathPattern());
            }
        }
    }

    @Test
    @DisplayName("已迁移子集反向对照：AuthFilter 中插件拥有的条目全部由对应插件声明")
    void pluginOwnedAuthFilterEntriesAreAllRegistered() {
        PLUGIN_OWNED_MONITOR_EXACT.forEach((path, pluginId) ->
                assertThat(findRoute(pluginId, path))
                        .as("AuthFilter 精确条目 %s 应由插件 %s 声明", path, pluginId)
                        .isNotEmpty());
        PLUGIN_OWNED_MONITOR_PREFIX.forEach((prefix, pluginId) ->
                assertThat(findRoute(pluginId, prefix + "**"))
                        .as("AuthFilter 前缀条目 %s 应由插件 %s 声明", prefix, pluginId)
                        .isNotEmpty());
    }

    @Test
    @DisplayName("PLUGIN_OWNED 清单与 AuthFilter 实际条目一致（防 AuthFilter 侧条目改名/删除后镜像悬空）")
    void pluginOwnedEntriesStillExistInAuthFilter() {
        assertThat(authFilterPaths("MONITOR_EXACT_PATHS"))
                .containsAll(PLUGIN_OWNED_MONITOR_EXACT.keySet());
        assertThat(authFilterPaths("MONITOR_PREFIX_PATHS"))
                .containsAll(PLUGIN_OWNED_MONITOR_PREFIX.keySet());
    }

    @Test
    @DisplayName("ADMIN_OR_SOLO 路由不得出现在 isPublic / 访客邀请白名单（admin-only 不变量）")
    void adminRoutesNeverAppearInPublicOrGuestLists() {
        List<Collection<String>> exactLists = List.of(
                authFilterPaths("PUBLIC_STATIC_EXACT_PATHS"),
                authFilterPaths("GUEST_ALLOWED_STATIC_EXACT"),
                authFilterPaths("GUEST_ALLOWED_EXACT"),
                authFilterPaths("GUEST_ALLOWED_POST_EXACT"));
        Collection<String> publicPrefixes = authFilterPaths("PUBLIC_PAGE_STATIC_PREFIX_PATHS");
        Collection<String> guestPrefixes = authFilterPaths("GUEST_ALLOWED_PREFIX");

        for (RouteAccessRegistry.RegisteredRoute registered : REGISTRY.routes()) {
            WebRouteContribution route = registered.route();
            if (route.accessLevel() != AccessLevel.ADMIN_OR_SOLO) {
                continue;
            }
            String path = isPrefixPattern(route.pathPattern())
                    ? toPrefix(route.pathPattern()) : route.pathPattern();
            for (Collection<String> exact : exactLists) {
                assertThat(exact)
                        .as("admin-only 路由 %s 不得出现在公开/访客精确清单", path)
                        .doesNotContain(path);
            }
            for (String prefix : publicPrefixes) {
                assertThat(path)
                        .as("admin-only 路由 %s 不得被公开静态前缀 %s 覆盖", path, prefix)
                        .doesNotStartWith(prefix);
            }
            for (String prefix : guestPrefixes) {
                assertThat(path)
                        .as("admin-only 路由 %s 不得被访客前缀 %s 覆盖", path, prefix)
                        .doesNotStartWith(prefix);
            }
        }
    }

    private static List<RouteAccessRegistry.RegisteredRoute> findRoute(String pluginId, String pathPattern) {
        return REGISTRY.routes().stream()
                .filter(registered -> registered.pluginId().equals(pluginId)
                        && registered.route().pathPattern().equals(pathPattern))
                .toList();
    }

    private static boolean isPrefixPattern(String pathPattern) {
        return pathPattern.endsWith("/**");
    }

    /** {@code /x/**} → AuthFilter 前缀清单形态 {@code /x/}。 */
    private static String toPrefix(String pathPattern) {
        return pathPattern.substring(0, pathPattern.length() - 2);
    }

    @SuppressWarnings("unchecked")
    private static Collection<String> authFilterPaths(String fieldName) {
        try {
            Field field = AuthFilter.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            return (Collection<String>) field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("AuthFilter 硬编码清单字段不可达: " + fieldName, e);
        }
    }
}
