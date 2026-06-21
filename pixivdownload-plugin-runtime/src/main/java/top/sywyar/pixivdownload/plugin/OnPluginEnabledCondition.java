package top.sywyar.pixivdownload.plugin;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.util.Map;

/**
 * {@link ConditionalOnPluginEnabled} 的判定：从 {@link org.springframework.core.env.Environment} 读
 * {@code plugins.<id>.enabled}（缺项默认启用），语义与 {@link PluginToggleProperties#isEnabled(String)}
 * 完全一致。条件在 Bean 注册期求值（早于 {@link PluginToggleProperties} 绑定为 Bean），因此直接读环境
 * 而非取该 Bean。
 * <p>
 * 本条件<b>只读开关、不区分必选 / 可选插件</b>。必选插件（core / download-workbench / schedule）的
 * 「不可禁用」由 app 侧 {@code PluginRegistry} 在注册期强制（必选插件即便开关为 {@code false} 也照常
 * 注册），其业务 Bean 又一律<b>不</b>标 {@link ConditionalOnPluginEnabled}（恒无条件装配，由 app 侧守卫
 * {@code PluginApiDependencyGuardTest} 固化）。因此本条件无需也<b>不得</b>回指 app 的组合根
 * {@code BuiltInPlugins} 判定必选性——plugin-runtime 在所有插件模块之下，回指组合根会让模块依赖成环。
 */
class OnPluginEnabledCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Map<String, Object> attributes =
                metadata.getAnnotationAttributes(ConditionalOnPluginEnabled.class.getName());
        if (attributes == null) {
            return true;
        }
        String pluginId = (String) attributes.get("value");
        return PluginToggleProperties.isEnabled(context.getEnvironment(), pluginId);
    }
}
