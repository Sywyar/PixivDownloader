package top.sywyar.pixivdownload.i18n;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import top.sywyar.pixivdownload.plugin.BuiltInPlugins;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.PluginToggleProperties;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.api.web.I18nContribution;
import top.sywyar.pixivdownload.plugin.runtime.discovery.DiscoveredFeaturePlugin;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginDiscoveryResult;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("WebI18nBundleRegistry i18n bundle 注册中心")
class WebI18nBundleRegistryTest {

    private static final ClassLoader LOADER = syntheticBundleClassLoader("fixture");

    /**
     * namespace → baseName 基线：内置共 10 条（download-workbench/gallery/novel/stats/duplicate/translate 已外置、不计）。合并后的 registry 必须逐条且按序等价，
     * 保证「所有页面 i18n 行为不变」。新增 namespace 时同步本基线。
     */
    private static final Map<String, String> LEGACY_NAMESPACE_BASENAMES = legacyBaseNames();

    private static Map<String, String> legacyBaseNames() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("common", "i18n.web.common");
        map.put("setup", "i18n.web.setup");
        map.put("login", "i18n.web.login");
        map.put("intro", "i18n.web.intro");
        map.put("monitor", "i18n.web.monitor");
        map.put("invite", "i18n.web.invite");
        map.put("tour", "i18n.web.tour");
        map.put("maintenance", "i18n.web.maintenance");
        map.put("plugins", "i18n.web.plugins");
        map.put("plugin-market", "i18n.web.plugin-market");
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
    @DisplayName("构造时从 PluginRegistry 合并全部内置插件 namespace，逐条且按序等价基线 map（10 条；download-workbench/gallery/novel/stats/duplicate/translate 已外置）")
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
        assertThat(ownerOf(registry, "maintenance")).isEqualTo("core");
        assertThat(registry.resolve("gallery")).isNull();
        assertThat(registry.resolve("artwork")).isNull();
        assertThat(registry.resolve("novel")).isNull();
        assertThat(registry.resolve("narration")).isNull();
    }

    @Test
    @DisplayName("resolve 只暴露所有受支持语言的不可变消息映射，不携带 ClassLoader")
    void resolveCarriesMaterializedLocaleMapsOnly() {
        WebI18nBundleRegistry registry = builtInRegistry();
        WebI18nBundleRegistry.RegisteredBundle bundle = registry.resolve("common");
        assertThat(bundle).isNotNull();
        assertThat(bundle.messagesByLocale()).containsOnlyKeys(
                AppLocale.SUPPORTED_LOCALES.stream().map(Locale::toLanguageTag).toArray(String[]::new));
        assertThat(bundle.load(Locale.US)).isNotEmpty();
        assertThat(bundle.load(Locale.SIMPLIFIED_CHINESE)).isNotEmpty();
        assertThat(Arrays.stream(WebI18nBundleRegistry.RegisteredBundle.class.getRecordComponents())
                .map(component -> component.getType()))
                .allMatch(type -> type != ClassLoader.class);
        assertThatThrownBy(() -> bundle.messagesByLocale().put("x", Map.of()))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> bundle.load(Locale.US).put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
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
        assertThatThrownBy(() -> bundles.add(bundles.get(0)))
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
    @DisplayName("translate namespace 可由外置 AI 插件注册，不属于内置快照")
    void translateNamespaceCanBeProvidedByExternalAiPlugin() {
        WebI18nBundleRegistry registry = builtInRegistry();
        assertThat(registry.resolve("translate")).isNull();

        registry.register("ai", LOADER,
                List.of(new I18nContribution("translate", "i18n.web.translate", 13)));

        assertThat(ownerOf(registry, "translate")).isEqualTo("ai");
        assertThat(registry.resolve("translate").contribution().baseName()).isEqualTo("i18n.web.translate");
    }

    @Test
    @DisplayName("batch/userscript namespace 可由外置 download-workbench 插件注册，不属于内置快照")
    void downloadWorkbenchNamespacesCanBeProvidedByExternalPlugin() {
        WebI18nBundleRegistry registry = builtInRegistry();
        assertThat(registry.resolve("batch")).isNull();
        assertThat(registry.resolve("userscript")).isNull();

        registry.register("download-workbench", LOADER, List.of(
                new I18nContribution("batch", "i18n.web.batch", 5),
                new I18nContribution("userscript", "i18n.web.userscript", 16)));

        assertThat(ownerOf(registry, "batch")).isEqualTo("download-workbench");
        assertThat(ownerOf(registry, "userscript")).isEqualTo("download-workbench");
    }

    @Test
    @DisplayName("duplicates namespace 可由外置 duplicate 插件注册，不属于内置快照")
    void duplicateNamespaceCanBeProvidedByExternalPlugin() {
        WebI18nBundleRegistry registry = builtInRegistry();
        assertThat(registry.resolve("duplicates")).isNull();

        registry.register("duplicate", LOADER,
                List.of(new I18nContribution("duplicates", "i18n.web.duplicates")));

        assertThat(registry.resolve("duplicates"))
                .isNotNull()
                .satisfies(bundle -> {
                    assertThat(bundle.pluginId()).isEqualTo("duplicate");
                    assertThat(bundle.contribution().baseName()).isEqualTo("i18n.web.duplicates");
                });
    }

    @Test
    @DisplayName("gallery/artwork/showcase/series namespace 可由外置 gallery 插件注册，不属于内置快照")
    void galleryNamespacesCanBeProvidedByExternalPlugin() {
        WebI18nBundleRegistry registry = builtInRegistry();
        assertThat(registry.resolve("gallery")).isNull();
        assertThat(registry.resolve("artwork")).isNull();
        assertThat(registry.resolve("showcase")).isNull();
        assertThat(registry.resolve("series")).isNull();

        registry.register("gallery", LOADER, List.of(
                new I18nContribution("gallery", "i18n.web.gallery", 6),
                new I18nContribution("artwork", "i18n.web.artwork", 9),
                new I18nContribution("showcase", "i18n.web.showcase", 10),
                new I18nContribution("series", "i18n.web.series", 11)));

        assertThat(ownerOf(registry, "gallery")).isEqualTo("gallery");
        assertThat(ownerOf(registry, "artwork")).isEqualTo("gallery");
        assertThat(ownerOf(registry, "showcase")).isEqualTo("gallery");
        assertThat(ownerOf(registry, "series")).isEqualTo("gallery");
    }

    @Test
    @DisplayName("novel / narration / novel-gallery namespace 可由外置 novel 插件注册，不属于内置快照")
    void novelNamespacesCanBeProvidedByExternalPlugin() {
        WebI18nBundleRegistry registry = builtInRegistry();
        assertThat(registry.resolve("novel")).isNull();
        assertThat(registry.resolve("narration")).isNull();
        assertThat(registry.resolve("novel-gallery")).isNull();

        registry.register("novel", LOADER, List.of(
                new I18nContribution("novel", "i18n.web.novel", 12),
                new I18nContribution("novel-gallery", "i18n.web.novel-gallery", 12),
                new I18nContribution("narration", "i18n.web.narration", 14)));

        assertThat(ownerOf(registry, "novel")).isEqualTo("novel");
        assertThat(ownerOf(registry, "narration")).isEqualTo("novel");
        assertThat(ownerOf(registry, "novel-gallery")).isEqualTo("novel");
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

    @Test
    @DisplayName("外置插件只在注册时用桥接 ClassLoader 物化消息，发布快照不保留 loader")
    void externalI18nIsMaterializedThroughBridgeClassLoader() {
        // 桥接捕获的插件 classloader（真实场景为 PF4J 插件 classloader），与插件实例 class 的 loader 不同
        ClassLoader bridgeClassLoader = syntheticBundleClassLoader("bridge");
        ExternalI18nPlugin external = new ExternalI18nPlugin();
        // 前置：本测试只有在「插件对象 class 的 loader != 桥接 classloader」时才有意义
        assertThat(external.getClass().getClassLoader()).isNotSameAs(bridgeClassLoader);

        PluginDiscoveryResult discovery = new PluginDiscoveryResult(
                List.of(new DiscoveredFeaturePlugin("ext-i18n", external, bridgeClassLoader)), List.of());
        PluginRegistry registry = new PluginRegistry(
                List.of(new CorePlaceholderPlugin()), new PluginToggleProperties(), discoveryProvider(discovery));
        WebI18nBundleRegistry i18n = new WebI18nBundleRegistry(registry);

        WebI18nBundleRegistry.RegisteredBundle bundle = i18n.resolve("ext-i18n");
        assertThat(bundle).isNotNull();
        assertThat(bundle.load(Locale.US))
                .containsEntry("fixture.loader", "bridge:i18n/web/ext-i18n_en_US.properties");
        assertThat(bundle.messagesByLocale()).containsOnlyKeys("en-US", "zh-CN");
    }

    @Test
    @DisplayName("locale 专属资源覆盖根资源，缺少专属资源时仍回退根 bundle 且不回退 JVM 默认语言")
    void localeFallbackMatchesExistingNoDefaultFallbackSemantics() {
        ClassLoader loader = resourceClassLoader(Map.of(
                "i18n/web/fallback.properties", "value=Root\nroot.only=kept\n",
                "i18n/web/fallback_en.properties", "value=English\n"));
        WebI18nBundleRegistry registry = emptyRegistry();
        registry.register("fallback", loader,
                List.of(new I18nContribution("fallback", "i18n.web.fallback")));

        assertThat(registry.resolve("fallback").load(Locale.US))
                .containsEntry("value", "English")
                .containsEntry("root.only", "kept");
        assertThat(registry.resolve("fallback").load(Locale.SIMPLIFIED_CHINESE))
                .containsEntry("value", "Root")
                .containsEntry("root.only", "kept");
    }

    @Test
    @DisplayName("资源完全缺失时保留既有延迟失败语义，读取受支持 locale 才抛 MissingResourceException")
    void missingBundleKeepsExistingLoadFailureSemantics() {
        WebI18nBundleRegistry registry = emptyRegistry();
        registry.register("missing", resourceClassLoader(Map.of()),
                List.of(new I18nContribution("missing", "i18n.web.missing")));

        assertThat(registry.resolve("missing").messagesByLocale()).isEmpty();
        assertThatThrownBy(() -> registry.resolve("missing").load(Locale.US))
                .isInstanceOf(MissingResourceException.class)
                .hasMessageContaining("i18n.web.missing");
    }

    @Test
    @DisplayName("同一注册批次任一声明非法时整批不发布")
    void registrationBatchIsAtomic() {
        WebI18nBundleRegistry registry = emptyRegistry();
        List<I18nContribution> contributions = Arrays.asList(ns("valid"), null);

        assertThatThrownBy(() -> registry.register("atomic", LOADER, contributions))
                .isInstanceOf(IllegalStateException.class);
        assertThat(registry.resolve("valid")).isNull();
        assertThat(registry.bundles()).isEmpty();
    }

    private static ClassLoader syntheticBundleClassLoader(String marker) {
        return new ClassLoader(WebI18nBundleRegistryTest.class.getClassLoader()) {
            @Override
            public InputStream getResourceAsStream(String name) {
                InputStream inherited = super.getResourceAsStream(name);
                if (inherited != null) {
                    return inherited;
                }
                if (name.startsWith("i18n/web/") && name.endsWith(".properties")) {
                    return utf8("fixture.loader=" + marker + ":" + name + "\n");
                }
                return null;
            }
        };
    }

    private static ClassLoader resourceClassLoader(Map<String, String> resources) {
        return new ClassLoader(null) {
            @Override
            public InputStream getResourceAsStream(String name) {
                String content = resources.get(name);
                return content == null ? null : utf8(content);
            }
        };
    }

    private static InputStream utf8(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String ownerOf(WebI18nBundleRegistry registry, String namespace) {
        WebI18nBundleRegistry.RegisteredBundle bundle = registry.resolve(namespace);
        assertThat(bundle).as("namespace %s 应已注册", namespace).isNotNull();
        return bundle.pluginId();
    }

    /** 把单个发现结果包装为 {@link ObjectProvider}，驱动 {@link PluginRegistry} 的 Spring 构造器（i18n 包不可见包内构造器）。 */
    private static ObjectProvider<PluginDiscoveryResult> discoveryProvider(PluginDiscoveryResult result) {
        return new ObjectProvider<>() {
            @Override
            public PluginDiscoveryResult getObject() {
                return result;
            }
        };
    }

    /** 最小核心插件占位（无 i18n），仅作对照。 */
    private static final class CorePlaceholderPlugin implements PixivFeaturePlugin {
        @Override public String id() { return "core"; }
        @Override public String displayName() { return "core.label"; }
        @Override public String description() { return "core.summary"; }
        @Override public PluginKind kind() { return PluginKind.CORE; }
    }

    /** 外置功能插件：声明一个 i18n namespace，用于验证注册中心采用桥接 classloader。 */
    private static final class ExternalI18nPlugin implements PixivFeaturePlugin {
        @Override public String id() { return "ext-i18n"; }
        @Override public String displayName() { return "ext-i18n.label"; }
        @Override public String description() { return "ext-i18n.summary"; }
        @Override public PluginKind kind() { return PluginKind.FEATURE; }
        @Override public List<I18nContribution> i18n() {
            return List.of(new I18nContribution("ext-i18n", "i18n.web.ext-i18n"));
        }
    }
}
