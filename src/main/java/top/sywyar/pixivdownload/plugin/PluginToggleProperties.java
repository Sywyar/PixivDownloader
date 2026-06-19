package top.sywyar.pixivdownload.plugin;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;

/**
 * 插件启用开关配置，映射 config.yaml 中的 {@code plugins.<id>.enabled}（{@code <id>} 为插件 id）。
 * 本身就是一张以插件 id 为键的开关表：未在配置中出现的插件<b>默认视为启用</b>，因此新增插件或旧配置
 * 缺项时默认全部启用。
 * <p>
 * 核心插件（{@link top.sywyar.pixivdownload.plugin.api.plugin.PluginKind#CORE}）永不可禁用，
 * 该硬约束由 {@link PluginRegistry} 在注册时强制（与本配置无关）；本配置只承载功能插件的开关。
 * <p>
 * Spring 上下文之外（{@code BuiltInPlugins.createAll()} 路径、单元测试）用空实例即代表全部启用。
 */
@Component
@ConfigurationProperties(prefix = "plugins")
public class PluginToggleProperties extends LinkedHashMap<String, PluginToggleProperties.PluginToggle> {

    /** 给定插件是否启用；未配置该插件时默认启用（true）。 */
    public boolean isEnabled(String pluginId) {
        PluginToggle toggle = get(pluginId);
        return toggle == null || toggle.isEnabled();
    }

    /** 单个插件的启用开关条目。 */
    public static class PluginToggle {

        /** 是否启用该插件，默认启用。 */
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
