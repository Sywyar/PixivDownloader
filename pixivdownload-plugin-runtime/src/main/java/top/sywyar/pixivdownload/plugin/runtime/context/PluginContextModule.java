package top.sywyar.pixivdownload.plugin.runtime.context;

import java.util.List;
import java.util.Objects;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PixivPluginDiscoveryBridge;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginInstallation;

/**
 * 一个外置插件包的「Spring 子 context 装配定义」：要在子 {@code ApplicationContext} 中实例化的配置类、
 * 解析这些类与其类路径资源所用的插件 classloader，以及来源插件包 id。这是发现桥接
 * （{@code PixivPluginDiscoveryBridge#inspectContextModules}）为<b>已启动且核心 API 兼容</b>的外置插件包产出的
 * 中性载体——只承载 {@code plugin.api} 入口契约暴露的 {@link Class} 配置类令牌与 JDK 类型，<b>不</b>泄露任何
 * 插件加载框架（PF4J）类型。
 *
 * <p>装配粒度是<b>外置插件包</b>（一个 PF4J pluginId = {@link #sourcePluginId}）：一个包内的全部配置类共享同一个
 * 插件 classloader，故归入同一个子 context。它与按<b>功能插件</b>粒度建模的 {@code PluginInstallation} 正交——
 * 后者服务于核心注册中心按功能插件 id 接入贡献，本载体服务于按插件包建立 Spring 子 context。
 *
 * @param sourcePluginId       外置插件包 id（PF4J pluginId），用于子 context 标识与诊断
 * @param classLoader          解析配置类与其类路径资源的插件 classloader（子 context 的 classloader）
 * @param configurationClasses 该插件包声明的 Spring {@code @Configuration} 配置类（来自入口契约
 *                             {@code PixivPluginProvider.configurationClasses()}，由插件 classloader 加载）
 */
public record PluginContextModule(String sourcePluginId, ClassLoader classLoader,
                                  List<Class<?>> configurationClasses) {

    public PluginContextModule {
        Objects.requireNonNull(sourcePluginId, "sourcePluginId");
        Objects.requireNonNull(classLoader, "classLoader");
        configurationClasses = List.copyOf(configurationClasses);
    }

    /** 是否声明了至少一个配置类（无配置类的插件包不需要子 context）。 */
    public boolean hasConfigurationClasses() {
        return !configurationClasses.isEmpty();
    }
}
