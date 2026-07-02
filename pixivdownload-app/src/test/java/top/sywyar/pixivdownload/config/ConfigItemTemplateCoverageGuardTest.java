package top.sywyar.pixivdownload.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import top.sywyar.pixivdownload.gui.config.ConfigFieldRegistry;
import top.sywyar.pixivdownload.gui.config.ConfigFieldSpec;
import top.sywyar.pixivdownload.notification.NotificationConfigKeys;
import top.sywyar.pixivdownload.notification.NotificationScenario;
import top.sywyar.pixivdownload.plugin.BuiltInPlugins;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 配置项覆盖守卫：固化「每个配置项都要进 config.yaml 模板，并做 GUI 配套（或显式登记豁免）」这一约束，
 * 防止三份各写一遍的清单（{@link DefaultConfigTemplate} 模板、{@code @ConfigurationProperties} 配置类、
 * GUI {@link ConfigFieldRegistry}）相互漂移。
 *
 * <ul>
 *   <li><b>前缀覆盖</b>：扫描到的每个 {@code @ConfigurationProperties} 前缀，模板里至少要出现一个对应键
 *       —— 漏掉整段前缀（如曾经的 {@code plugin-catalog.*}）会被这条挡下。</li>
 *   <li><b>GUI → 模板</b>：每个 GUI 配置字段都必须对应模板里的真实可写键 —— GUI 有字段、模板却没有
 *       （如曾经的 {@code download.novel-translate-max-concurrent} / {@code plugins.plugin-market.enabled}）
 *       会被挡下。</li>
 *   <li><b>模板 → GUI</b>：每个模板键都要有 GUI 字段，除非登记在 {@link #TEMPLATE_KEYS_WITHOUT_GUI_FIELD}。</li>
 * </ul>
 *
 * <p>新增配置项时：补模板键 + 补 GUI 字段（含 i18n）即可让三条用例通过；确属不需要 GUI 的，把键登记进豁免清单
 * 并在此说明理由。
 */
@DisplayName("配置项覆盖守卫：模板 / GUI / @ConfigurationProperties 三方一致")
class ConfigItemTemplateCoverageGuardTest {

    private static final String BASE_PACKAGE = "top.sywyar.pixivdownload";

    /**
     * 模板有意<b>不</b>提供 GUI 字段网格项的配置键（显式豁免；新增豁免须在此登记并说明理由）：
     * <ul>
     *   <li>{@code app.language} / {@code app.theme} —— 由 GUI 自带的语言 / 主题切换器管理，不进配置字段网格；</li>
     *   <li>{@code plugin-catalog.repositories} —— 自定义仓库<b>列表</b>型配置，由「插件」分组的仓库列表编辑器
     *       （{@code PluginMarketConfigSection}，经 {@code PluginRepositoryConfigEditor} 结构化读写）管理，不入字段网格。</li>
     * </ul>
     */
    private static final Set<String> TEMPLATE_KEYS_WITHOUT_GUI_FIELD = Set.of(
            "app.language", "app.theme", "plugin-catalog.repositories");

    /**
     * 插件启停键仍写入模板并由启动 / Web 插件前端消费，但桌面 GUI 配置页不呈现这些开关。
     */
    private static final Set<String> OFFICIAL_EXTERNAL_PLUGIN_TOGGLE_KEYS = Set.of(
            "plugins.stats.enabled", "plugins.gui-theme.enabled");

    /**
     * App 侧仅保留调用门面 / 运行期选择状态，模板与 GUI 字段由外置官方插件贡献的前缀。
     * 这些前缀不能重新塞回核心默认模板，否则插件缺失 / 禁用时 GUI 字段无法自然消失。
     */
    private static final Set<String> EXTERNAL_PLUGIN_OWNED_PREFIXES = Set.of("narration-tts");

    @Test
    @DisplayName("每个 @ConfigurationProperties 前缀在 config.yaml 模板中至少有一个键")
    void everyConfigurationPropertiesPrefixHasTemplateKey() {
        Set<String> templateKeys = templateKeys();
        Set<String> prefixes = configurationPropertiesPrefixes();

        assertThat(prefixes).as("应扫描到 @ConfigurationProperties 前缀").isNotEmpty();
        Set<String> uncovered = prefixes.stream()
                .filter(prefix -> !EXTERNAL_PLUGIN_OWNED_PREFIXES.contains(prefix))
                .filter(prefix -> templateKeys.stream()
                        .noneMatch(k -> k.equals(prefix) || k.startsWith(prefix + ".")))
                .collect(Collectors.toCollection(TreeSet::new));
        assertThat(uncovered)
                .as("以下 @ConfigurationProperties 前缀在 DefaultConfigTemplate 中无任何键"
                        + "（新增配置项前缀必须进模板）")
                .isEmpty();
    }

    @Test
    @DisplayName("GUI 配置字段都对应模板中的真实配置键")
    void everyGuiFieldMapsToTemplateKey() {
        Set<String> templateKeys = templateKeys();
        Set<String> guiMissingFromTemplate = new TreeSet<>(guiFieldKeys());
        guiMissingFromTemplate.removeAll(templateKeys);
        assertThat(guiMissingFromTemplate)
                .as("以下 GUI 配置字段在 config.yaml 模板中无对应键（GUI 有字段就必须在模板可写）")
                .isEmpty();
    }

    @Test
    @DisplayName("模板配置键都有 GUI 字段（显式豁免除外）")
    void everyTemplateKeyHasGuiFieldOrIsExempt() {
        Set<String> templateMissingFromGui = new TreeSet<>(templateKeys());
        templateMissingFromGui.removeAll(guiFieldKeys());
        templateMissingFromGui.removeAll(templateKeysWithoutGuiField());
        assertThat(templateMissingFromGui)
                .as("以下模板配置键既无 GUI 字段、也不在显式豁免清单（新增配置项必须做 GUI 配套或登记豁免）")
                .isEmpty();
    }

    // ---- helpers --------------------------------------------------------------

    /** 解析默认模板文本，提取全部配置键（忽略空行 / 注释行，取每行 {@code :} 之前的部分）。 */
    private static Set<String> templateKeys() {
        Set<String> keys = new LinkedHashSet<>();
        for (String line : DefaultConfigTemplate.build(code -> code).split("\n")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int idx = trimmed.indexOf(':');
            if (idx > 0) {
                keys.add(trimmed.substring(0, idx).trim());
            }
        }
        return keys;
    }

    private static Set<String> templateKeysWithoutGuiField() {
        Set<String> keys = new TreeSet<>(TEMPLATE_KEYS_WITHOUT_GUI_FIELD);
        keys.addAll(pluginToggleKeysWithoutGuiField());
        keys.addAll(notificationScenarioKeysWithoutCoreGuiField());
        return keys;
    }

    private static Set<String> notificationScenarioKeysWithoutCoreGuiField() {
        return java.util.Arrays.stream(NotificationScenario.values())
                .map(NotificationScenario::id)
                .map(NotificationConfigKeys::scenarioEnabledKey)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<String> pluginToggleKeysWithoutGuiField() {
        Set<String> keys = BuiltInPlugins.createAll().stream()
                .filter(plugin -> plugin.kind() == PluginKind.FEATURE && !plugin.required())
                .map(plugin -> "plugins." + plugin.id() + ".enabled")
                .collect(Collectors.toCollection(TreeSet::new));
        keys.addAll(OFFICIAL_EXTERNAL_PLUGIN_TOGGLE_KEYS);
        return keys;
    }

    /** GUI 配置面板的全部字段键。 */
    private static Set<String> guiFieldKeys() {
        return ConfigFieldRegistry.allFields().stream()
                .map(ConfigFieldSpec::key)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    /** 扫描 {@value #BASE_PACKAGE} 下所有 {@code @ConfigurationProperties} 类，取其前缀（非空）。 */
    private static Set<String> configurationPropertiesPrefixes() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(ConfigurationProperties.class));
        Set<String> prefixes = new TreeSet<>();
        for (BeanDefinition def : scanner.findCandidateComponents(BASE_PACKAGE)) {
            String className = def.getBeanClassName();
            if (className == null) {
                continue;
            }
            try {
                Class<?> clazz = Class.forName(className);
                ConfigurationProperties cp = AnnotationUtils.findAnnotation(clazz, ConfigurationProperties.class);
                if (cp == null) {
                    continue;
                }
                String prefix = cp.prefix().isEmpty() ? cp.value() : cp.prefix();
                if (!prefix.isEmpty()) {
                    prefixes.add(prefix);
                }
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("无法加载 @ConfigurationProperties 类: " + className, e);
            }
        }
        return prefixes;
    }
}
