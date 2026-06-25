package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.web.QueueTypeContribution;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("QueueTypeRegistry 作品类型注册中心")
class QueueTypeRegistryTest {

    private static QueueTypeRegistry emptyRegistry() {
        return new QueueTypeRegistry(new PluginRegistry(List.of()));
    }

    private static QueueTypeContribution type(String type) {
        return new QueueTypeContribution("demo", type, "demo", "label." + type, 10, null);
    }

    @Test
    @DisplayName("构造时从 PluginRegistry 收集全部内置作品类型（下载工作台 illust + 小说 novel）")
    void collectsQueueTypesFromBuiltInPlugins() {
        QueueTypeRegistry registry = new QueueTypeRegistry(new PluginRegistry(BuiltInPlugins.createAll()));
        assertThat(registry.queueTypes())
                .extracting(registered -> registered.queueType().type())
                .containsExactlyInAnyOrder("illust", "novel");
        assertThat(registry.queueTypes())
                .filteredOn(registered -> registered.queueType().type().equals("novel"))
                .singleElement()
                .satisfies(registered -> {
                    assertThat(registered.pluginId()).isEqualTo("novel");
                    assertThat(registered.queueType().moduleUrl())
                            .isEqualTo("/pixiv-novel-download/novel-queue-type.js");
                });
        assertThat(registry.queueTypes())
                .filteredOn(registered -> registered.queueType().type().equals("illust"))
                .singleElement()
                .satisfies(registered -> {
                    assertThat(registered.pluginId()).isEqualTo("download-workbench");
                    assertThat(registered.queueType().moduleUrl()).isNull();
                });
    }

    @Test
    @DisplayName("register → unregister → 再 register 后快照与首次注册一致（可逆性）")
    void registerUnregisterRoundTrip() {
        QueueTypeRegistry registry = emptyRegistry();
        List<QueueTypeContribution> items = List.of(type("a"), type("b"));
        registry.register("demo", items);
        List<QueueTypeRegistry.RegisteredQueueType> first = registry.queueTypes();
        registry.unregister("demo");
        assertThat(registry.queueTypes()).isEmpty();
        registry.register("demo", items);
        assertThat(registry.queueTypes()).isEqualTo(first);
    }

    @Test
    @DisplayName("unregister 对未注册过类型的 pluginId 静默返回（统一卸载流程对每个插件都会调用）")
    void unregisterUnknownPluginIsSilent() {
        QueueTypeRegistry registry = emptyRegistry();
        registry.unregister("never-registered");
        assertThat(registry.queueTypes()).isEmpty();
    }

    @Test
    @DisplayName("同一 pluginId 重复注册立即抛出")
    void duplicatePluginRegistrationRejected() {
        QueueTypeRegistry registry = emptyRegistry();
        registry.register("demo", List.of(type("a")));
        assertThatThrownBy(() -> registry.register("demo", List.of(type("b"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("demo");
    }

    @Test
    @DisplayName("作品类型 id 全局冲突立即抛出（同一类型只能由一个插件声明）")
    void duplicateTypeAcrossPluginsRejected() {
        QueueTypeRegistry registry = emptyRegistry();
        registry.register("a", List.of(type("shared")));
        assertThatThrownBy(() -> registry.register("b", List.of(type("shared"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("shared");
    }

    @Test
    @DisplayName("同一插件内作品类型 id 重复也立即抛出")
    void duplicateTypeWithinPluginRejected() {
        QueueTypeRegistry registry = emptyRegistry();
        assertThatThrownBy(() -> registry.register("demo", List.of(type("dup"), type("dup"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dup");
    }

    @Test
    @DisplayName("非法输入拒绝：pluginId / type / labelI18nKey 非空，类型列表非空")
    void invalidInputRejected() {
        QueueTypeRegistry registry = emptyRegistry();
        assertThatThrownBy(() -> registry.register(" ", List.of(type("a"))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> registry.register("demo", List.of()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> registry.register("demo", List.of(
                new QueueTypeContribution("demo", " ", "ns", "label", 0, null))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> registry.register("demo", List.of(
                new QueueTypeContribution("demo", "a", "ns", " ", 0, null))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("labelNamespace 为 null / 空白立即抛出（纯 key 必须有确定 namespace 才能解析，必填语义）")
    void blankLabelNamespaceRejected() {
        QueueTypeRegistry registry = emptyRegistry();
        assertThatThrownBy(() -> registry.register("demo", List.of(
                new QueueTypeContribution("demo", "a", null, "label", 0, null))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> registry.register("demo", List.of(
                new QueueTypeContribution("demo", "b", " ", "label", 0, null))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("queueTypes() 返回不可变快照，外部不可修改")
    void snapshotIsImmutable() {
        QueueTypeRegistry registry = emptyRegistry();
        registry.register("demo", List.of(type("a")));
        List<QueueTypeRegistry.RegisteredQueueType> snapshot = registry.queueTypes();
        assertThatThrownBy(() -> snapshot.add(
                new QueueTypeRegistry.RegisteredQueueType("x", type("x"))))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
