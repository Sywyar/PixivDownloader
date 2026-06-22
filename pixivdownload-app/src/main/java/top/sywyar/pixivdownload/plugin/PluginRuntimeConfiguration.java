package top.sywyar.pixivdownload.plugin;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.plugin.api.PluginApiVersion;
import top.sywyar.pixivdownload.plugin.runtime.PluginDiscoveryResult;
import top.sywyar.pixivdownload.plugin.runtime.PluginInventory;
import top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeManager;
import top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeStatus;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginApiRequirement;
import top.sywyar.pixivdownload.plugin.runtime.install.ExternalPluginInstaller;
import top.sywyar.pixivdownload.plugin.runtime.status.RequiredPluginPolicy;

import java.util.List;

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
 *   <li><b>不</b>做热安装 / 热卸载，也不据诊断状态改变核心启动；必选清单与诊断状态仅经这些 Bean 暴露，是否据此处置不在本配置内决定。</li>
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
     * 清点已启动外置插件的功能插件安装条目（统一描述符 + 兼容性基线状态 + classloader + 实例）与失败诊断，供
     * {@link PluginDiscoveryResult}（注册接入）与 {@link PluginStatusService}（状态报告）共用同一次清点结果。
     * 形参 {@code pluginRuntimeStatus} 仅用于排序（确保 {@code start()} 先完成、PF4J 实例已就绪）。
     */
    @Bean
    public PluginInventory pluginInventory(PluginRuntimeManager pluginRuntimeManager,
                                           PluginRuntimeStatus pluginRuntimeStatus) {
        return pluginRuntimeManager.inspectPlugins();
    }

    /**
     * 投影出可接入 {@link PluginRegistry} 的外置功能插件发现结果：仅核心 API 兼容且已启动者进入 {@code discovered}，
     * 不兼容 / 失败者并入 {@code failures}（拒绝接入）。
     */
    @Bean
    public PluginDiscoveryResult pluginDiscoveryResult(PluginInventory pluginInventory) {
        return pluginInventory.toDiscoveryResult();
    }

    /**
     * 必选插件策略：声明核心 / 发行视角下必须在场的插件 id（首个为下载工作台 {@code download-workbench}）及其兼容
     * 版本范围、是否允许禁用、缺失 / 不兼容时的提示文案键。{@link RecoveryModeService} 据本策略与插件状态报告判定是否
     * 进入恢复模式；本配置只声明策略数据，不在启动期据此拦截请求（拦截由恢复模式访问控制 {@link RecoveryModeGate}
     * 执行）。
     * <p>下载工作台当前随主程序编译为内置必选插件、恒在场且与核心 API 同版本，故正常运行下该必选项恒满足。
     */
    @Bean
    public RequiredPluginPolicy requiredPluginPolicy() {
        return RequiredPluginPolicy.of(List.of(
                new RequiredPluginPolicy.RequiredPlugin(
                        "download-workbench",
                        PluginApiRequirement.of(PluginApiVersion.MAJOR, PluginApiVersion.MINOR),
                        false,
                        "plugin.recovery.missing.download-workbench")));
    }

    /**
     * 外置插件安装器：把上传的 {@code .zip} / {@code .jar} 安装包安全装入 {@link RuntimeFiles#pluginsDirectory()}
     *（PF4J 扫描的同一目录），处理 Zip Slip 防护、布局校验、核心 API 兼容门与重复 / 升级 / 降级。POJO、构造无副作用、
     * 不创建目录（目录在首次安装提交时按需创建）。装为 Bean 供后续安装流程复用，本配置不在启动期调用安装、也不新增 UI。
     */
    @Bean
    public ExternalPluginInstaller externalPluginInstaller() {
        return new ExternalPluginInstaller(RuntimeFiles.pluginsDirectory());
    }
}
