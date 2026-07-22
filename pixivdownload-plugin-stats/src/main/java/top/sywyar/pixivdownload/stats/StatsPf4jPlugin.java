package top.sywyar.pixivdownload.stats;

import org.pf4j.Plugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivPluginProvider;

import java.util.List;

/**
 * stats 外置插件的 PF4J 主类：作为外置插件包从 {@code plugins/} 目录被 PF4J 加载、启动后，经入口契约
 * {@link PixivPluginProvider} 把统计功能插件 {@link StatsPlugin} 暴露给核心发现桥接，由其接入核心
 * {@code PluginRegistry}（来源 EXTERNAL）。
 *
 * <h2>为什么不让 {@code PixivFeaturePlugin} 直接继承 PF4J 类型</h2>
 * 入口契约 {@link PixivPluginProvider} 与功能插件 {@link PixivFeaturePlugin} 都是跨边界共享契约（{@code plugin.api}），
 * 必须由<b>宿主父 classloader</b> 加载，二者才是同一份 Class、桥接的 {@code instanceof} 判定才成立。故由本 PF4J 主类
 * （插件 classloader 加载）<b>实现</b>共享入口契约、再返回功能插件实例，而不是让功能插件本身耦合 PF4J。
 *
 * <h2>thin 插件包</h2>
 * 本插件包<b>不</b>打入 {@code plugin-api} / {@code core-api} / {@code plugin-runtime} / Spring / PF4J 等共享类
 * （它们在本模块均为 {@code provided}、由宿主提供）：插件 jar 只含 {@code top.sywyar.pixivdownload.stats.*} 与其
 * 静态资源 / i18n / {@code plugin.properties}。这样 {@code org.pf4j.Plugin}、{@link PixivPluginProvider} 等都从父
 * classloader 解析、与宿主共享同一份 Class，避免「插件自带契约副本→同名异 loader→{@code instanceof} 失败」。
 *
 * <p>功能贡献（route / static / i18n / navigation / schema）由 {@link StatsPlugin} 经 {@code PixivFeaturePlugin}
 * 接口方法声明，<b>不</b>依赖 Spring 组件扫描。统计 controller / service（{@code StatsController} /
 * {@code StatsService}）的 Bean 由 {@link StatsPluginConfiguration} 装配，经入口契约 {@link #configurationClasses()}
 * 交给宿主在 stats 专属子 {@code ApplicationContext} 中实例化；其 {@code /api/stats/**} 处理器的动态注册是另行
 * 处理的后续接线。
 */
public class StatsPf4jPlugin extends Plugin implements PixivPluginProvider {

    @Override
    public PixivFeaturePlugin featurePlugin() {
        return new StatsPlugin();
    }

    @Override
    public List<Class<?>> configurationClasses() {
        return List.of(StatsPluginConfiguration.class);
    }
}
