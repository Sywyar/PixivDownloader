package top.sywyar.pixivdownload.gui.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.gui.i18n.GuiMessages;
import top.sywyar.pixivdownload.plugin.BuiltInPlugins;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 配置面板字段注册守卫：可禁用插件开关（{@code plugins.<id>.enabled}）作为「插件」分组的 BOOL 字段呈现，
 * 名称 / 简介经插件声明的 i18n key（{@code displayName()} / {@code description()}）在<b>插件自有 namespace</b>
 * 中解析（文案归插件所有、不在核心 GUI bundle）、需重启；官方外置插件使用核心 GUI 文案兜底；
 * 必选插件（下载工作台）不可禁用、不呈现开关。
 */
@DisplayName("配置面板字段注册：内置插件开关")
class ConfigFieldRegistryTest {

    @Test
    @DisplayName("插件分组出现在配置分组列表（下载之后、代理之前），i18n 已解析")
    void pluginsGroupListedAfterDownload() {
        List<String> groups = ConfigFieldRegistry.groups();
        String plugins = GuiMessages.get("gui.config.group.plugins");
        String download = GuiMessages.get("gui.config.group.download");
        String proxy = GuiMessages.get("gui.config.group.proxy");

        assertThat(plugins).as("分组名 i18n 已解析（非原始 key）").isNotEqualTo("gui.config.group.plugins");
        assertThat(groups).contains(plugins);
        assertThat(groups.indexOf(download)).isLessThan(groups.indexOf(plugins));
        assertThat(groups.indexOf(plugins)).isLessThan(groups.indexOf(proxy));
    }

    @Test
    @DisplayName("可禁用功能插件各有一个 BOOL 开关：默认启用、需重启，名称/简介解析自插件自有 namespace")
    void pluginToggleFieldsRegistered() {
        // 名称 / 简介为纯 key（如 nav.label / plugin.label），在插件 displayNamespace 指定的 namespace 中解析（中文 locale 下的预期值）。
        Map<String, String> expectedNames = Map.of(
                "gallery", "画廊",
                "novel", "小说",
                "duplicate", "重复检测");
        Locale previousDefault = Locale.getDefault();
        try {
            // 锁定中文 locale，断言名称 / 简介解析出本地化文案（而非落到「原始 key」），且来源是插件自有 namespace。
            Locale.setDefault(Locale.SIMPLIFIED_CHINESE);
            GuiMessages.setLocale(Locale.SIMPLIFIED_CHINESE);

            String pluginsGroup = GuiMessages.get("gui.config.group.plugins");
            Map<String, ConfigFieldSpec> byKey = ConfigFieldRegistry.allFields().stream()
                    .collect(Collectors.toMap(ConfigFieldSpec::key, spec -> spec, (a, b) -> a));
            Map<String, PixivFeaturePlugin> plugins = BuiltInPlugins.createAll().stream()
                    .collect(Collectors.toMap(PixivFeaturePlugin::id, p -> p, (a, b) -> a));

            for (String id : List.of("gallery", "novel", "duplicate")) {
                PixivFeaturePlugin plugin = plugins.get(id);
                String key = "plugins." + id + ".enabled";
                ConfigFieldSpec spec = byKey.get(key);
                assertThat(spec).as("字段 %s 应已注册", key).isNotNull();
                assertThat(spec.type()).isEqualTo(FieldType.BOOL);
                assertThat(spec.group()).isEqualTo(pluginsGroup);
                assertThat(spec.defaultValue()).isEqualTo("true");
                assertThat(spec.requiresRestart()).as("插件开关需重启").isTrue();
                // 标签 = 插件 displayNamespace 中纯 key 的解析值，且不是落回的「原始 key」。
                assertThat(spec.label())
                        .as("名称解析自插件自有 namespace 的 %s", plugin.displayName())
                        .isEqualTo(expectedNames.get(id))
                        .isNotEqualTo(plugin.displayName());
                // 简介已解析（非空、非「原始 key」）。
                assertThat(spec.helpText())
                        .as("简介解析自插件自有 namespace 的 %s", plugin.description())
                        .isNotBlank()
                        .isNotEqualTo(plugin.description());
            }
            for (String id : List.of("stats", "gui-theme")) {
                String key = "plugins." + id + ".enabled";
                ConfigFieldSpec spec = byKey.get(key);
                assertThat(spec).as("官方外置插件字段 %s 应已注册", key).isNotNull();
                assertThat(spec.type()).isEqualTo(FieldType.BOOL);
                assertThat(spec.group()).isEqualTo(pluginsGroup);
                assertThat(spec.defaultValue()).isEqualTo("true");
                assertThat(spec.requiresRestart()).as("外置插件开关需完整重启").isTrue();
                assertThat(spec.label()).isNotBlank().doesNotStartWith("gui.config.field.");
                assertThat(spec.helpText()).isNotBlank().doesNotStartWith("gui.config.field.");
            }
        } finally {
            GuiMessages.clearLocaleOverride();
            Locale.setDefault(previousDefault);
        }
    }

    @Test
    @DisplayName("必选插件「下载工作台」不可禁用：不呈现开关字段")
    void requiredDownloadWorkbenchHasNoToggleField() {
        boolean present = ConfigFieldRegistry.allFields().stream()
                .anyMatch(spec -> spec.key().equals("plugins.download-workbench.enabled"));
        assertThat(present).as("download-workbench 为必选插件，不应有开关字段").isFalse();
    }
}
