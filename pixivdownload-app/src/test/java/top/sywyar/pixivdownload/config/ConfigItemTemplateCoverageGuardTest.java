package top.sywyar.pixivdownload.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import top.sywyar.pixivdownload.notification.NotificationConfigKeys;
import top.sywyar.pixivdownload.notification.NotificationScenario;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 配置项覆盖守卫：固化「每个配置项都要进 config.yaml 模板，并做 GUI 配套（或显式登记豁免）」这一约束，
 * 防止三份各写一遍的清单（{@link DefaultConfigTemplate} 模板、{@code @ConfigurationProperties} 配置类、
 * App-owned declarative GUI schema）相互漂移。
 *
 * <ul>
 *   <li><b>前缀覆盖</b>：扫描到的每个 {@code @ConfigurationProperties} 前缀，模板里至少要出现一个对应键，
 *       除非它是明确登记的开放式或插件自有配置前缀
 *       —— 漏掉整段宿主前缀（如曾经的 {@code plugin-catalog.*}）会被这条挡下。</li>
 *   <li><b>GUI → 模板</b>：每个 GUI 配置字段都必须对应模板里的真实可写键 —— GUI 有字段、模板却没有
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
     *   <li>{@code app.language} / {@code app.theme} / {@code app.config-menu-expand-all} ——
     *       由 GUI「界面」页的即时偏好控件管理，不进配置字段网格；</li>
     *   <li>{@code app.gui-provider} —— 桌面 UI 启动前完成提供者选择，只能通过配置文件修改并重启进程；</li>
     *   <li>{@code plugin-catalog.repositories} —— 自定义仓库<b>列表</b>型配置，由「插件」分组的仓库列表编辑器
     *       （{@code PluginMarketConfigSection}，经 {@code PluginRepositoryConfigEditor} 结构化读写）管理，不入字段网格；</li>
     *   <li>{@code multi-mode.*} —— 配置支持保留、但 GUI 不再提供设置页（配置页已移除该分组），
     *       配置项仍由 {@code MultiModeConfig} / {@code RuntimeConfigReloadService} 承载，仅在需要调整时手工编辑
     *       {@code config.yaml}。</li>
     * </ul>
     */
    private static final Set<String> TEMPLATE_KEYS_WITHOUT_GUI_FIELD = Set.of(
            "app.language", "app.theme", "app.config-menu-expand-all", "app.gui-provider",
            "plugin-catalog.repositories",
            "multi-mode.quota.enabled", "multi-mode.quota.max-artworks", "multi-mode.quota.reset-period-hours",
            "multi-mode.quota.archive-expire-minutes", "multi-mode.quota.limit-image",
            "multi-mode.quota.max-proxy-requests", "multi-mode.quota.archive-max-concurrent",
            "multi-mode.post-download-mode", "multi-mode.delete-after-hours",
            "multi-mode.request-limit-minute", "multi-mode.static-resource-request-limit-minute",
            "multi-mode.limit-page");

    /**
     * 有意不要求核心模板提供任何键的 {@code @ConfigurationProperties} 前缀：
     * <ul>
     *   <li>插件自有配置前缀由对应外置插件贡献，不能塞回核心模板；</li>
     *   <li>{@code plugins} 是按任意插件 id 展开的宿主启停表，缺项默认启用，只有显式管理动作才持久化具体键。</li>
     * </ul>
     */
    private static final Set<String> CONFIGURATION_PROPERTIES_PREFIXES_WITHOUT_TEMPLATE_KEYS = Set.of(
            "narration-tts", "notification", "plugins");

    @Test
    @DisplayName("每个 @ConfigurationProperties 前缀在 config.yaml 模板中至少有一个键")
    void everyConfigurationPropertiesPrefixHasTemplateKey() {
        Set<String> templateKeys = templateKeys();
        Set<String> prefixes = configurationPropertiesPrefixes();

        assertThat(prefixes).as("应扫描到 @ConfigurationProperties 前缀").isNotEmpty();
        Set<String> uncovered = prefixes.stream()
                .filter(prefix -> !CONFIGURATION_PROPERTIES_PREFIXES_WITHOUT_TEMPLATE_KEYS.contains(prefix))
                .filter(prefix -> templateKeys.stream()
                        .noneMatch(k -> k.equals(prefix) || k.startsWith(prefix + ".")))
                .collect(Collectors.toCollection(TreeSet::new));
        assertThat(uncovered)
                .as("以下 @ConfigurationProperties 前缀在 DefaultConfigTemplate 中无任何键"
                        + "（新增宿主配置项前缀必须进模板，开放式或插件自有前缀须显式登记）")
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
        templateMissingFromGui.removeAll(TEMPLATE_KEYS_WITHOUT_GUI_FIELD);
        assertThat(templateMissingFromGui)
                .as("以下模板配置键既无 GUI 字段、也不在显式豁免清单（新增配置项必须做 GUI 配套或登记豁免）")
                .isEmpty();
    }

    @Test
    @DisplayName("notification 场景开关不再写入核心默认模板")
    void notificationScenarioKeysAreExcludedFromCoreTemplate() {
        Set<String> notificationKeys = java.util.Arrays.stream(NotificationScenario.values())
                .map(NotificationScenario::id)
                .map(NotificationConfigKeys::scenarioEnabledKey)
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(templateKeys()).doesNotContainAnyElementsOf(notificationKeys);
    }

    @Test
    @DisplayName("核心默认模板不声明任何具体插件启停键")
    void pluginToggleKeysAreExcludedFromCoreTemplate() {
        assertThat(templateKeys()).noneMatch(ConfigItemTemplateCoverageGuardTest::isPluginToggleKey);
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

    private static boolean isPluginToggleKey(String key) {
        return key.startsWith("plugins.") && key.endsWith(".enabled");
    }

    /** App-owned Schema 中声明的全部核心配置字段键。 */
    private static Set<String> guiFieldKeys() {
        try {
            String source = Files.readString(Path.of(
                    "src/main/java/top/sywyar/pixivdownload/gui/DesktopCoreConfigCatalog.java"), StandardCharsets.UTF_8);
            Matcher matcher = Pattern.compile("core\\(\\s*\"([^\"]+)\"").matcher(source);
            Set<String> keys = new TreeSet<>();
            while (matcher.find()) keys.add(matcher.group(1));
            for (String day : Set.of("monday", "tuesday", "wednesday", "thursday",
                    "friday", "saturday", "sunday")) {
                keys.add("maintenance." + day + ".enabled");
                keys.add("maintenance." + day + ".time");
            }
            return keys;
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("无法读取桌面核心配置目录", failure);
        }
    }

    /** 扫描 {@value #BASE_PACKAGE} 下所有 {@code @ConfigurationProperties} 类，取其前缀（非空）。 */
    private static Set<String> configurationPropertiesPrefixes() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(ConfigurationProperties.class));
        Set<String> prefixes = new TreeSet<>();
        for (BeanDefinition def : scanner.findCandidateComponents(BASE_PACKAGE)) {
            String className = def.getBeanClassName();
            if (className == null || className.contains("Test$")) {
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
