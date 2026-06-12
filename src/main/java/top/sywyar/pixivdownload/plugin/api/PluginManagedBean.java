package top.sywyar.pixivdownload.plugin.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 插件托管 Bean 标记。被标记的类不经根包组件扫描注册，
 * 由所属插件的 {@code XxxPluginConfiguration} 以 {@code @Bean} 显式提供
 * （前向兼容约束：插件 Bean 装配收敛到每插件一个显式 Configuration）。
 * <p>
 * 框架识别所需的字面量注解（如 Spring MVC handler 检测要求的
 * {@code @RestController}）保留在类上不受影响——本标记只负责把类排除出
 * 根包扫描，应用主类按注解类型一次性配置排除过滤器，新插件包无需再改主类。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface PluginManagedBean {
}
