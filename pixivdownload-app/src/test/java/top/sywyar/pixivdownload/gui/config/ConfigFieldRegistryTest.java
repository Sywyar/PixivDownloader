package top.sywyar.pixivdownload.gui.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.gui.i18n.GuiMessages;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 配置面板字段注册守卫：桌面 GUI「插件」分组只呈现插件市场 / 受信仓库配置，插件启停由 Web 插件前端控制，
 * 不在 Swing 配置页呈现 {@code plugins.<id>.enabled} 开关。
 */
@DisplayName("配置面板字段注册：插件分组")
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
    @DisplayName("插件分组不呈现插件启停开关")
    void pluginToggleFieldsAreNotRegistered() {
        assertThat(ConfigFieldRegistry.allFields())
                .as("插件启停由 Web 插件前端控制，Swing 配置页不应注册 plugins.* 字段")
                .noneMatch(spec -> spec.key().startsWith("plugins."));
    }

    @Test
    @DisplayName("插件市场主开关仍保留在插件分组")
    void pluginCatalogToggleStillRegistered() {
        String pluginsGroup = GuiMessages.get("gui.config.group.plugins");
        ConfigFieldSpec spec = ConfigFieldRegistry.allFields().stream()
                .collect(Collectors.toMap(ConfigFieldSpec::key, field -> field, (a, b) -> a))
                .get("plugin-catalog.enabled");

        assertThat(spec).as("插件市场主开关应保留").isNotNull();
        assertThat(spec.type()).isEqualTo(FieldType.BOOL);
        assertThat(spec.group()).isEqualTo(pluginsGroup);
        assertThat(spec.defaultValue()).isEqualTo("false");
        assertThat(spec.label()).isNotBlank().doesNotStartWith("gui.config.field.");
    }
}
