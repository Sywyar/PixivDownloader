package top.sywyar.pixivdownload.plugin.runtime.discovery;

import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDescriptor;
import top.sywyar.pixivdownload.plugin.runtime.status.PluginStatus;

import java.util.Objects;

/**
 * 一个从外置插件包发现的功能插件的运行时安装条目：描述符 + 运行时基线状态 + 解析用 classloader + 插件实例。
 * 这是发现桥接 {@link PixivPluginDiscoveryBridge#inspect} 产出的中性载体——只承载 {@code plugin.api} 契约
 * （{@link PixivFeaturePlugin}）、描述符 / 状态模型与 JDK 类型，<b>不</b>泄露任何 PF4J 实现类型。
 *
 * <p>{@link #status} 是发现阶段已能判定的<b>基线</b>状态：
 * <ul>
 *   <li>{@link PluginStatus#STARTED}：插件包已启动且核心 API 兼容，{@link #plugin} 与 {@link #classLoader} 在场，
 *       可被核心注册中心接入；</li>
 *   <li>{@link PluginStatus#INCOMPATIBLE}：插件包声明的核心 API 版本要求不被当前核心满足——<b>不</b>提取其功能插件
 *       （不信任不兼容插件的贡献），{@link #plugin} 为 {@code null}，拒绝接入。</li>
 * </ul>
 * 依赖可达性与必选策略等需要全局视图的判定不在此处，由 {@link top.sywyar.pixivdownload.plugin.runtime.status.PluginStatusEvaluator}
 * 在核心壳侧综合全部插件后推导。
 *
 * @param descriptor  插件描述符
 * @param status      发现阶段基线状态（{@link PluginStatus#STARTED} 或 {@link PluginStatus#INCOMPATIBLE}）
 * @param classLoader 解析该插件资源的 classloader（不兼容、未提取实例时仍记录插件包 classloader）
 * @param plugin      功能插件实例（兼容、已提取时在场；不兼容时为 {@code null}）
 */
public record PluginInstallation(
        PluginDescriptor descriptor,
        PluginStatus status,
        ClassLoader classLoader,
        PixivFeaturePlugin plugin) {

    public PluginInstallation {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(status, "status");
    }

    /** 功能插件 id（{@link PluginDescriptor#id()}）。 */
    public String id() {
        return descriptor.id();
    }

    /** 是否可接入核心注册中心（已启动、核心 API 兼容、实例在场）。 */
    public boolean registrable() {
        return status == PluginStatus.STARTED && plugin != null;
    }
}
