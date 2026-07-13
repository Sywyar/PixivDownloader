package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.api.web.WebUiSlotContribution;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.registry.WebUiSlotRegistry;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("WebUiSlotRegistry UI 槽位注册中心")
class WebUiSlotRegistryTest {

    private static WebUiSlotRegistry emptyRegistry() {
        return new WebUiSlotRegistry(new PluginRegistry(List.of()));
    }

    private static WebUiSlotContribution slot(String pluginId, String slotId) {
        return new WebUiSlotContribution(pluginId, slotId, "anchor-" + slotId, "/" + pluginId + "/slot.js", 10);
    }

    @Test
    @DisplayName("core-only 不携带小说下载 UI 槽位，外置 novel 插件贡献下载页 8 个槽位锚点")
    void collectsUiSlotsFromBuiltInPlugins() {
        WebUiSlotRegistry registry = new WebUiSlotRegistry(new PluginRegistry(BuiltInPlugins.createAll()));
        assertThat(registry.slots()).isEmpty();

        WebUiSlotRegistry withNovel = new WebUiSlotRegistry(
                new PluginRegistry(List.of(new TestNovelGalleryPlugin())));
        assertThat(withNovel.slots())
                .filteredOn(registered -> registered.pluginId().equals("novel"))
                .extracting(registered -> registered.slot().target())
                .containsExactlyInAnyOrder(
                        "kind-option-user", "kind-option-search", "kind-option-quick",
                        "quick-actions-bookmarks", "quick-actions-mine",
                        "import-hint", "search-filter", "settings-card");
        // 槽位由小说自有行为模块渲染（与 queueType moduleUrl 同一模块）。
        assertThat(withNovel.slots())
                .filteredOn(registered -> registered.pluginId().equals("novel"))
                .allSatisfy(registered -> assertThat(registered.slot().moduleUrl())
                        .isEqualTo("/pixiv-novel-download/novel-queue-type.js"));
    }

    @Test
    @DisplayName("register → unregister → 再 register 后快照与首次注册一致（可逆性）")
    void registerUnregisterRoundTrip() {
        WebUiSlotRegistry registry = emptyRegistry();
        List<WebUiSlotContribution> items = List.of(slot("demo", "demo.a"), slot("demo", "demo.b"));
        registry.register("demo", items);
        List<WebUiSlotRegistry.RegisteredUiSlot> first = registry.slots();
        registry.unregister("demo");
        assertThat(registry.slots()).isEmpty();
        registry.register("demo", items);
        assertThat(registry.slots()).isEqualTo(first);
    }

    @Test
    @DisplayName("启动期槽位使用 RegisteredPlugin 稳定 id，不二次调用插件 id getter")
    void bootSlotsUseStableRegisteredIdentity() {
        FlakyIdSlotPlugin plugin = new FlakyIdSlotPlugin();

        WebUiSlotRegistry registry = new WebUiSlotRegistry(new PluginRegistry(List.of(plugin)));

        assertThat(plugin.idReads()).isOne();
        assertThat(registry.slots()).singleElement()
                .extracting(WebUiSlotRegistry.RegisteredUiSlot::pluginId)
                .isEqualTo("flaky-slot-id");
    }

    @Test
    @DisplayName("unregister 对未注册过槽位的 pluginId 静默返回（统一卸载流程对每个插件都会调用）")
    void unregisterUnknownPluginIsSilent() {
        WebUiSlotRegistry registry = emptyRegistry();
        registry.unregister("never-registered");
        assertThat(registry.slots()).isEmpty();
    }

    @Test
    @DisplayName("按 pluginId 精准注销：只去除目标插件的槽位，其它插件保留")
    void unregisterIsPluginScoped() {
        WebUiSlotRegistry registry = emptyRegistry();
        registry.register("a", List.of(slot("a", "a.one")));
        registry.register("b", List.of(slot("b", "b.one")));
        registry.unregister("a");
        assertThat(registry.slots()).extracting(WebUiSlotRegistry.RegisteredUiSlot::pluginId)
                .containsExactly("b");
    }

    @Test
    @DisplayName("同一 pluginId 重复注册立即抛出")
    void duplicatePluginRegistrationRejected() {
        WebUiSlotRegistry registry = emptyRegistry();
        registry.register("demo", List.of(slot("demo", "demo.a")));
        assertThatThrownBy(() -> registry.register("demo", List.of(slot("demo", "demo.b"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("demo");
    }

    @Test
    @DisplayName("slotId 全局冲突立即抛出（跨插件不可重名）")
    void duplicateSlotIdAcrossPluginsRejected() {
        WebUiSlotRegistry registry = emptyRegistry();
        registry.register("a", List.of(slot("a", "shared.slot")));
        assertThatThrownBy(() -> registry.register("b", List.of(slot("b", "shared.slot"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("shared.slot");
    }

    @Test
    @DisplayName("同一插件内 slotId 重复也立即抛出")
    void duplicateSlotIdWithinPluginRejected() {
        WebUiSlotRegistry registry = emptyRegistry();
        assertThatThrownBy(() -> registry.register("demo", List.of(slot("demo", "dup"), slot("demo", "dup"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dup");
    }

    @Test
    @DisplayName("moduleUrl 为 null（宿主内联渲染）放行")
    void nullModuleUrlAllowed() {
        WebUiSlotRegistry registry = emptyRegistry();
        registry.register("demo", List.of(new WebUiSlotContribution("demo", "demo.inline", "anchor", null, 0)));
        assertThat(registry.slots()).singleElement()
                .satisfies(registered -> assertThat(registered.slot().moduleUrl()).isNull());
    }

    @Test
    @DisplayName("非法输入拒绝：pluginId / slotId / target 非空，pluginId 与声明一致，moduleUrl 非空时须同源绝对路径")
    void invalidInputRejected() {
        WebUiSlotRegistry registry = emptyRegistry();
        // 空 pluginId
        assertThatThrownBy(() -> registry.register(" ", List.of(slot("demo", "a"))))
                .isInstanceOf(IllegalStateException.class);
        // 空贡献列表
        assertThatThrownBy(() -> registry.register("demo", List.of()))
                .isInstanceOf(IllegalStateException.class);
        // 空 slotId
        assertThatThrownBy(() -> registry.register("demo", List.of(
                new WebUiSlotContribution("demo", " ", "anchor", "/demo/s.js", 0))))
                .isInstanceOf(IllegalStateException.class);
        // 空 target
        assertThatThrownBy(() -> registry.register("demo", List.of(
                new WebUiSlotContribution("demo", "demo.a", " ", "/demo/s.js", 0))))
                .isInstanceOf(IllegalStateException.class);
        // pluginId 与声明不一致
        assertThatThrownBy(() -> registry.register("demo", List.of(
                new WebUiSlotContribution("other", "demo.a", "anchor", "/demo/s.js", 0))))
                .isInstanceOf(IllegalStateException.class);
        // moduleUrl 带协议（外部 URL）
        assertThatThrownBy(() -> registry.register("demo", List.of(
                new WebUiSlotContribution("demo", "demo.a", "anchor", "https://evil.example/s.js", 0))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("same-origin");
        // moduleUrl 协议相对（//host）
        assertThatThrownBy(() -> registry.register("demo", List.of(
                new WebUiSlotContribution("demo", "demo.a", "anchor", "//evil.example/s.js", 0))))
                .isInstanceOf(IllegalStateException.class);
        // moduleUrl 反斜杠变体（/\host）
        assertThatThrownBy(() -> registry.register("demo", List.of(
                new WebUiSlotContribution("demo", "demo.a", "anchor", "/\\evil.example/s.js", 0))))
                .isInstanceOf(IllegalStateException.class);
        // moduleUrl 伪协议（不以 / 开头）
        assertThatThrownBy(() -> registry.register("demo", List.of(
                new WebUiSlotContribution("demo", "demo.a", "anchor", "javascript:alert(1)", 0))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("metadata 不可变拷贝：构造期复制，传入 map 后续改动不影响已声明的槽位")
    void metadataIsDefensivelyCopied() {
        java.util.HashMap<String, String> mutable = new java.util.HashMap<>();
        mutable.put("k", "v");
        WebUiSlotContribution contribution = new WebUiSlotContribution("demo", "demo.a", "anchor", null, 0, mutable);
        mutable.put("k2", "v2");
        assertThat(contribution.metadata()).containsExactlyEntriesOf(Map.of("k", "v"));
        assertThatThrownBy(() -> contribution.metadata().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("slots() 返回不可变快照，外部不可修改")
    void snapshotIsImmutable() {
        WebUiSlotRegistry registry = emptyRegistry();
        registry.register("demo", List.of(slot("demo", "demo.a")));
        List<WebUiSlotRegistry.RegisteredUiSlot> slots = registry.slots();
        assertThatThrownBy(() -> slots.add(
                new WebUiSlotRegistry.RegisteredUiSlot("x", slot("x", "x.a"))))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static final class FlakyIdSlotPlugin implements PixivFeaturePlugin {
        private final AtomicInteger idReads = new AtomicInteger();

        @Override
        public String id() {
            if (idReads.incrementAndGet() != 1) {
                throw new AssertionError("plugin id getter was read more than once");
            }
            return "flaky-slot-id";
        }

        @Override public String displayName() { return "flaky-slot-id.name"; }
        @Override public String description() { return "flaky-slot-id.summary"; }
        @Override public PluginKind kind() { return PluginKind.FEATURE; }
        @Override public List<WebUiSlotContribution> uiSlots() {
            return List.of(slot("flaky-slot-id", "flaky-slot-id.main"));
        }

        int idReads() {
            return idReads.get();
        }
    }
}
