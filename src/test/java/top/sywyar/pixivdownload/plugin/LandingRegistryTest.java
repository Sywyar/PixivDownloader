package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.api.web.AccessPolicy;
import top.sywyar.pixivdownload.plugin.api.web.Audience;
import top.sywyar.pixivdownload.plugin.api.web.HttpMethod;
import top.sywyar.pixivdownload.plugin.api.web.LandingContribution;
import top.sywyar.pixivdownload.plugin.api.web.NavigationContribution;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 落点 / 入口注册中心：把「业务落点选择」与「UI 导航排序」彻底拆开。落点只消费 {@link LandingContribution}
 * 的 priority、不读导航 order，故第三方插件无法借更小的导航 order 间接改变邀请落点；只收启用插件；并由
 * 可达性守卫确保落点 href 对其 audience 可达（不静默产生坏入口）。
 */
@DisplayName("LandingRegistry 落点 / 入口注册中心")
class LandingRegistryTest {

    private static LandingRegistry emptyRegistry() {
        return new LandingRegistry(new PluginRegistry(List.of()));
    }

    private static LandingContribution landing(String pluginId, String id, Audience audience, int priority) {
        return new LandingContribution(pluginId, id, audience, "/" + id + ".html", priority);
    }

    private static PluginToggleProperties disabling(String... ids) {
        PluginToggleProperties toggles = new PluginToggleProperties();
        for (String id : ids) {
            PluginToggleProperties.PluginToggle off = new PluginToggleProperties.PluginToggle();
            off.setEnabled(false);
            toggles.put(id, off);
        }
        return toggles;
    }

    @Test
    @DisplayName("内置插件落点：受邀访客 gallery(priority 20) 优先于 novel(priority 30) → 画廊落点")
    void builtInResolvesInvitedGuestToGallery() {
        LandingRegistry registry = LandingRegistry.forBuiltInPlugins();
        assertThat(registry.resolve(Audience.INVITED_GUEST)).contains("/pixiv-gallery.html");
    }

    @Test
    @DisplayName("禁用画廊后受邀访客落点回退到小说（注册中心只收启用插件，禁用插件不贡献落点）")
    void fallsBackToNovelWhenGalleryDisabled() {
        LandingRegistry registry = new LandingRegistry(
                new PluginRegistry(BuiltInPlugins.createAll(), disabling("gallery")));
        assertThat(registry.resolve(Audience.INVITED_GUEST)).contains("/pixiv-novel-gallery.html");
        assertThat(registry.landings())
                .extracting(LandingRegistry.RegisteredLanding::pluginId)
                .doesNotContain("gallery").contains("novel");
    }

    @Test
    @DisplayName("画廊 + 小说都禁用：受邀访客落点为空（由调用方兜底回 /login.html?inviteError=1）")
    void emptyWhenBothDisabled() {
        LandingRegistry registry = new LandingRegistry(
                new PluginRegistry(BuiltInPlugins.createAll(), disabling("gallery", "novel")));
        assertThat(registry.resolve(Audience.INVITED_GUEST)).isEmpty();
    }

    @Test
    @DisplayName("空注册中心 / null 身份解析返回空")
    void emptyRegistryResolvesEmpty() {
        assertThat(emptyRegistry().resolve(Audience.INVITED_GUEST)).isEmpty();
        assertThat(emptyRegistry().resolve(null)).isEmpty();
    }

    @Test
    @DisplayName("第三方插件注册更小 priority 的导航项不影响邀请落点（落点与导航排序解耦）")
    void smallerNavOrderDoesNotHijackLanding() {
        // intruder 声明一个 priority=1（远小于画廊导航 priority 30）的导航项，但不声明任何落点。
        // 若落点错误地复用了导航 priority，它会被劫持；正确实现下落点只看 landings()，故不受影响。
        PixivFeaturePlugin intruder = new TestPlugin("intruder") {
            @Override
            public List<NavigationContribution> navigation() {
                return List.of(new NavigationContribution(
                        "intruder", "app.top", "nav.intruder", "/intruder.html", "icon",
                        AccessPolicy.INVITED_GUEST, 1));
            }
        };
        List<PixivFeaturePlugin> plugins = new ArrayList<>(BuiltInPlugins.createAll());
        plugins.add(intruder);
        LandingRegistry registry = new LandingRegistry(new PluginRegistry(plugins));
        assertThat(registry.resolve(Audience.INVITED_GUEST)).contains("/pixiv-gallery.html");
    }

    @Test
    @DisplayName("显式声明更小 priority 的落点才能改变邀请落点（priority 主导落点、非 nav order）")
    void smallerLandingPriorityWins() {
        PixivFeaturePlugin contender = new TestPlugin("contender") {
            @Override
            public List<LandingContribution> landings() {
                return List.of(new LandingContribution(
                        "contender", "contender", Audience.INVITED_GUEST, "/contender.html", 5));
            }
        };
        List<PixivFeaturePlugin> plugins = new ArrayList<>(BuiltInPlugins.createAll());
        plugins.add(contender);
        LandingRegistry registry = new LandingRegistry(new PluginRegistry(plugins));
        assertThat(registry.resolve(Audience.INVITED_GUEST)).contains("/contender.html");
    }

    @Test
    @DisplayName("按身份分别解析：某身份的落点不会泄漏给另一身份")
    void resolvesPerAudience() {
        LandingRegistry registry = emptyRegistry();
        registry.register("p", List.of(
                landing("p", "vis", Audience.VISITOR, 5),
                landing("p", "guest", Audience.INVITED_GUEST, 50)));
        assertThat(registry.resolve(Audience.INVITED_GUEST)).contains("/guest.html");
        assertThat(registry.resolve(Audience.VISITOR)).contains("/vis.html");
        assertThat(registry.resolve(Audience.ADMIN)).isEmpty();
    }

    @Test
    @DisplayName("同一身份多落点取 priority 最小者（priority 相同按 id 稳定）")
    void resolvesByPriorityThenId() {
        LandingRegistry registry = emptyRegistry();
        registry.register("p", List.of(
                landing("p", "b-high", Audience.INVITED_GUEST, 10),
                landing("p", "a-tie", Audience.INVITED_GUEST, 5),
                landing("p", "c-tie", Audience.INVITED_GUEST, 5)));
        // priority 5 的两条按 id 稳定取 "a-tie"
        assertThat(registry.resolve(Audience.INVITED_GUEST)).contains("/a-tie.html");
    }

    @Test
    @DisplayName("register → unregister → 再 register 后快照与首次一致（可逆性）")
    void registerUnregisterRoundTrip() {
        LandingRegistry registry = emptyRegistry();
        List<LandingContribution> items = List.of(landing("demo", "a", Audience.INVITED_GUEST, 10));
        registry.register("demo", items);
        List<LandingRegistry.RegisteredLanding> first = registry.landings();
        registry.unregister("demo");
        assertThat(registry.landings()).isEmpty();
        registry.register("demo", items);
        assertThat(registry.landings()).isEqualTo(first);
    }

    @Test
    @DisplayName("unregister 对未注册过的 pluginId 静默返回")
    void unregisterUnknownIsSilent() {
        LandingRegistry registry = emptyRegistry();
        registry.unregister("never-registered");
        assertThat(registry.landings()).isEmpty();
    }

    @Test
    @DisplayName("同一 pluginId 重复注册立即抛出")
    void duplicatePluginRejected() {
        LandingRegistry registry = emptyRegistry();
        registry.register("demo", List.of(landing("demo", "a", Audience.INVITED_GUEST, 1)));
        assertThatThrownBy(() -> registry.register("demo",
                List.of(landing("demo", "b", Audience.INVITED_GUEST, 2))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("demo");
    }

    @Test
    @DisplayName("落点 id 全局冲突立即抛出（跨插件不可重名）")
    void duplicateIdRejected() {
        LandingRegistry registry = emptyRegistry();
        registry.register("a", List.of(landing("a", "shared", Audience.INVITED_GUEST, 1)));
        assertThatThrownBy(() -> registry.register("b",
                List.of(landing("b", "shared", Audience.INVITED_GUEST, 2))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("shared");
    }

    @Test
    @DisplayName("非法输入拒绝：pluginId 不符 / id 空 / audience 空 / href 非法 / 列表空")
    void invalidInputRejected() {
        LandingRegistry registry = emptyRegistry();
        // pluginId 与声明方不一致
        assertThatThrownBy(() -> registry.register("demo", List.of(
                new LandingContribution("other", "a", Audience.INVITED_GUEST, "/a.html", 1))))
                .isInstanceOf(IllegalStateException.class);
        // 空列表
        assertThatThrownBy(() -> registry.register("demo", List.of()))
                .isInstanceOf(IllegalStateException.class);
        // id 空
        assertThatThrownBy(() -> registry.register("demo", List.of(
                new LandingContribution("demo", " ", Audience.INVITED_GUEST, "/a.html", 1))))
                .isInstanceOf(IllegalStateException.class);
        // audience 空
        assertThatThrownBy(() -> registry.register("demo", List.of(
                new LandingContribution("demo", "a", null, "/a.html", 1))))
                .isInstanceOf(IllegalStateException.class);
        // href 非法（不以 / 开头）
        assertThatThrownBy(() -> registry.register("demo", List.of(
                new LandingContribution("demo", "a", Audience.INVITED_GUEST, "a.html", 1))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("landings() 返回不可变快照，外部不可修改")
    void snapshotIsImmutable() {
        LandingRegistry registry = emptyRegistry();
        registry.register("demo", List.of(landing("demo", "a", Audience.INVITED_GUEST, 1)));
        List<LandingRegistry.RegisteredLanding> snapshot = registry.landings();
        assertThatThrownBy(() -> snapshot.add(
                new LandingRegistry.RegisteredLanding("x", landing("x", "x", Audience.INVITED_GUEST, 1))))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("可达性守卫：每条内置落点 href 对其 audience 可达（RouteAccessRegistry 解析的访问策略放行该身份）")
    void builtInLandingsAreReachableByTheirAudience() {
        // 用同一插件集构建落点与路由注册中心，逐条比对：落点指向的路由必须存在且其访问策略放行该 audience，
        // 否则属配置错误（坏入口）。这是「landing 指向不可达 route 时测试明确失败」的覆盖。
        PluginRegistry plugins = new PluginRegistry(BuiltInPlugins.createAll());
        LandingRegistry landings = new LandingRegistry(plugins);
        RouteAccessRegistry routes = new RouteAccessRegistry(plugins);

        assertThat(landings.landings()).isNotEmpty();
        for (LandingRegistry.RegisteredLanding registered : landings.landings()) {
            LandingContribution item = registered.landing();
            String path = item.href();
            int q = path.indexOf('?');
            if (q >= 0) {
                path = path.substring(0, q);
            }
            Optional<RouteAccessRegistry.RegisteredRoute> route = routes.resolve(path, HttpMethod.GET);
            assertThat(route)
                    .as("落点 %s 的 href %s 应命中一条已声明路由", item.id(), item.href())
                    .isPresent();
            assertThat(route.get().route().accessPolicy().admits(item.audience()))
                    .as("落点 %s 的 href %s 对应路由策略 %s 应放行其 audience %s",
                            item.id(), item.href(), route.get().route().accessPolicy(), item.audience())
                    .isTrue();
        }
    }

    /** 最小测试插件：默认无任何 contribution，子类按需覆写 navigation() / landings()。 */
    private static class TestPlugin implements PixivFeaturePlugin {
        private final String id;

        TestPlugin(String id) {
            this.id = id;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String displayName() {
            return "nav.label";
        }

        @Override
        public String description() {
            return "plugin.summary";
        }

        @Override
        public PluginKind kind() {
            return PluginKind.FEATURE;
        }
    }
}
