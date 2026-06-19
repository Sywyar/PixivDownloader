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
 * 必选插件（{@link BuiltInPlugins#isRequired(String)}）的开关被忽略、其托管 Bean 恒装配——与
 * {@link PluginRegistry} 对必选插件「即便开关为 false 也照常注册」保持一致，避免必选插件出现
 * 「贡献已注册但 Bean 缺席」的半装配态。
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
        if (BuiltInPlugins.isRequired(pluginId)) {
            return true;
        }
        return PluginToggleProperties.isEnabled(context.getEnvironment(), pluginId);
    }
}
