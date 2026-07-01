package top.sywyar.pixivdownload.plugin.management;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginInstallation;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginInventory;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginLoadFailure;
import top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeManager;
import top.sywyar.pixivdownload.plugin.runtime.install.ExternalPluginInstaller;
import top.sywyar.pixivdownload.plugin.runtime.install.model.InstalledPlugin;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDescriptor;
import top.sywyar.pixivdownload.plugin.runtime.status.PluginDiagnostic;
import top.sywyar.pixivdownload.plugin.runtime.status.PluginStatus;
import top.sywyar.pixivdownload.plugin.runtime.status.PluginStatusEvaluator;
import top.sywyar.pixivdownload.plugin.runtime.status.PluginStatusEvaluator.ObservedPlugin;
import top.sywyar.pixivdownload.plugin.runtime.status.PluginStatusReport;
import top.sywyar.pixivdownload.plugin.runtime.status.RequiredPluginPolicy;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import top.sywyar.pixivdownload.plugin.recovery.RecoveryModeService;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.BuiltInPlugins;

/**
 * 插件状态查询服务（后端、admin-grade，不依赖任何 UI）：把运行时观测（内置插件注册中心 {@link PluginRegistry} +
 * 外置插件清点 {@link PluginInventory}）与必选插件策略 {@link RequiredPluginPolicy} 综合为
 * {@link PluginStatusReport}，由 {@link PluginStatusEvaluator} 推导每个插件 id 的 {@link PluginStatus}。
 *
 * <p>本服务<b>只读、只报告</b>，不自行据状态改变核心启动 / 路由开放——是否据报告进入恢复模式由
 * {@link RecoveryModeService} 与访问控制消费方判定。它是状态模型「可由后端查询」的落点：管理 API / GUI 等入口复用
 * 本服务，不各自实现插件扫描。
 *
 * <p>内置插件的描述符由 {@link PluginDescriptor#forBuiltIn} 现造、基线状态取注册中心（活动=已启动、安装但未活动=已禁用）；
 * 外置插件的描述符与基线状态取清点结果（含被拒绝接入的不兼容条目）；无法读出描述符的包级加载失败追加为
 * {@link PluginStatus#FAILED} 诊断。
 */
@Service
public class PluginStatusService {

    private final PluginRegistry pluginRegistry;
    private final Supplier<PluginInventory> pluginInventory;
    private final Supplier<List<InstalledPlugin>> installedArtifacts;
    private final RequiredPluginPolicy requiredPluginPolicy;
    private final PluginStatusEvaluator evaluator = new PluginStatusEvaluator();

    public PluginStatusService(PluginRegistry pluginRegistry, PluginInventory pluginInventory,
                               RequiredPluginPolicy requiredPluginPolicy) {
        this.pluginRegistry = pluginRegistry;
        this.pluginInventory = () -> pluginInventory;
        this.installedArtifacts = List::of;
        this.requiredPluginPolicy = requiredPluginPolicy;
    }

    /** Spring 运行时读取动态清点，避免 singleton 永久固定旧插件实例和 classloader。 */
    @Autowired
    public PluginStatusService(PluginRegistry pluginRegistry, PluginRuntimeManager runtimeManager,
                               ExternalPluginInstaller installer,
                               RequiredPluginPolicy requiredPluginPolicy) {
        this.pluginRegistry = pluginRegistry;
        this.pluginInventory = runtimeManager::inspectPlugins;
        this.installedArtifacts = installer::listInstalled;
        this.requiredPluginPolicy = requiredPluginPolicy;
    }

    /** 计算当前插件状态报告。每次调用按当前注册中心 / 清点快照重新评估。 */
    public PluginStatusReport report() {
        Set<String> activeIds = pluginRegistry.plugins().stream()
                .map(PixivFeaturePlugin::id)
                .collect(Collectors.toSet());

        List<ObservedPlugin> observed = new ArrayList<>();
        // 内置插件：描述符现造，活动=已启动、安装但未活动=已禁用。
        for (PixivFeaturePlugin plugin : pluginRegistry.allPlugins()) {
            if (BuiltInPlugins.isBuiltIn(plugin.id())) {
                PluginStatus base = activeIds.contains(plugin.id()) ? PluginStatus.STARTED : PluginStatus.DISABLED;
                observed.add(new ObservedPlugin(PluginDescriptor.forBuiltIn(plugin), base));
            }
        }
        // 外置插件：取清点结果的描述符与基线状态（不兼容条目原样保留，由评估器判 INCOMPATIBLE）。
        PluginInventory inventory = pluginInventory.get();
        for (PluginInstallation installation : inventory.installations()) {
            observed.add(new ObservedPlugin(installation.descriptor(),
                    externalBaseStatus(installation, activeIds)));
        }
        Set<String> observedExternalPackages = inventory.installations().stream()
                .map(installation -> installation.descriptor().sourcePluginId())
                .collect(Collectors.toSet());
        for (InstalledPlugin installed : installedArtifacts.get()) {
            if (!observedExternalPackages.contains(installed.id())) {
                observed.add(new ObservedPlugin(installed.descriptor(), PluginStatus.INSTALLED));
            }
        }

        PluginStatusReport evaluated = evaluator.evaluate(observed, requiredPluginPolicy);

        // 包级加载 / 发现失败（无描述符）追加为 FAILED 诊断，使报告覆盖坏包。
        List<PluginDiagnostic> diagnostics = new ArrayList<>(evaluated.diagnostics());
        for (PluginLoadFailure failure : inventory.failures()) {
            diagnostics.add(new PluginDiagnostic(failure.source(), PluginStatus.FAILED, null,
                    requiredPluginPolicy.isRequired(failure.source()), List.of(failure.reason())));
        }
        return new PluginStatusReport(diagnostics);
    }

    private static PluginStatus externalBaseStatus(PluginInstallation installation, Set<String> activeIds) {
        if (installation.status() != PluginStatus.STARTED) {
            return installation.status();
        }
        // 已启动且兼容：若被开关禁用（已发现但未进入活动快照）则报 DISABLED，否则 STARTED。
        return activeIds.contains(installation.id()) ? PluginStatus.STARTED : PluginStatus.DISABLED;
    }
}
