package top.sywyar.pixivdownload.plugin.runtime;

import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;

import java.util.Objects;

/**
 * 一个从外置插件包中发现的 {@link PixivFeaturePlugin}，连同它的来源信息，构成核心壳把外置插件接入
 * {@code PluginRegistry} 的中性适配载体。本 record 只承载跨边界共享契约（{@link PixivFeaturePlugin}）
 * 与 JDK 类型（{@link ClassLoader} / {@link String}），<b>不</b>泄露任何 PF4J 实现类型——PF4J 收口在
 * {@link PixivPluginDiscoveryBridge} 内部。
 *
 * @param sourcePluginId 外置插件包的标识（PF4J pluginId，来自插件描述符），仅用于诊断 / 溯源；它与
 *                       {@link PixivFeaturePlugin#id()} 是两套命名空间——一个外置包可贡献多个功能插件，
 *                       核心注册中心按 {@link PixivFeaturePlugin#id()} 去重 / 排序
 * @param plugin         发现的功能插件实例（由 {@link #classLoader()} 创建）
 * @param classLoader    创建该插件实例的插件 classloader：其声明的静态资源 / i18n bundle 等资源解析都应经此
 *                       classloader 进行，禁止回退核心壳的应用 classloader
 */
public record DiscoveredFeaturePlugin(String sourcePluginId, PixivFeaturePlugin plugin, ClassLoader classLoader) {

    public DiscoveredFeaturePlugin {
        Objects.requireNonNull(sourcePluginId, "sourcePluginId");
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(classLoader, "classLoader");
    }

    /** 功能插件自身的 id（{@link PixivFeaturePlugin#id()}）：核心注册中心据此去重与排序。 */
    public String featurePluginId() {
        return plugin.id();
    }
}
