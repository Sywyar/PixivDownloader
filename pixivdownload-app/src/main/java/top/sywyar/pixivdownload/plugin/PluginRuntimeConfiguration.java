package top.sywyar.pixivdownload.plugin;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.plugin.runtime.PluginDiscoveryResult;
import top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeManager;
import top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeStatus;

/**
 * 核心壳侧装配 PF4J 外置插件运行时（{@link PluginRuntimeManager} 住 {@code pixivdownload-plugin-runtime}
 * 模块、对 app 不可见地封装了 PF4J）。插件目录路径经 {@link RuntimeFiles#pluginsDirectory()} 解析（集中在
 * {@code RuntimeFiles}、与 config / state / data 同套覆盖机制），由本配置注入运行时管理器。
 *
 * <p>启动期把 {@code start()} 的结果暴露为 {@link PluginRuntimeStatus} Bean（扫描 / 加载 / 启动外置插件、
 * 输出目录缺失 / 空 / 坏包诊断），并把发现到的外置功能插件暴露为 {@link PluginDiscoveryResult} Bean 供
 * {@link PluginRegistry} 接入。运行时只负责定位 / 扫描 / 启动 / 发现，不改变核心壳的弹性：
 * <ul>
 *   <li>插件目录缺失 / 空 / 含坏包都<b>不</b>致核心壳启动失败（由 {@code PluginRuntimeManager} 收敛）；</li>
 *   <li>外置插件经 {@link PluginRegistry} 与内置插件统一注册（来源标记区分），但<b>不</b>改变内置插件注册 /
 *       禁用 / required 语义；外置 pluginId 与内置冲突由 {@link PluginRegistry} fail-fast。</li>
 *   <li><b>不</b>做热安装 / 热卸载，也不据诊断状态改变核心启动（必选插件清单 / 恢复流程据这些 Bean 暴露的状态另行判断）。</li>
 * </ul>
 */
@Configuration
public class PluginRuntimeConfiguration {

    @Bean
    public PluginRuntimeManager pluginRuntimeManager() {
        return new PluginRuntimeManager(RuntimeFiles.pluginsDirectory());
    }

    @Bean
    public PluginRuntimeStatus pluginRuntimeStatus(PluginRuntimeManager pluginRuntimeManager) {
        return pluginRuntimeManager.start();
    }

    /**
     * 发现已启动外置插件贡献的功能插件，供 {@link PluginRegistry} 接入。形参 {@code pluginRuntimeStatus} 仅用于排序
     * （确保 {@code start()} 先完成、PF4J 实例已就绪），发现本身读运行时管理器缓存的已启动插件。
     */
    @Bean
    public PluginDiscoveryResult pluginDiscoveryResult(PluginRuntimeManager pluginRuntimeManager,
                                                       PluginRuntimeStatus pluginRuntimeStatus) {
        return pluginRuntimeManager.discoverFeaturePlugins();
    }
}
