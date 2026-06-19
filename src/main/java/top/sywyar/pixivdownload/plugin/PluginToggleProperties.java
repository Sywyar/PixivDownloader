package top.sywyar.pixivdownload.plugin;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;

/**
 * 插件启用开关配置，映射 config.yaml 中的 {@code plugins.<id>.enabled}（{@code <id>} 为插件 id）。
 * 本身就是一张以插件 id 为键的开关表：未在配置中出现的插件<b>默认视为启用</b>，因此新增插件或旧配置
 * 缺项时默认全部启用。它是插件启用状态的<b>单一事实源</b>：{@link PluginRegistry} 据此决定活动快照，
 * {@link ConditionalOnPluginEnabled} 据此决定插件托管业务 Bean 是否装配。
 * <p>
 * 本类只反映 {@code config.yaml} 里的<b>原始开关值</b>；必选插件
 * （{@link top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin#required()}：核心插件，
 * 以及覆写返回 {@code true} 的功能插件如下载工作台）的「不可禁用」由 {@link PluginRegistry}
 * 注册时与 {@link OnPluginEnabledCondition} Bean 装配时强制（即便此处读到 {@code false} 也忽略），与本配置无关。
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

    /**
     * 直接从 {@link Environment} 读取 {@code plugins.<id>.enabled}（缺项默认启用），语义与实例方法
     * {@link #isEnabled(String)} 完全一致。供 {@link OnPluginEnabledCondition} 在 Bean 注册期使用——
     * 那一刻本类尚未绑定为 Bean，无法取实例，只能读环境；用 {@link Binder} 与 {@code @ConfigurationProperties}
     * 同款宽松绑定，短横线 id（如 {@code download-workbench}）正常解析。
     */
    public static boolean isEnabled(Environment environment, String pluginId) {
        if (pluginId == null || pluginId.isBlank()) {
            return true;
        }
        return Binder.get(environment)
                .bind("plugins." + pluginId + ".enabled", Boolean.class)
                .orElse(true);
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
