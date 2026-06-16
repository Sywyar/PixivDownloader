package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.web.TabContribution;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("DownloadTabRegistry 获取方式标签页注册中心")
class DownloadTabRegistryTest {

    private static DownloadTabRegistry emptyRegistry() {
        return new DownloadTabRegistry(new PluginRegistry(List.of()));
    }

    private static TabContribution tab(String tabId) {
        return new TabContribution("demo", tabId, 10, List.of("illust", "novel"));
    }

    @Test
    @DisplayName("构造时从 PluginRegistry 收集全部内置标签页（下载工作台声明 5 个获取方式标签页）")
    void collectsTabsFromBuiltInPlugins() {
        DownloadTabRegistry registry = new DownloadTabRegistry(new PluginRegistry(BuiltInPlugins.createAll()));
        assertThat(registry.tabs())
                .extracting(registered -> registered.tab().tabId())
                .containsExactlyInAnyOrder("quick-fetch", "single-import", "user", "search", "series");
        assertThat(registry.tabs())
                .allSatisfy(registered -> {
                    assertThat(registered.pluginId()).isEqualTo("download-workbench");
                    assertThat(registered.tab().supportedQueueTypes()).contains("illust", "novel");
                });
    }

    @Test
    @DisplayName("register → unregister → 再 register 后快照与首次注册一致（可逆性）")
    void registerUnregisterRoundTrip() {
        DownloadTabRegistry registry = emptyRegistry();
        List<TabContribution> items = List.of(tab("a"), tab("b"));
        registry.register("demo", items);
        List<DownloadTabRegistry.RegisteredTab> first = registry.tabs();
        registry.unregister("demo");
        assertThat(registry.tabs()).isEmpty();
        registry.register("demo", items);
        assertThat(registry.tabs()).isEqualTo(first);
    }

    @Test
    @DisplayName("unregister 对未注册过标签页的 pluginId 静默返回（统一卸载流程对每个插件都会调用）")
    void unregisterUnknownPluginIsSilent() {
        DownloadTabRegistry registry = emptyRegistry();
        registry.unregister("never-registered");
        assertThat(registry.tabs()).isEmpty();
    }

    @Test
    @DisplayName("同一 pluginId 重复注册立即抛出")
    void duplicatePluginRegistrationRejected() {
        DownloadTabRegistry registry = emptyRegistry();
        registry.register("demo", List.of(tab("a")));
        assertThatThrownBy(() -> registry.register("demo", List.of(tab("b"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("demo");
    }

    @Test
    @DisplayName("标签页 id 全局冲突立即抛出（跨插件不可重名）")
    void duplicateTabIdAcrossPluginsRejected() {
        DownloadTabRegistry registry = emptyRegistry();
        registry.register("a", List.of(tab("shared")));
        assertThatThrownBy(() -> registry.register("b", List.of(tab("shared"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("shared");
    }

    @Test
    @DisplayName("非法输入拒绝：pluginId / tabId 非空，支持类型列表非空，标签页列表非空")
    void invalidInputRejected() {
        DownloadTabRegistry registry = emptyRegistry();
        assertThatThrownBy(() -> registry.register(" ", List.of(tab("a"))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> registry.register("demo", List.of()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> registry.register("demo", List.of(
                new TabContribution("demo", " ", 0, List.of("illust")))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> registry.register("demo", List.of(
                new TabContribution("demo", "a", 0, List.of()))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("tabs() 返回不可变快照，外部不可修改")
    void snapshotIsImmutable() {
        DownloadTabRegistry registry = emptyRegistry();
        registry.register("demo", List.of(tab("a")));
        List<DownloadTabRegistry.RegisteredTab> snapshot = registry.tabs();
        assertThatThrownBy(() -> snapshot.add(
                new DownloadTabRegistry.RegisteredTab("x", tab("x"))))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
