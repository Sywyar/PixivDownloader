package top.sywyar.pixivdownload.plugin.management;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginInstallation;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginInventory;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginLoadFailure;
import top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeManager;
import top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeStatus;
import top.sywyar.pixivdownload.plugin.runtime.status.PluginRuntimeVerificationSnapshot;
import top.sywyar.pixivdownload.plugin.runtime.install.ExternalPluginInstaller;
import top.sywyar.pixivdownload.plugin.runtime.install.model.InstalledPlugin;
import top.sywyar.pixivdownload.plugin.runtime.install.transaction.PluginRecoveryGateSnapshot;
import top.sywyar.pixivdownload.plugin.runtime.install.transaction.PluginTransactionRecoveryReport;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDescriptor;
import top.sywyar.pixivdownload.plugin.runtime.lifecycle.PluginRuntimePackagePhase;
import top.sywyar.pixivdownload.plugin.runtime.status.PluginDiagnostic;
import top.sywyar.pixivdownload.plugin.runtime.status.PluginStatus;
import top.sywyar.pixivdownload.plugin.runtime.status.PluginStatusEvaluator;
import top.sywyar.pixivdownload.plugin.runtime.status.PluginStatusEvaluator.ObservedPlugin;
import top.sywyar.pixivdownload.plugin.runtime.status.PluginStatusReport;
import top.sywyar.pixivdownload.plugin.runtime.status.RequiredPluginPolicy;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import top.sywyar.pixivdownload.plugin.recovery.RecoveryModeService;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.registry.PluginSource;

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
    private final Supplier<Map<String, PluginDescriptor>> loadedDescriptors;
    private final Supplier<PluginRecoveryGateSnapshot> recoveryGate;
    private final Supplier<List<PluginRuntimeVerificationSnapshot>> runtimeVerifications;
    private final Supplier<Map<String, String>> runtimeFailures;
    private final Supplier<Map<String, PluginRuntimePackagePhase>> runtimePhases;
    private final RequiredPluginPolicy requiredPluginPolicy;
    private final PluginStatusEvaluator evaluator = new PluginStatusEvaluator();

    public PluginStatusService(PluginRegistry pluginRegistry, PluginInventory pluginInventory,
                               RequiredPluginPolicy requiredPluginPolicy) {
        this(pluginRegistry, () -> pluginInventory, List::of, Map::of, requiredPluginPolicy);
    }

    public PluginStatusService(PluginRegistry pluginRegistry,
                               Supplier<PluginInventory> pluginInventory,
                               Supplier<List<InstalledPlugin>> installedArtifacts,
                               RequiredPluginPolicy requiredPluginPolicy) {
        this(pluginRegistry, pluginInventory, installedArtifacts, Map::of, requiredPluginPolicy);
    }

    public PluginStatusService(PluginRegistry pluginRegistry,
                               Supplier<PluginInventory> pluginInventory,
                               Supplier<List<InstalledPlugin>> installedArtifacts,
                               Supplier<Map<String, PluginDescriptor>> loadedDescriptors,
                               RequiredPluginPolicy requiredPluginPolicy) {
        this(pluginRegistry, pluginInventory, installedArtifacts, loadedDescriptors,
                () -> PluginRecoveryGateSnapshot.safe(PluginTransactionRecoveryReport.success()),
                List::of, requiredPluginPolicy);
    }

    public PluginStatusService(PluginRegistry pluginRegistry,
                               Supplier<PluginInventory> pluginInventory,
                               Supplier<List<InstalledPlugin>> installedArtifacts,
                               Supplier<Map<String, PluginDescriptor>> loadedDescriptors,
                               Supplier<PluginRecoveryGateSnapshot> recoveryGate,
                               RequiredPluginPolicy requiredPluginPolicy) {
        this(pluginRegistry, pluginInventory, installedArtifacts, loadedDescriptors, recoveryGate,
                List::of, requiredPluginPolicy);
    }

    public PluginStatusService(PluginRegistry pluginRegistry,
                               Supplier<PluginInventory> pluginInventory,
                               Supplier<List<InstalledPlugin>> installedArtifacts,
                               Supplier<Map<String, PluginDescriptor>> loadedDescriptors,
                               Supplier<PluginRecoveryGateSnapshot> recoveryGate,
                               Supplier<List<PluginRuntimeVerificationSnapshot>> runtimeVerifications,
                               RequiredPluginPolicy requiredPluginPolicy) {
        this.pluginRegistry = pluginRegistry;
        this.pluginInventory = pluginInventory;
        this.installedArtifacts = installedArtifacts;
        this.loadedDescriptors = loadedDescriptors;
        this.recoveryGate = recoveryGate;
        this.runtimeVerifications = runtimeVerifications;
        this.runtimeFailures = Map::of;
        this.runtimePhases = Map::of;
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
        this.loadedDescriptors = runtimeManager::loadedDescriptors;
        this.recoveryGate = installer::recoveryGateSnapshot;
        this.runtimeVerifications = () -> runtimeManager.status()
                .map(status -> status.verifications())
                .orElseGet(List::of);
        this.runtimeFailures = () -> runtimeManager.status()
                .map(PluginStatusService::runtimeFailures)
                .orElseGet(Map::of);
        this.runtimePhases = runtimeManager::packagePhases;
        this.requiredPluginPolicy = requiredPluginPolicy;
    }

    /** 计算当前插件状态报告。每次调用按当前注册中心 / 清点快照重新评估。 */
    public PluginStatusReport report() {
        PluginRecoveryGateSnapshot recovery = recoveryGate.get();
        Map<String, String> lifecycleFailures = currentFailuresById();
        Set<String> crashedIds = crashedPluginIds();
        Set<String> activeIds = pluginRegistry.registeredPlugins().stream()
                .map(PluginRegistry.RegisteredPlugin::id)
                .collect(Collectors.toSet());

        List<ObservedPlugin> observed = new ArrayList<>();
        // 内置插件：描述符现造，活动=已启动、安装但未活动=已禁用。
        for (PluginRegistry.RegisteredPlugin registered : pluginRegistry.allRegisteredPlugins()) {
            if (registered.source() == PluginSource.BUILT_IN) {
                PluginStatus base = lifecycleFailures.containsKey(registered.id())
                        ? PluginStatus.FAILED
                        : activeIds.contains(registered.id()) ? PluginStatus.STARTED : PluginStatus.DISABLED;
                observed.add(new ObservedPlugin(
                        PluginDescriptor.forBuiltIn(registered.plugin(), registered.id()), base));
            }
        }
        if (!recovery.safeToScan()) {
            PluginStatusReport builtIns = evaluator.evaluate(observed, RequiredPluginPolicy.empty());
            List<PluginDiagnostic> diagnostics = new ArrayList<>(builtIns.diagnostics());
            List<String> messages = recovery.report().failures().stream()
                    .map(PluginStatusService::recoveryFailureMessage)
                    .toList();
            if (messages.isEmpty()) {
                messages = List.of("plugin transaction recovery has not completed");
            }
            diagnostics.add(new PluginDiagnostic("plugin-runtime", PluginStatus.FAILED,
                    null, true, messages));
            return new PluginStatusReport(diagnostics);
        }
        // 外置插件：取清点结果的描述符与基线状态（不兼容条目原样保留，由评估器判 INCOMPATIBLE）。
        PluginInventory inventory = pluginInventory.get();
        for (PluginInstallation installation : inventory.installations()) {
            observed.add(new ObservedPlugin(installation.descriptor(),
                    externalBaseStatus(installation, activeIds, lifecycleFailures, crashedIds)));
        }
        Set<String> observedExternalPackages = inventory.installations().stream()
                .map(installation -> installation.descriptor().sourcePluginId())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        for (PluginDescriptor descriptor : loadedDescriptors.get().values()) {
            if (descriptor != null && observedExternalPackages.add(descriptor.sourcePluginId())) {
                observed.add(new ObservedPlugin(descriptor,
                        crashedIds.contains(descriptor.sourcePluginId())
                                ? PluginStatus.CRASHED : PluginStatus.INSTALLED));
            }
        }
        for (InstalledPlugin installed : installedArtifacts.get()) {
            if (observedExternalPackages.add(installed.id())) {
                observed.add(new ObservedPlugin(installed.descriptor(),
                        crashedIds.contains(installed.id()) ? PluginStatus.CRASHED : PluginStatus.INSTALLED));
            }
        }

        PluginStatusReport evaluated = evaluator.evaluate(observed, requiredPluginPolicy);

        // 包级加载 / 发现失败（无描述符）追加为 FAILED 诊断，使报告覆盖坏包。
        List<PluginDiagnostic> diagnostics = evaluated.diagnostics().stream()
                .map(diagnostic -> withLifecycleFailure(diagnostic, lifecycleFailures.get(diagnostic.id())))
                .collect(Collectors.toCollection(ArrayList::new));
        for (PluginLoadFailure failure : inventory.failures()) {
            diagnostics.add(new PluginDiagnostic(failure.source(), failure.status(), null,
                    requiredPluginPolicy.isRequired(failure.source()), List.of(failure.reason())));
        }
        return new PluginStatusReport(diagnostics);
    }

    public PluginRecoveryGateSnapshot recoveryGateSnapshot() {
        return recoveryGate.get();
    }

    /** 当前确实发生在插件启动 / 子上下文启动阶段的失败；普通坏包与验签失败不在此列。 */
    public Map<String, String> startupFailuresById() {
        Map<String, String> failures = new LinkedHashMap<>(currentFailuresById());
        crashedPluginIds().forEach(failures::remove);
        return Map.copyOf(failures);
    }

    private Map<String, String> currentFailuresById() {
        Map<String, String> failures = new LinkedHashMap<>(runtimeFailures.get());
        failures.putAll(pluginRegistry.lifecycleFailuresById());
        return failures;
    }

    private Set<String> crashedPluginIds() {
        Map<String, PluginRuntimePackagePhase> phases = runtimePhases.get();
        if (phases == null || phases.isEmpty()) {
            return Set.of();
        }
        return phases.entrySet().stream()
                .filter(entry -> entry.getValue() == PluginRuntimePackagePhase.CRASHED)
                .map(Map.Entry::getKey)
                .collect(Collectors.toUnmodifiableSet());
    }

    /** 当前 runtime 为各安装路径保留的最新结构化离线复验事实；不读取或解析失败原因文本。 */
    public List<PluginRuntimeVerificationSnapshot> runtimeVerificationSnapshots() {
        List<PluginRuntimeVerificationSnapshot> snapshots = runtimeVerifications.get();
        return snapshots == null ? List.of() : List.copyOf(snapshots);
    }

    private static String recoveryFailureMessage(PluginTransactionRecoveryReport.Failure failure) {
        return failure.kind() + " transaction=" + failure.transactionId()
                + " path=" + failure.transactionDirectory() + ": " + failure.detail();
    }

    private static Map<String, String> runtimeFailures(PluginRuntimeStatus status) {
        Set<String> notStarted = new LinkedHashSet<>(status.loadedPluginIds());
        notStarted.removeAll(status.startedPluginIds());
        Map<String, String> failures = new LinkedHashMap<>();
        for (PluginLoadFailure failure : status.failures()) {
            if (notStarted.contains(failure.source())) {
                failures.putIfAbsent(failure.source(), failure.reason());
            }
        }
        return Map.copyOf(failures);
    }

    private static PluginStatus externalBaseStatus(PluginInstallation installation, Set<String> activeIds,
                                                   Map<String, String> lifecycleFailures, Set<String> crashedIds) {
        if (crashedIds.contains(installation.id())) {
            return PluginStatus.CRASHED;
        }
        if (lifecycleFailures.containsKey(installation.id())) {
            return PluginStatus.FAILED;
        }
        if (installation.status() != PluginStatus.STARTED) {
            return installation.status();
        }
        // 已启动且兼容：若被开关禁用（已发现但未进入活动快照）则报 DISABLED，否则 STARTED。
        return activeIds.contains(installation.id()) ? PluginStatus.STARTED : PluginStatus.DISABLED;
    }

    private static PluginDiagnostic withLifecycleFailure(PluginDiagnostic diagnostic, String failure) {
        if (failure == null) {
            return diagnostic;
        }
        List<String> messages = new ArrayList<>();
        messages.add(failure);
        messages.addAll(diagnostic.messages());
        PluginStatus status = diagnostic.status() == PluginStatus.CRASHED
                ? PluginStatus.CRASHED : PluginStatus.FAILED;
        return new PluginDiagnostic(diagnostic.id(), status, diagnostic.descriptor(),
                diagnostic.requiredByPolicy(), messages);
    }
}
