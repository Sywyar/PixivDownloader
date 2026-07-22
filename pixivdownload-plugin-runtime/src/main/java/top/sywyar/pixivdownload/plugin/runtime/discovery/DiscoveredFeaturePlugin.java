package top.sywyar.pixivdownload.plugin.runtime.discovery;

import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;

import java.util.Objects;

/**
 * 一个从外置插件包中发现的 {@link PixivFeaturePlugin}，连同它的来源信息，构成核心壳把外置插件接入
 * {@code PluginRegistry} 的中性适配载体。本 record 只承载跨边界共享契约（{@link PixivFeaturePlugin}）
 * 与 JDK 类型（{@link ClassLoader} / {@link String}），<b>不</b>泄露任何 PF4J 实现类型——PF4J 收口在
 * {@link PixivPluginDiscoveryBridge} 内部。
 *
 * @param sourcePluginId  外置插件包的标识（PF4J pluginId，来自插件描述符），用于诊断 / 溯源
 * @param featurePluginId 发现桥首次读取并校验后盖章的稳定功能插件 id；当前发布格式要求它与
 *                        {@code sourcePluginId} 相同，后续注册与排序不得再次调用不可信的
 *                        {@link PixivFeaturePlugin#id()}
 * @param plugin         发现的功能插件实例（由 {@link #classLoader()} 创建）
 * @param classLoader    创建该插件实例的插件 classloader：其声明的静态资源 / i18n bundle 等资源解析都应经此
 *                       classloader 进行，禁止回退核心壳的应用 classloader
 */
public record DiscoveredFeaturePlugin(String sourcePluginId, String featurePluginId, long generation,
                                      PixivFeaturePlugin plugin, ClassLoader classLoader) {

    public DiscoveredFeaturePlugin {
        Objects.requireNonNull(sourcePluginId, "sourcePluginId");
        Objects.requireNonNull(featurePluginId, "featurePluginId");
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(classLoader, "classLoader");
        if (!sourcePluginId.equals(featurePluginId)) {
            throw new IllegalArgumentException("external package id must match its captured feature id: "
                    + sourcePluginId + " != " + featurePluginId);
        }
    }

    /** 兼容启动期/测试载体；未提供代际时使用 0。 */
    public DiscoveredFeaturePlugin(String sourcePluginId, String featurePluginId,
                                   PixivFeaturePlugin plugin, ClassLoader classLoader) {
        this(sourcePluginId, featurePluginId, 0L, plugin, classLoader);
    }
}
