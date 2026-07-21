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
 * 本条件<b>只读开关、不区分宿主必选策略</b>。app 侧 {@code PluginRegistry} 以「内置 CORE 结构事实、
 * {@code RequiredPluginPolicy} 或启用开关」决定活动快照；不可禁用能力的业务 Bean 一律<b>不</b>标
 * {@link ConditionalOnPluginEnabled}，并分别由 app 的内置 CORE 守卫与所属外置插件的模块守卫固化。
 * 因此本条件无需也<b>不得</b>回指 app 的组合根 {@code BuiltInPlugins} 或复制
 * {@code RequiredPluginPolicy}——plugin-runtime 在所有插件模块之下，回指组合根会让模块依赖成环。
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
