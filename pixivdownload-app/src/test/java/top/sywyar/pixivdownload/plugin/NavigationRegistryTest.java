package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.web.AccessPolicy;
import top.sywyar.pixivdownload.plugin.api.web.NavigationContribution;
import top.sywyar.pixivdownload.plugin.api.web.NavigationPlacements;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import top.sywyar.pixivdownload.plugin.registry.NavigationRegistry;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;

@DisplayName("NavigationRegistry 导航注册中心")
class NavigationRegistryTest {

    private static NavigationRegistry emptyRegistry() {
        return new NavigationRegistry(new PluginRegistry(List.of()));
    }

    private static NavigationContribution nav(String id) {
        return new NavigationContribution(id, "app.top", "ns", "nav.label", "/" + id + ".html", "icon",
                AccessPolicy.ADMIN, 10);
    }

    @Test
    @DisplayName("gallery/novel-gallery 已安装时从 PluginRegistry 收集导航项（主入口 + 类型切换 + 统计页画廊视图 + 图标）")
    void collectsNavigationFromBuiltInPlugins() {
        NavigationRegistry registry = new NavigationRegistry(new PluginRegistry(builtInWithGalleryAndNovelGallery()));
        // 每条导航项是一个逻辑入口（id 全局唯一、可经 placements 同时进入多个 slot）。内置入口全集：
        // 邀请码管理 / 插件入口 / 插件市场页内分段入口 + 画廊主入口及其类型切换 / 统计页视图三链
        // + 小说画廊主入口及其类型切换。画廊的疑似重复页图标由主入口经 placement 兼任、不另立 id。统计 stats 与 duplicate 已外置，
        // download-workbench / stats / duplicate 导航项经外置插件 contribution 注册、不在内置清单。
        assertThat(registry.navigation())
                .extracting(registered -> registered.navigation().id())
                .containsExactlyInAnyOrder(
                        "invite-manage", "plugin-manage", "plugin-market",
                        "gallery", "gallery-type-switch",
                        "gallery-view-all", "gallery-view-authors", "gallery-view-series",
                        "gallery-gui-open", "gallery-invite-manage-back",
                        "novel-gallery", "novel-type-switch");
        // 画廊主入口归 gallery 插件、INVITED_GUEST 可见、进入顶部栏 + 中立主侧栏 + 画廊侧栏 + 疑似重复图标区四个 placement。
        assertThat(registry.navigation())
                .filteredOn(registered -> registered.navigation().id().equals("gallery"))
                .singleElement()
                .satisfies(registered -> {
                    assertThat(registered.pluginId()).isEqualTo("gallery");
                    assertThat(registered.navigation().visibleTo()).isEqualTo(AccessPolicy.INVITED_GUEST);
                    assertThat(registered.navigation().markers()).containsExactly("first-download-result");
                    assertThat(registered.navigation().placements())
                            .containsExactlyInAnyOrder("app.top", "app.sidebar", "gallery.sidebar", "duplicates.header-icons");
                });
        assertThat(registry.navigation())
                .filteredOn(registered -> registered.navigation().id().equals("gallery-gui-open"))
                .singleElement()
                .satisfies(registered -> {
                    assertThat(registered.pluginId()).isEqualTo("gallery");
                    assertThat(registered.navigation().labelNamespace()).isEqualTo("gallery");
                    assertThat(registered.navigation().labelI18nKey()).isEqualTo("gui.action.open");
                    assertThat(registered.navigation().href()).isEqualTo("/pixiv-gallery.html");
                    assertThat(registered.navigation().placements())
                            .containsExactlyInAnyOrder("gui.status.actions", "gui.tray.actions");
                });
        assertThat(registry.navigation())
                .filteredOn(registered -> registered.navigation().id().equals("gallery-invite-manage-back"))
                .singleElement()
                .satisfies(registered -> {
                    assertThat(registered.pluginId()).isEqualTo("gallery");
                    assertThat(registered.navigation().labelNamespace()).isEqualTo("gallery");
                    assertThat(registered.navigation().labelI18nKey()).isEqualTo("invite.manage.back");
                    assertThat(registered.navigation().href()).isEqualTo("/pixiv-gallery.html?view=all");
                    assertThat(registered.navigation().placements()).containsExactly("invite.manage.back");
                });
        // 邀请码管理 / 插件入口归 core 插件、ADMIN 可见；邀请码管理只进侧栏、不进顶部栏 placement，
        // 插件入口只进顶部栏 placement（app.top）、不进侧栏。
        assertThat(registry.navigation())
                .filteredOn(registered -> registered.pluginId().equals("core"))
                .extracting(registered -> registered.navigation().id())
                .containsExactlyInAnyOrder("invite-manage", "plugin-manage");
        assertThat(registry.navigation())
                .filteredOn(registered -> registered.navigation().id().equals("invite-manage"))
                .singleElement()
                .satisfies(registered -> assertThat(registered.navigation().placements())
                        .containsExactlyInAnyOrder("app.sidebar", "gallery.sidebar", "novel.sidebar"));
        assertThat(registry.navigation())
                .filteredOn(registered -> registered.navigation().id().equals("plugin-manage"))
                .singleElement()
                .satisfies(registered -> assertThat(registered.navigation().placements())
                        .containsExactly("app.top"));
        // 插件市场导航归 plugin-market 插件、ADMIN 可见、只进插件页内分段 placement；随插件启停进出快照。
        assertThat(registry.navigation())
                .filteredOn(registered -> registered.navigation().id().equals("plugin-market"))
                .singleElement()
                .satisfies(registered -> {
                    assertThat(registered.pluginId()).isEqualTo("plugin-market");
                    assertThat(registered.navigation().visibleTo()).isEqualTo(AccessPolicy.ADMIN);
                    assertThat(registered.navigation().placements()).containsExactly(NavigationPlacements.PLUGINS_SEGMENT);
                });
    }

    @Test
    @DisplayName("register → unregister → 再 register 后快照与首次注册一致（可逆性）")
    void registerUnregisterRoundTrip() {
        NavigationRegistry registry = emptyRegistry();
        List<NavigationContribution> items = List.of(nav("a"), nav("b"));
        registry.register("demo", items);
        List<NavigationRegistry.RegisteredNavigation> first = registry.navigation();
        registry.unregister("demo");
        assertThat(registry.navigation()).isEmpty();
        registry.register("demo", items);
        assertThat(registry.navigation()).isEqualTo(first);
    }

    @Test
    @DisplayName("unregister 对未注册过导航的 pluginId 静默返回（统一卸载流程对每个插件都会调用）")
    void unregisterUnknownPluginIsSilent() {
        NavigationRegistry registry = emptyRegistry();
        registry.unregister("never-registered");
        assertThat(registry.navigation()).isEmpty();
    }

    @Test
    @DisplayName("同一 pluginId 重复注册立即抛出")
    void duplicatePluginRegistrationRejected() {
        NavigationRegistry registry = emptyRegistry();
        registry.register("demo", List.of(nav("a")));
        assertThatThrownBy(() -> registry.register("demo", List.of(nav("b"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("demo");
    }

    @Test
    @DisplayName("导航 id 全局冲突立即抛出（跨插件不可重名）")
    void duplicateNavigationIdRejected() {
        NavigationRegistry registry = emptyRegistry();
        registry.register("a", List.of(nav("shared")));
        assertThatThrownBy(() -> registry.register("b", List.of(nav("shared"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("shared");
    }

    @Test
    @DisplayName("同一插件内导航 id 重复也立即抛出")
    void duplicateNavigationIdWithinPluginRejected() {
        NavigationRegistry registry = emptyRegistry();
        assertThatThrownBy(() -> registry.register("demo", List.of(nav("dup"), nav("dup"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dup");
    }

    @Test
    @DisplayName("同一 placement 内导航 href 冲突立即抛出（同一菜单不可有两条指向同一页的入口）")
    void duplicateNavigationHrefWithinPlacementRejected() {
        NavigationRegistry registry = emptyRegistry();
        registry.register("a", List.of(new NavigationContribution(
                "a-id", "app.top", "ns", "nav.label", "/shared.html", "icon", AccessPolicy.ADMIN, 10)));
        assertThatThrownBy(() -> registry.register("b", List.of(new NavigationContribution(
                "b-id", "app.top", "ns", "nav.label", "/shared.html", "icon", AccessPolicy.ADMIN, 10))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("/shared.html")
                .hasMessageContaining("app.top");
    }

    @Test
    @DisplayName("同一插件、同一 placement 内 href 重复也立即抛出（不同 id 指向同一 href）")
    void duplicateNavigationHrefWithinPluginRejected() {
        NavigationRegistry registry = emptyRegistry();
        assertThatThrownBy(() -> registry.register("demo", List.of(
                new NavigationContribution("x", "app.top", "ns", "nav.label", "/dup.html", "icon", AccessPolicy.ADMIN, 0),
                new NavigationContribution("y", "app.top", "ns", "nav.label", "/dup.html", "icon", AccessPolicy.ADMIN, 0))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("/dup.html")
                .hasMessageContaining("demo");
    }

    @Test
    @DisplayName("不同 placement 可复用同一 href（href 仅在 placement 内唯一，如类型切换 tab 与图标都指向画廊）")
    void sameHrefAcrossPlacementsAllowed() {
        NavigationRegistry registry = emptyRegistry();
        registry.register("a", List.of(new NavigationContribution(
                "a-id", "app.top", "ns", "nav.label", "/shared.html", "icon", AccessPolicy.ADMIN, 10)));
        registry.register("b", List.of(new NavigationContribution(
                "b-id", "gallery.sidebar", "ns", "nav.label", "/shared.html", "icon", AccessPolicy.ADMIN, 10)));
        assertThat(registry.navigation()).hasSize(2);
    }

    @Test
    @DisplayName("一条导航项可同时进入多个 placement（placements 集合保真）")
    void multiPlacementContributionPreserved() {
        NavigationRegistry registry = emptyRegistry();
        registry.register("demo", List.of(new NavigationContribution(
                "multi", Set.of("app.top", "gallery.sidebar", "novel.sidebar"),
                "ns", "nav.label", "/multi.html", "icon", AccessPolicy.ADMIN, 10)));
        assertThat(registry.navigation()).singleElement()
                .satisfies(registered -> assertThat(registered.navigation().placements())
                        .containsExactlyInAnyOrder("app.top", "gallery.sidebar", "novel.sidebar"));
    }

    @Test
    @DisplayName("非法输入拒绝：pluginId / id / labelI18nKey / href 非空，visibleTo 非 null，placements 和 markers 不含空白")
    void invalidInputRejected() {
        NavigationRegistry registry = emptyRegistry();
        assertThatThrownBy(() -> registry.register(" ", List.of(nav("a"))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> registry.register("demo", List.of()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> registry.register("demo", List.of(
                new NavigationContribution(" ", "app.top", "ns", "nav.label", "/a.html", "icon", AccessPolicy.ADMIN, 0))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> registry.register("demo", List.of(
                new NavigationContribution("a", "app.top", "ns", " ", "/a.html", "icon", AccessPolicy.ADMIN, 0))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> registry.register("demo", List.of(
                new NavigationContribution("a", "app.top", "ns", "nav.label", " ", "icon", AccessPolicy.ADMIN, 0))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> registry.register("demo", List.of(
                new NavigationContribution("a", "app.top", "ns", "nav.label", "/a.html", "icon", null, 0))))
                .isInstanceOf(IllegalStateException.class);
        // placements 为空
        assertThatThrownBy(() -> registry.register("demo", List.of(
                new NavigationContribution("a", Set.<String>of(), "ns", "nav.label", "/a.html", "icon", AccessPolicy.ADMIN, 0))))
                .isInstanceOf(IllegalStateException.class);
        // placements 含空白项
        assertThatThrownBy(() -> registry.register("demo", List.of(
                new NavigationContribution("a", Set.of(" "), "ns", "nav.label", "/a.html", "icon", AccessPolicy.ADMIN, 0))))
                .isInstanceOf(IllegalStateException.class);
        // markers 含空白项
        assertThatThrownBy(() -> registry.register("demo", List.of(
                new NavigationContribution("a", Set.of("app.top"), "ns", "nav.label", "/a.html", "icon",
                        AccessPolicy.ADMIN, 0, Set.of(" ")))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("labelNamespace 为 null / 空白被接受（有意的回退语义、不 fail-fast；缺省值原样保真供消费端回退）")
    void blankLabelNamespaceAcceptedAsIntentionalFallback() {
        NavigationRegistry registry = emptyRegistry();
        // 与必填的 PageSection.titleNamespace / QueueType.labelNamespace 刻意不同：导航 labelNamespace 允许缺省，
        // 表示该入口未绑定确定 namespace，由前端 tns 退化为裸 key、在页面首个 namespace 内解析。注册期不抛。
        assertThatCode(() -> registry.register("a", List.of(new NavigationContribution(
                "n-null", "app.top", null, "nav.label", "/n-null.html", "icon", AccessPolicy.ADMIN, 10))))
                .doesNotThrowAnyException();
        assertThatCode(() -> registry.register("b", List.of(new NavigationContribution(
                "n-blank", "app.top", "  ", "nav.label", "/n-blank.html", "icon", AccessPolicy.ADMIN, 10))))
                .doesNotThrowAnyException();
        // 缺省的 namespace 原样保真（消费端据此判定回退），不被规整成某个默认值。
        assertThat(registry.navigation())
                .filteredOn(r -> r.navigation().id().equals("n-null"))
                .singleElement()
                .satisfies(r -> assertThat(r.navigation().labelNamespace()).isNull());
        assertThat(registry.navigation())
                .filteredOn(r -> r.navigation().id().equals("n-blank"))
                .singleElement()
                .satisfies(r -> assertThat(r.navigation().labelNamespace()).isEqualTo("  "));
    }

    @Test
    @DisplayName("navigation() 返回不可变快照，外部不可修改")
    void snapshotIsImmutable() {
        NavigationRegistry registry = emptyRegistry();
        registry.register("demo", List.of(nav("a")));
        List<NavigationRegistry.RegisteredNavigation> navigation = registry.navigation();
        assertThatThrownBy(() -> navigation.add(
                new NavigationRegistry.RegisteredNavigation("x", nav("x"))))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static List<top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin> builtInWithGalleryAndNovelGallery() {
        java.util.ArrayList<top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin> plugins =
                new java.util.ArrayList<>(BuiltInPlugins.createAll());
        plugins.add(new TestGalleryPlugin());
        plugins.add(new TestNovelGalleryPlugin());
        return plugins;
    }
}
