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
