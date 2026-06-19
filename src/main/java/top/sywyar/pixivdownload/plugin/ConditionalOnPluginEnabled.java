package top.sywyar.pixivdownload.plugin;

import org.springframework.context.annotation.Conditional;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 仅当 {@code plugins.<id>.enabled} 为真（缺项默认启用）时才装配被标注的 {@code @Bean} 方法 /
 * {@code @Configuration} 类。用于让<b>插件托管业务 Bean</b> 随插件启停而装配 / 缺席，与
 * {@link PluginToggleProperties}（{@link PluginRegistry} 据此决定活动快照）共用同一启用事实源。
 * <p>
 * <b>不要</b>给插件 descriptor（{@link top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin}）
 * Bean 标注本注解：descriptor 必须始终注册，{@link PluginRegistry#allPlugins()}、受管 schema 合并、
 * {@link PluginRegistry#disabledPlugins()} 都依赖全部 descriptor 在场。核心插件的 Bean 也不标注
 * （核心永不可禁用）。功能插件若有「即便插件被禁用也必须运行」的核心链路能力，正确做法是把它抽成
 * <b>核心服务</b>（根包扫描、不属任何功能插件，如下载后即时算 Hash 的核心服务
 * {@code core.hash.ArtworkHashService}），而不是把它留在功能插件里强制始终装配——后者会造成「禁用插件
 * 却仍有插件托管 Bean 常驻」的归属歧义。
 *
 * @see OnPluginEnabledCondition
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(OnPluginEnabledCondition.class)
public @interface ConditionalOnPluginEnabled {

    /** 插件 id（小写短横线，如 {@code download-workbench}）。 */
    String value();
}
