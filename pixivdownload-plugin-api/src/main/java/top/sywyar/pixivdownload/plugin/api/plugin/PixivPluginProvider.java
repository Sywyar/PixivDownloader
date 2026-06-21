package top.sywyar.pixivdownload.plugin.api.plugin;

import java.util.List;

/**
 * 外置插件向核心暴露其 {@link PixivFeaturePlugin} 的入口契约。外置插件 jar 的主类
 * （PF4J {@code Plugin-Class}，运行期同时继承插件框架的插件基类）实现本接口，返回它贡献的一个或多个
 * {@link PixivFeaturePlugin}；核心壳的发现桥接据此把外置插件接入 {@code PluginRegistry}。
 *
 * <p><b>跨 classloader 边界契约。</b>本接口与 {@link PixivFeaturePlugin}（及其引用的 contribution / 服务接口）
 * 是外置插件与核心之间<b>唯一</b>允许跨边界传递的类型，必须由父 classloader 加载的共享契约包
 * {@code pixivdownload-plugin-api} 提供——否则会出现「同名类不同 classloader」的 {@link ClassCastException}。
 * 因此本接口保持零业务、零框架依赖（仅 JDK + {@code plugin.api} 自身），<b>不</b>引用 PF4J、Spring 等任何
 * 插件加载框架类型：插件加载框架是核心壳的实现细节，不进入跨插件契约。
 *
 * <p>一个外置插件可贡献多个 {@link PixivFeaturePlugin}（如一个聚合包同时提供统计与判重）；各
 * {@link PixivFeaturePlugin#id()} 在核心 {@code PluginRegistry} 中全局唯一，与内置插件冲突时由核心 fail-fast。
 */
public interface PixivPluginProvider {

    /**
     * 本外置插件贡献的全部 {@link PixivFeaturePlugin}。返回的实例由插件自身 classloader 创建，
     * 其声明的静态资源 / i18n bundle 等也经该 classloader 解析。不得返回 {@code null}；
     * 不贡献任何功能插件时返回空列表。
     */
    List<PixivFeaturePlugin> featurePlugins();
}
