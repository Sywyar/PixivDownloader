package top.sywyar.pixivdownload.plugin.runtime;

import org.pf4j.Plugin;
import org.pf4j.PluginManager;
import org.pf4j.PluginState;
import org.pf4j.PluginWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivPluginProvider;

import java.util.ArrayList;
import java.util.List;

/**
 * PF4J 外置插件发现桥接：把 PF4J {@link PluginManager} 已加载 / 启动的外置插件，转换为核心可消费的中性
 * {@link DiscoveredFeaturePlugin}（携带 {@link PixivFeaturePlugin} + 来源 + 插件 classloader）。本类是 PF4J
 * 与核心壳之间唯一接触 {@code org.pf4j} 的桥接点——产出全部为 {@code plugin.api} 契约 + JDK 类型，不向外泄露任何
 * PF4J 实现类型。
 *
 * <h2>发现规则</h2>
 * <ul>
 *   <li><b>只看已启动插件。</b>仅 {@link PluginState#STARTED} 的插件参与发现；未启动 / 失败的包由
 *       {@link PluginRuntimeManager} 在加载 / 启动阶段已捕获为失败诊断，不在此重复。</li>
 *   <li><b>经入口契约提取。</b>外置插件主类（PF4J {@code Plugin-Class}）须实现跨边界契约
 *       {@link PixivPluginProvider}；桥接调用 {@link PixivPluginProvider#featurePlugins()} 取得它贡献的
 *       {@link PixivFeaturePlugin} 列表，逐个连同 {@link PluginWrapper#getPluginClassLoader()} 包装。</li>
 *   <li><b>classloader 边界严格。</b>每个发现的功能插件都带上其插件 classloader（资源解析的依据），
 *       绝不用核心壳的应用 classloader 误读外置插件资源。</li>
 *   <li><b>诊断清晰、隔离失败。</b>主类未实现 {@link PixivPluginProvider}（含「插件自带了一份 plugin-api
 *       副本 → 同名类不同 classloader → instanceof 不成立」的典型 classloader 类型错误）、入口方法抛错或返回
 *       {@code null} 都被收敛为 {@link PluginLoadFailure} 条目并记日志，<b>不</b>抛出、<b>不</b>影响其它插件发现，
 *       更<b>不</b>致核心壳启动失败。</li>
 * </ul>
 */
public final class PixivPluginDiscoveryBridge {

    private static final Logger log = LoggerFactory.getLogger(PixivPluginDiscoveryBridge.class);

    /**
     * 遍历 {@link PluginManager} 已启动的外置插件，发现其贡献的全部 {@link PixivFeaturePlugin}。
     * 本方法不向上抛出：任一插件的发现失败被隔离为 {@link PluginDiscoveryResult#failures()} 条目。
     */
    public PluginDiscoveryResult discover(PluginManager manager) {
        if (manager == null) {
            return PluginDiscoveryResult.empty();
        }
        List<DiscoveredFeaturePlugin> discovered = new ArrayList<>();
        List<PluginLoadFailure> failures = new ArrayList<>();
        for (PluginWrapper wrapper : manager.getPlugins()) {
            if (wrapper.getPluginState() != PluginState.STARTED) {
                continue;
            }
            discoverFromWrapper(wrapper, discovered, failures);
        }
        return new PluginDiscoveryResult(discovered, failures);
    }

    private void discoverFromWrapper(PluginWrapper wrapper,
                                     List<DiscoveredFeaturePlugin> discovered,
                                     List<PluginLoadFailure> failures) {
        String sourcePluginId = wrapper.getPluginId();
        ClassLoader classLoader = wrapper.getPluginClassLoader();

        Plugin plugin;
        try {
            plugin = wrapper.getPlugin();
        } catch (Exception e) {
            failures.add(fail(sourcePluginId, "failed to obtain plugin instance: " + describe(e)));
            return;
        }
        if (!(plugin instanceof PixivPluginProvider provider)) {
            String pluginClassName = plugin == null ? "null" : plugin.getClass().getName();
            failures.add(fail(sourcePluginId, "plugin main class does not implement PixivPluginProvider: "
                    + pluginClassName + " (ensure pixivdownload-plugin-api is provided by the host and not "
                    + "bundled inside the plugin, otherwise the contract type is loaded by a different classloader)"));
            return;
        }

        List<PixivFeaturePlugin> featurePlugins;
        try {
            featurePlugins = provider.featurePlugins();
        } catch (Exception e) {
            failures.add(fail(sourcePluginId, "featurePlugins() threw: " + describe(e)));
            return;
        }
        if (featurePlugins == null) {
            failures.add(fail(sourcePluginId, "featurePlugins() returned null"));
            return;
        }
        for (PixivFeaturePlugin featurePlugin : featurePlugins) {
            if (featurePlugin == null) {
                failures.add(fail(sourcePluginId, "featurePlugins() contained a null element"));
                continue;
            }
            discovered.add(new DiscoveredFeaturePlugin(sourcePluginId, featurePlugin, classLoader));
        }
    }

    private static PluginLoadFailure fail(String sourcePluginId, String reason) {
        log.error("External plugin discovery failed for {}: {}", sourcePluginId, reason);
        return new PluginLoadFailure(sourcePluginId, reason);
    }

    private static String describe(Throwable t) {
        if (t == null) {
            return "unknown error";
        }
        String message = t.getMessage();
        return (message != null && !message.isBlank()) ? message : t.getClass().getName();
    }
}
