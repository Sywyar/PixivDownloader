package top.sywyar.pixivdownload.plugin;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeManager;
import top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeStatus;

/**
 * 核心壳侧装配 PF4J 外置插件运行时骨架（{@link PluginRuntimeManager} 住 {@code pixivdownload-plugin-runtime}
 * 模块、对 app 不可见地封装了 PF4J）。插件目录路径经 {@link RuntimeFiles#pluginsDirectory()} 解析（集中在
 * {@code RuntimeFiles}、与 config / state / data 同套覆盖机制），由本配置注入运行时管理器。
 *
 * <p>启动期把 {@code start()} 的结果暴露为 {@link PluginRuntimeStatus} Bean（扫描 / 加载 / 启动外置插件、
 * 输出目录缺失 / 空 / 坏包诊断）。当前运行时骨架只负责定位 / 扫描 / 诊断，不改变核心启动：
 * <ul>
 *   <li>插件目录缺失 / 空 / 含坏包都<b>不</b>致核心壳启动失败（由 {@code PluginRuntimeManager} 收敛）；</li>
 *   <li><b>不</b>把外置插件接入 {@link PluginRegistry}，<b>不</b>改变内置插件注册 / 禁用 / required 语义；</li>
 *   <li><b>不</b>做热安装 / 热卸载，也不据诊断状态改变核心启动（插件加载策略 / 恢复流程据本 Bean 暴露的状态另行判断）。</li>
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
}
