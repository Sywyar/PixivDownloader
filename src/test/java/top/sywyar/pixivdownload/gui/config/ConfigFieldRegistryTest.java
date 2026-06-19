package top.sywyar.pixivdownload.gui.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.gui.i18n.GuiMessages;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 配置面板字段注册守卫：内置插件开关（{@code plugins.<id>.enabled}）作为「插件」分组的 BOOL 字段呈现，
 * 与 config.yaml 模板对齐、需重启、i18n 标签已解析。
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
    @DisplayName("5 个内置功能插件各有一个 BOOL 开关字段：默认启用、需重启、i18n 标签已解析")
    void pluginToggleFieldsRegistered() {
        String pluginsGroup = GuiMessages.get("gui.config.group.plugins");
        Map<String, ConfigFieldSpec> byKey = ConfigFieldRegistry.allFields().stream()
                .collect(Collectors.toMap(ConfigFieldSpec::key, spec -> spec, (a, b) -> a));

        for (String id : List.of("download-workbench", "gallery", "novel", "stats", "duplicate")) {
            String key = "plugins." + id + ".enabled";
            ConfigFieldSpec spec = byKey.get(key);
            assertThat(spec).as("字段 %s 应已注册", key).isNotNull();
            assertThat(spec.type()).isEqualTo(FieldType.BOOL);
            assertThat(spec.group()).isEqualTo(pluginsGroup);
            assertThat(spec.defaultValue()).isEqualTo("true");
            assertThat(spec.requiresRestart()).as("插件开关需重启").isTrue();
            assertThat(spec.label())
                    .as("label i18n 已解析（非原始 key）")
                    .isNotEqualTo("gui.config.field.plugins." + id + ".enabled.label");
            assertThat(spec.helpText())
                    .as("help i18n 已解析（非原始 key）")
                    .isNotEqualTo("gui.config.field.plugins.enabled.help");
        }
    }
}
