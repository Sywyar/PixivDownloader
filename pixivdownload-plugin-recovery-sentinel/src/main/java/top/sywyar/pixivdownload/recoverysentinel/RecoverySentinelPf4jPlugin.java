package top.sywyar.pixivdownload.recoverysentinel;

import org.pf4j.Plugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivPluginProvider;

import java.util.List;

/**
 * recovery-sentinel 外置插件的 PF4J 主类：作为外置插件包从 {@code plugins/} 目录被 PF4J 加载、启动后，经入口契约
 * {@link PixivPluginProvider} 把最小功能插件 {@link RecoverySentinelPlugin} 暴露给核心发现桥接，由其接入核心
 * {@code PluginRegistry}（来源 EXTERNAL）。
 *
 * <h2>为什么不让 {@code PixivFeaturePlugin} 直接继承 PF4J 类型</h2>
 * 入口契约 {@link PixivPluginProvider} 与功能插件 {@link PixivFeaturePlugin} 都是跨边界共享契约（{@code plugin.api}），
 * 必须由<b>宿主父 classloader</b> 加载，二者才是同一份 Class、桥接的 {@code instanceof} 判定才成立。故由本 PF4J 主类
 * （插件 classloader 加载）<b>实现</b>共享入口契约、再返回功能插件实例，而不是让功能插件本身耦合 PF4J。
 *
 * <h2>thin 插件包</h2>
 * 本插件包<b>不</b>打入 {@code plugin-api} / PF4J 等共享类（它们在本模块均为 {@code provided}、由宿主提供）：插件 jar
 * 只含 {@code top.sywyar.pixivdownload.recoverysentinel.*} 与根部 {@code plugin.properties}。这样 {@code org.pf4j.Plugin}、
 * {@link PixivPluginProvider} 等都从父 classloader 解析、与宿主共享同一份 Class，避免「插件自带契约副本→同名异 loader→
 * {@code instanceof} 失败」。
 */
public class RecoverySentinelPf4jPlugin extends Plugin implements PixivPluginProvider {

    @Override
    public List<PixivFeaturePlugin> featurePlugins() {
        return List.of(new RecoverySentinelPlugin());
    }
}
