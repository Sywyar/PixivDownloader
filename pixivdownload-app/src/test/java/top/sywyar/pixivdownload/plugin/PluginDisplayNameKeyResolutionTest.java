package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.web.I18nContribution;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.stream.Stream;

import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 内置插件展示名 / 简介 i18n 守卫：{@link PixivFeaturePlugin#displayName()} / {@link PixivFeaturePlugin#description()}
 * 现为<b>纯 key</b>，其所在 namespace 由 {@link PixivFeaturePlugin#displayNamespace()} 提供（与导航
 * {@code NavigationContribution} 的「namespace 与 key 分离」模型一致），由 Web 插件管理页等消费端解析。本守卫钉死
 * 「每个内置插件的展示名 / 简介都是纯 key、displayNamespace 由某内置插件声明、其 bundle 在中英两种 locale 都含该 key」
 * ——故管理页对每个内置插件都能解析出本地化名称、不会落回裸 key 或插件 id。
 * <p>这补足 {@code ConfigFieldRegistryTest}（只覆盖 GUI 配置页呈现的可禁用功能插件）未覆盖的必选 / 核心插件
 * （core / download-workbench / schedule）。displayNamespace 由谁声明不限于插件自身（如 core / schedule 借用核心
 * {@code plugins} namespace），与管理页「按响应里出现的 namespace 统一加载」的解析模型一致。
 */
@DisplayName("内置插件 displayName/description 为纯 key + displayNamespace 且中英可解析")
class PluginDisplayNameKeyResolutionTest {

    /** 与 WebI18nService 同款：禁用「回退到 JVM 默认 locale」，请求 locale 找不到时落到根 bundle。 */
    private static final ResourceBundle.Control NO_FALLBACK =
            ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES);

    /** 合并全部内置插件声明的 namespace → baseName（管理页据响应里出现的 namespace 统一加载，归属不限插件自身）。 */
    private static Map<String, String> mergedBaseNames() {
        Map<String, String> map = new HashMap<>();
        for (PixivFeaturePlugin plugin : BuiltInPlugins.createAll()) {
            for (I18nContribution contribution : plugin.i18n()) {
                map.put(contribution.namespace(), contribution.baseName());
            }
        }
        return map;
    }

    static Stream<Arguments> displayKeys() {
        List<Arguments> args = new ArrayList<>();
        for (PixivFeaturePlugin plugin : BuiltInPlugins.createAll()) {
            args.add(arguments(plugin.id(), "displayName", plugin.displayNamespace(), plugin.displayName()));
            args.add(arguments(plugin.id(), "description", plugin.displayNamespace(), plugin.description()));
        }
        return args.stream();
    }

    @ParameterizedTest(name = "{0}.{1} = {2}:{3}")
    @MethodSource("displayKeys")
    @DisplayName("展示名 / 简介为纯 key（不含 namespace），且在 displayNamespace 指定的 namespace、中英两 locale 都能解析出文案")
    void displayKeysArePureAndResolvable(String pluginId, String which, String namespace, String key) {
        assertThat(key)
                .as("%s 的 %s 应为纯 key（不带 namespace、无 \":\"）", pluginId, which)
                .doesNotContain(":");
        assertThat(namespace)
                .as("%s 的 %s 须有 displayNamespace（非空）", pluginId, which)
                .isNotBlank();

        Map<String, String> baseNames = mergedBaseNames();
        assertThat(baseNames)
                .as("%s 的 displayNamespace \"%s\" 必须由某内置插件声明", pluginId, namespace)
                .containsKey(namespace);
        String baseName = baseNames.get(namespace);

        for (Locale locale : List.of(Locale.SIMPLIFIED_CHINESE, Locale.ENGLISH)) {
            ResourceBundle bundle = ResourceBundle.getBundle(
                    baseName, locale, getClass().getClassLoader(), NO_FALLBACK);
            assertThat(bundle.containsKey(key))
                    .as("%s 的 %s key \"%s\" 应在 %s 解析出文案（namespace %s）", pluginId, which, key, locale, namespace)
                    .isTrue();
        }
    }
}
