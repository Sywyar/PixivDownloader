package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.web.AccessLevel;
import top.sywyar.pixivdownload.plugin.api.web.HttpMethod;
import top.sywyar.pixivdownload.plugin.api.web.WebRouteContribution;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("RouteAccessRegistry 路由注册中心")
class RouteAccessRegistryTest {

    private static RouteAccessRegistry emptyRegistry() {
        return new RouteAccessRegistry(new PluginRegistry(List.of()));
    }

    private static WebRouteContribution route(String pattern) {
        return new WebRouteContribution(pattern, AccessLevel.ADMIN_OR_SOLO, Set.of(), false);
    }

    @Test
    @DisplayName("构造时从 PluginRegistry 收集全部内置插件路由（stats 三条在快照中）")
    void collectsRoutesFromBuiltInPlugins() {
        RouteAccessRegistry registry = new RouteAccessRegistry(new PluginRegistry(BuiltInPlugins.createAll()));
        assertThat(registry.routes())
                .filteredOn(registered -> registered.pluginId().equals("stats"))
                .extracting(registered -> registered.route().pathPattern())
                .containsExactlyInAnyOrder("/pixiv-stats.html", "/pixiv-stats/**", "/api/stats/**");
    }

    @Test
    @DisplayName("register → unregister → 再 register 后快照与首次注册一致（可逆性）")
    void registerUnregisterRoundTrip() {
        RouteAccessRegistry registry = emptyRegistry();
        List<WebRouteContribution> routes = List.of(route("/demo.html"), route("/api/demo/**"));
        registry.register("demo", routes);
        List<RouteAccessRegistry.RegisteredRoute> first = registry.routes();
        registry.unregister("demo");
        assertThat(registry.routes()).isEmpty();
        registry.register("demo", routes);
        assertThat(registry.routes()).isEqualTo(first);
    }

    @Test
    @DisplayName("unregister 对未注册过路由的 pluginId 静默返回（统一卸载流程对每个插件都会调用）")
    void unregisterUnknownPluginIsSilent() {
        RouteAccessRegistry registry = emptyRegistry();
        registry.unregister("never-registered");
        assertThat(registry.routes()).isEmpty();
    }

    @Test
    @DisplayName("同一 pluginId 重复注册立即抛出")
    void duplicatePluginRegistrationRejected() {
        RouteAccessRegistry registry = emptyRegistry();
        registry.register("demo", List.of(route("/demo.html")));
        assertThatThrownBy(() -> registry.register("demo", List.of(route("/other.html"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("demo");
    }

    @Test
    @DisplayName("（模式, 方法集, 访问级别）三元组完全重复立即抛出；同模式不同三元组允许")
    void duplicateRouteTripleRejected() {
        RouteAccessRegistry registry = emptyRegistry();
        registry.register("a", List.of(route("/shared/**")));
        assertThatThrownBy(() -> registry.register("b", List.of(route("/shared/**"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("/shared/**");

        RouteAccessRegistry other = emptyRegistry();
        other.register("a", List.of(route("/shared/**")));
        other.register("b", List.of(new WebRouteContribution(
                "/shared/**", AccessLevel.GUEST_READ, Set.of(HttpMethod.GET), false)));
        assertThat(other.routes()).hasSize(2);
    }

    @Test
    @DisplayName("路径模式必须以 / 开头且非空，pluginId 必须非空")
    void invalidInputRejected() {
        RouteAccessRegistry registry = emptyRegistry();
        assertThatThrownBy(() -> registry.register("demo", List.of(route("no-slash"))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> registry.register("demo", List.of(route(" "))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> registry.register(" ", List.of(route("/demo.html"))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> registry.register("demo", List.of()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("routes() 返回不可变快照，外部不可修改")
    void snapshotIsImmutable() {
        RouteAccessRegistry registry = emptyRegistry();
        registry.register("demo", List.of(route("/demo.html")));
        List<RouteAccessRegistry.RegisteredRoute> routes = registry.routes();
        assertThatThrownBy(() -> routes.add(new RouteAccessRegistry.RegisteredRoute("x", route("/x.html"))))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
