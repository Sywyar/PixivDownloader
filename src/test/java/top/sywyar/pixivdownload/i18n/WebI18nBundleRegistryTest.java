package top.sywyar.pixivdownload.i18n;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.BuiltInPlugins;
import top.sywyar.pixivdownload.plugin.PluginRegistry;
import top.sywyar.pixivdownload.plugin.api.web.I18nContribution;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("WebI18nBundleRegistry i18n bundle 注册中心")
class WebI18nBundleRegistryTest {

    private static final ClassLoader LOADER = WebI18nBundleRegistryTest.class.getClassLoader();

    /**
     * 退役前的静态 map 基线：namespace → baseName 共 19 条。合并后的 registry 必须逐条等价，
     * 保证「所有页面 i18n 行为不变」。新增 namespace 时同步本基线。
     */
    private static final Map<String, String> LEGACY_NAMESPACE_BASENAMES = legacyBaseNames();

    private static Map<String, String> legacyBaseNames() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("common", "i18n.web.common");
        map.put("setup", "i18n.web.setup");
        map.put("login", "i18n.web.login");
        map.put("intro", "i18n.web.intro");
        map.put("batch", "i18n.web.batch");
        map.put("gallery", "i18n.web.gallery");
        map.put("stats", "i18n.web.stats");
        map.put("duplicates", "i18n.web.duplicates");
        map.put("artwork", "i18n.web.artwork");
        map.put("showcase", "i18n.web.showcase");
        map.put("series", "i18n.web.series");
        map.put("novel", "i18n.web.novel");
        map.put("translate", "i18n.web.translate");
        map.put("narration", "i18n.web.narration");
        map.put("monitor", "i18n.web.monitor");
        map.put("userscript", "i18n.web.userscript");
        map.put("invite", "i18n.web.invite");
        map.put("tour", "i18n.web.tour");
        map.put("maintenance", "i18n.web.maintenance");
        return map;
    }

    private static WebI18nBundleRegistry emptyRegistry() {
        return new WebI18nBundleRegistry(new PluginRegistry(List.of()));
    }

    private static WebI18nBundleRegistry builtInRegistry() {
        return new WebI18nBundleRegistry(new PluginRegistry(BuiltInPlugins.createAll()));
    }

    private static I18nContribution ns(String namespace) {
        return new I18nContribution(namespace, "i18n.web." + namespace);
    }

    @Test
    @DisplayName("构造时从 PluginRegistry 合并全部内置插件 namespace，逐条且按序等价退役前的静态 map（19 条）")
    void mergedNamespacesMirrorLegacyStaticMap() {
        WebI18nBundleRegistry registry = builtInRegistry();

        // 顺序也必须一致：namespace 自带 order，跨插件合并后逐条按退役前静态 map 的顺序排列
        // （containsExactly 而非 InAnyOrder），故 /api/i18n/meta 暴露的 namespace 顺序不漂移
        assertThat(registry.supportedNamespaces())
                .containsExactlyElementsOf(LEGACY_NAMESPACE_BASENAMES.keySet());
        LEGACY_NAMESPACE_BASENAMES.forEach((namespace, baseName) ->
                assertThat(registry.resolve(namespace))
                        .as("namespace %s 应解析到 baseName %s", namespace, baseName)
                        .isNotNull()
                        .satisfies(bundle ->
                                assertThat(bundle.contribution().baseName()).isEqualTo(baseName)));
    }

    @Test
    @DisplayName("namespace 按归属原则落到声明方插件：核心/共享留 core，页面跟功能插件走")
    void namespacesOwnedByDeclaringPlugin() {
        WebI18nBundleRegistry registry = builtInRegistry();
        assertThat(ownerOf(registry, "common")).isEqualTo("core");
        assertThat(ownerOf(registry, "translate")).isEqualTo("core");
        assertThat(ownerOf(registry, "maintenance")).isEqualTo("core");
        assertThat(ownerOf(registry, "batch")).isEqualTo("download-workbench");
        assertThat(ownerOf(registry, "userscript")).isEqualTo("download-workbench");
        assertThat(ownerOf(registry, "gallery")).isEqualTo("gallery");
        assertThat(ownerOf(registry, "artwork")).isEqualTo("gallery");
        assertThat(ownerOf(registry, "novel")).isEqualTo("novel");
        assertThat(ownerOf(registry, "narration")).isEqualTo("novel");
        assertThat(ownerOf(registry, "stats")).isEqualTo("stats");
        assertThat(ownerOf(registry, "duplicates")).isEqualTo("duplicate");
    }

    @Test
    @DisplayName("resolve 携带声明方插件的 ClassLoader（bundle 解析用）")
    void resolveCarriesDeclaringClassLoader() {
        WebI18nBundleRegistry registry = builtInRegistry();
        WebI18nBundleRegistry.RegisteredBundle bundle = registry.resolve("gallery");
        assertThat(bundle).isNotNull();
        assertThat(bundle.classLoader())
                .isSameAs(top.sywyar.pixivdownload.gallery.GalleryPlugin.class.getClassLoader());
    }

    @Test
    @DisplayName("resolve 未注册的 namespace 返回 null")
    void resolveUnknownNamespaceReturnsNull() {
        assertThat(emptyRegistry().resolve("does-not-exist")).isNull();
    }

    @Test
    @DisplayName("register → unregister → 再 register 后快照与首次注册一致（可逆性）")
    void registerUnregisterRoundTrip() {
        WebI18nBundleRegistry registry = emptyRegistry();
        List<I18nContribution> items = List.of(ns("alpha"), ns("beta"));
        registry.register("demo", LOADER, items);
        List<WebI18nBundleRegistry.RegisteredBundle> first = registry.bundles();
        registry.unregister("demo");
        assertThat(registry.bundles()).isEmpty();
        registry.register("demo", LOADER, items);
        assertThat(registry.bundles()).isEqualTo(first);
    }

    @Test
    @DisplayName("unregister 对未注册过 namespace 的 pluginId 静默返回（统一卸载流程对每个插件都会调用）")
    void unregisterUnknownPluginIsSilent() {
        WebI18nBundleRegistry registry = emptyRegistry();
        registry.unregister("never-registered");
        assertThat(registry.bundles()).isEmpty();
    }

    @Test
    @DisplayName("同一 pluginId 重复注册立即抛出")
    void duplicatePluginRegistrationRejected() {
        WebI18nBundleRegistry registry = emptyRegistry();
        registry.register("demo", LOADER, List.of(ns("alpha")));
        assertThatThrownBy(() -> registry.register("demo", LOADER, List.of(ns("beta"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("demo");
    }

    @Test
    @DisplayName("namespace 全局冲突立即抛出（跨插件不可重名）")
    void duplicateNamespaceAcrossPluginsRejected() {
        WebI18nBundleRegistry registry = emptyRegistry();
        registry.register("a", LOADER, List.of(ns("shared")));
        assertThatThrownBy(() -> registry.register("b", LOADER, List.of(ns("shared"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("shared");
    }

    @Test
    @DisplayName("同一插件内 namespace 重复也立即抛出")
    void duplicateNamespaceWithinPluginRejected() {
        WebI18nBundleRegistry registry = emptyRegistry();
        assertThatThrownBy(() -> registry.register("demo", LOADER, List.of(ns("dup"), ns("dup"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dup");
    }

    @Test
    @DisplayName("非法输入拒绝：pluginId / namespace / baseName 非空，classLoader 非 null，contribution 列表非空")
    void invalidInputRejected() {
        WebI18nBundleRegistry registry = emptyRegistry();
        assertThatThrownBy(() -> registry.register(" ", LOADER, List.of(ns("a"))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> registry.register("demo", null, List.of(ns("a"))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> registry.register("demo", LOADER, List.of()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> registry.register("demo", LOADER,
                List.of(new I18nContribution(" ", "i18n.web.a"))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> registry.register("demo", LOADER,
                List.of(new I18nContribution("a", " "))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("bundles() 返回不可变快照，外部不可修改")
    void snapshotIsImmutable() {
        WebI18nBundleRegistry registry = emptyRegistry();
        registry.register("demo", LOADER, List.of(ns("alpha")));
        List<WebI18nBundleRegistry.RegisteredBundle> bundles = registry.bundles();
        assertThatThrownBy(() -> bundles.add(
                new WebI18nBundleRegistry.RegisteredBundle("x", ns("x"), LOADER)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("新增 namespace 无需改核心 registry：新插件 namespace 直接注册即可解析（扩展性）")
    void newNamespaceNeedsNoCoreChange() {
        WebI18nBundleRegistry registry = builtInRegistry();
        // 模拟未来新插件登记自己的 namespace，无需触碰 registry 实现或既有声明
        registry.register("future-plugin", LOADER,
                List.of(new I18nContribution("future", "i18n.web.future")));
        assertThat(registry.resolve("future"))
                .isNotNull()
                .satisfies(bundle -> {
                    assertThat(bundle.pluginId()).isEqualTo("future-plugin");
                    assertThat(bundle.contribution().baseName()).isEqualTo("i18n.web.future");
                });
        // 既有 namespace 不受影响
        assertThat(registry.supportedNamespaces())
                .containsAll(LEGACY_NAMESPACE_BASENAMES.keySet())
                .contains("future");
    }

    @Test
    @DisplayName("未声明 order 的未来插件 namespace 默认追加在全部 legacy namespace 之后（同默认 order 保持注册顺序）")
    void defaultOrderAppendsAfterLegacyNamespaces() {
        WebI18nBundleRegistry registry = builtInRegistry();
        // 二参构造器 → order 取 Integer.MAX_VALUE：未来插件无需认领序号即自然追加在末尾，
        // 多个同为默认 order 的 namespace 之间保持注册顺序
        registry.register("future-plugin", LOADER, List.of(
                new I18nContribution("future-a", "i18n.web.future-a"),
                new I18nContribution("future-b", "i18n.web.future-b")));

        List<String> expected = new ArrayList<>(LEGACY_NAMESPACE_BASENAMES.keySet());
        expected.add("future-a");
        expected.add("future-b");
        assertThat(registry.supportedNamespaces()).containsExactlyElementsOf(expected);
    }

    private static String ownerOf(WebI18nBundleRegistry registry, String namespace) {
        WebI18nBundleRegistry.RegisteredBundle bundle = registry.resolve(namespace);
        assertThat(bundle).as("namespace %s 应已注册", namespace).isNotNull();
        return bundle.pluginId();
    }
}
