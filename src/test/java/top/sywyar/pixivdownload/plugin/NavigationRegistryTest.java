package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.web.AccessLevel;
import top.sywyar.pixivdownload.plugin.api.web.NavigationContribution;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("NavigationRegistry 导航注册中心")
class NavigationRegistryTest {

    private static NavigationRegistry emptyRegistry() {
        return new NavigationRegistry(new PluginRegistry(List.of()));
    }

    private static NavigationContribution nav(String id) {
        return new NavigationContribution(id, "nav.label", "/" + id + ".html", "icon",
                AccessLevel.ADMIN_OR_SOLO, 10);
    }

    @Test
    @DisplayName("构造时从 PluginRegistry 收集全部内置插件导航项（四个功能插件各一条）")
    void collectsNavigationFromBuiltInPlugins() {
        NavigationRegistry registry = new NavigationRegistry(new PluginRegistry(BuiltInPlugins.createAll()));
        assertThat(registry.navigation())
                .extracting(registered -> registered.navigation().id())
                .containsExactlyInAnyOrder("gallery", "novel", "stats", "duplicate");
        assertThat(registry.navigation())
                .filteredOn(registered -> registered.navigation().id().equals("gallery"))
                .singleElement()
                .satisfies(registered -> {
                    assertThat(registered.pluginId()).isEqualTo("gallery");
                    assertThat(registered.navigation().visibleTo()).isEqualTo(AccessLevel.GUEST_READ);
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
    @DisplayName("导航 href 全局冲突立即抛出（跨插件不同 id 指向同一 href）")
    void duplicateNavigationHrefAcrossPluginsRejected() {
        NavigationRegistry registry = emptyRegistry();
        registry.register("a", List.of(new NavigationContribution(
                "a-id", "nav.label", "/shared.html", "icon", AccessLevel.ADMIN_OR_SOLO, 10)));
        assertThatThrownBy(() -> registry.register("b", List.of(new NavigationContribution(
                "b-id", "nav.label", "/shared.html", "icon", AccessLevel.ADMIN_OR_SOLO, 10))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("/shared.html")
                .hasMessageContaining("b");
    }

    @Test
    @DisplayName("同一插件内导航 href 重复也立即抛出（不同 id 指向同一 href）")
    void duplicateNavigationHrefWithinPluginRejected() {
        NavigationRegistry registry = emptyRegistry();
        assertThatThrownBy(() -> registry.register("demo", List.of(
                new NavigationContribution("x", "nav.label", "/dup.html", "icon", AccessLevel.ADMIN, 0),
                new NavigationContribution("y", "nav.label", "/dup.html", "icon", AccessLevel.ADMIN, 0))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("/dup.html")
                .hasMessageContaining("demo");
    }

    @Test
    @DisplayName("非法输入拒绝：pluginId / id / labelI18nKey / href 非空，visibleTo 非 null，导航列表非空")
    void invalidInputRejected() {
        NavigationRegistry registry = emptyRegistry();
        assertThatThrownBy(() -> registry.register(" ", List.of(nav("a"))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> registry.register("demo", List.of()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> registry.register("demo", List.of(
                new NavigationContribution(" ", "nav.label", "/a.html", "icon", AccessLevel.ADMIN, 0))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> registry.register("demo", List.of(
                new NavigationContribution("a", " ", "/a.html", "icon", AccessLevel.ADMIN, 0))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> registry.register("demo", List.of(
                new NavigationContribution("a", "nav.label", " ", "icon", AccessLevel.ADMIN, 0))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> registry.register("demo", List.of(
                new NavigationContribution("a", "nav.label", "/a.html", "icon", null, 0))))
                .isInstanceOf(IllegalStateException.class);
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
}
