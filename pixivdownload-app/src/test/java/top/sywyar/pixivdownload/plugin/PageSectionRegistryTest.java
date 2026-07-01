package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.web.AccessPolicy;
import top.sywyar.pixivdownload.plugin.api.web.PageSectionContribution;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import top.sywyar.pixivdownload.plugin.registry.PageSectionRegistry;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;

/**
 * {@code PageSectionRegistry} 的注册期不变量与「namespace + 纯 key」必填语义：区块 id 全局唯一、可逆注册、
 * 不可变快照，以及 {@code titleNamespace} 必填、{@code actionTitleNamespace} 随 {@code actionTitleI18nKey} 条件必填。
 *（{@code actionHref} / {@code moduleUrl} 的同源绝对路径安全边界由 {@code PageSectionControllerTest} 守护，此处不重复。）
 */
@DisplayName("PageSectionRegistry 页面区块注册中心")
class PageSectionRegistryTest {

    private static PageSectionRegistry emptyRegistry() {
        return new PageSectionRegistry(new PluginRegistry(List.of()));
    }

    /** 最小合法区块：pluginId=demo、titleNamespace=ns、无操作入口 / 内嵌导航 / 模块。 */
    private static PageSectionContribution section(String id) {
        return new PageSectionContribution("demo", id, "host.slot", "ns", "title",
                null, null, null, null, null, null, AccessPolicy.ADMIN, 10);
    }

    @Test
    @DisplayName("构造时从 PluginRegistry 收集内置区块（画廊向统计页贡献的视图 / 收藏夹两条，均归 gallery 插件）")
    void collectsSectionsFromBuiltInPlugins() {
        PageSectionRegistry registry = new PageSectionRegistry(new PluginRegistry(BuiltInPlugins.createAll()));
        assertThat(registry.sections())
                .extracting(registered -> registered.section().id())
                .containsExactlyInAnyOrder("gallery-stats-views", "gallery-stats-collections");
        assertThat(registry.sections())
                .allSatisfy(registered -> assertThat(registered.pluginId()).isEqualTo("gallery"));
    }

    @Test
    @DisplayName("register → unregister → 再 register 后快照与首次注册一致（可逆性）")
    void registerUnregisterRoundTrip() {
        PageSectionRegistry registry = emptyRegistry();
        List<PageSectionContribution> items = List.of(section("a"), section("b"));
        registry.register("demo", items);
        List<PageSectionRegistry.RegisteredSection> first = registry.sections();
        registry.unregister("demo");
        assertThat(registry.sections()).isEmpty();
        registry.register("demo", items);
        assertThat(registry.sections()).isEqualTo(first);
    }

    @Test
    @DisplayName("unregister 对未注册过区块的 pluginId 静默返回（统一卸载流程对每个插件都会调用）")
    void unregisterUnknownPluginIsSilent() {
        PageSectionRegistry registry = emptyRegistry();
        registry.unregister("never-registered");
        assertThat(registry.sections()).isEmpty();
    }

    @Test
    @DisplayName("同一 pluginId 重复注册立即抛出")
    void duplicatePluginRegistrationRejected() {
        PageSectionRegistry registry = emptyRegistry();
        registry.register("demo", List.of(section("a")));
        assertThatThrownBy(() -> registry.register("demo", List.of(section("b"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("demo");
    }

    @Test
    @DisplayName("区块 id 全局冲突立即抛出（跨插件不可重名）")
    void duplicateSectionIdAcrossPluginsRejected() {
        PageSectionRegistry registry = emptyRegistry();
        registry.register("a", List.of(new PageSectionContribution("a", "shared", "host.slot", "ns", "title",
                null, null, null, null, null, null, AccessPolicy.ADMIN, 10)));
        assertThatThrownBy(() -> registry.register("b", List.of(new PageSectionContribution("b", "shared", "host.slot",
                "ns", "title", null, null, null, null, null, null, AccessPolicy.ADMIN, 10))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("shared");
    }

    @Test
    @DisplayName("pluginId 与区块声明方不一致立即抛出（区块归属须与登记方一致）")
    void pluginIdMismatchRejected() {
        PageSectionRegistry registry = emptyRegistry();
        assertThatThrownBy(() -> registry.register("demo", List.of(new PageSectionContribution(
                "other", "a", "host.slot", "ns", "title", null, null, null, null, null, null, AccessPolicy.ADMIN, 10))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("titleNamespace 为 null / 空白立即抛出（纯 key 必须有确定 namespace 才能解析，必填语义）")
    void blankTitleNamespaceRejected() {
        PageSectionRegistry registry = emptyRegistry();
        assertThatThrownBy(() -> registry.register("demo", List.of(new PageSectionContribution(
                "demo", "a", "host.slot", null, "title", null, null, null, null, null, null, AccessPolicy.ADMIN, 10))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> registry.register("demo", List.of(new PageSectionContribution(
                "demo", "b", "host.slot", " ", "title", null, null, null, null, null, null, AccessPolicy.ADMIN, 10))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("actionTitleNamespace 随 actionTitleI18nKey 条件必填：有 key 缺 namespace 抛出；有 key 有 namespace 通过；无 key 缺 namespace 通过")
    void actionTitleNamespaceRequiredWhenKeyPresent() {
        PageSectionRegistry registry = emptyRegistry();
        // 有 actionTitleI18nKey 但缺 actionTitleNamespace → 抛（actionHref 合法、唯一不合法因素是缺 namespace）。
        assertThatThrownBy(() -> registry.register("demo", List.of(new PageSectionContribution(
                "demo", "a", "host.slot", "ns", "title", null, "/a.html", "plus", null, "act.key", null,
                AccessPolicy.ADMIN, 10)))).isInstanceOf(IllegalStateException.class);
        // 有 actionTitleI18nKey 且有 actionTitleNamespace → 通过。
        assertThatCode(() -> registry.register("demo", List.of(new PageSectionContribution(
                "demo", "b", "host.slot", "ns", "title", null, "/a.html", "plus", "ns2", "act.key", null,
                AccessPolicy.ADMIN, 10)))).doesNotThrowAnyException();
        // 无 actionTitleI18nKey 时 actionTitleNamespace 可为 null（无操作标题）→ 通过。
        assertThatCode(() -> emptyRegistry().register("demo", List.of(new PageSectionContribution(
                "demo", "c", "host.slot", "ns", "title", null, "/a.html", "plus", null, null, null,
                AccessPolicy.ADMIN, 10)))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("sections() 返回不可变快照，外部不可修改")
    void snapshotIsImmutable() {
        PageSectionRegistry registry = emptyRegistry();
        registry.register("demo", List.of(section("a")));
        List<PageSectionRegistry.RegisteredSection> snapshot = registry.sections();
        assertThatThrownBy(() -> snapshot.add(
                new PageSectionRegistry.RegisteredSection("x", section("x"))))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
