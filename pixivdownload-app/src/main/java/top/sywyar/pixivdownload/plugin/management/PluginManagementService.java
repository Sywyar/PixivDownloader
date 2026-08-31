package top.sywyar.pixivdownload.plugin.management;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import top.sywyar.pixivdownload.plugin.PluginToggleProperties;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.runtime.status.PluginRuntimeVerificationSnapshot;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.VersionRequirement;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDependencyRef;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDescriptor;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginExecutionMode;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginLifecyclePolicy;
import top.sywyar.pixivdownload.plugin.runtime.install.ExternalPluginInstaller;
import top.sywyar.pixivdownload.plugin.runtime.install.provenance.InstalledPluginInventorySnapshot;
import top.sywyar.pixivdownload.plugin.runtime.install.provenance.InstalledPluginSnapshot;
import top.sywyar.pixivdownload.plugin.runtime.install.provenance.PluginProvenanceRecord;
import top.sywyar.pixivdownload.plugin.runtime.install.provenance.ProvenanceSnapshotState;
import top.sywyar.pixivdownload.plugin.runtime.install.trust.PluginTrustDecision;
import top.sywyar.pixivdownload.plugin.runtime.install.transaction.PluginRecoveryGateSnapshot;
import top.sywyar.pixivdownload.plugin.runtime.status.PluginDiagnostic;
import top.sywyar.pixivdownload.plugin.runtime.status.PluginStatus;
import top.sywyar.pixivdownload.plugin.runtime.status.PluginStatusReport;
import top.sywyar.pixivdownload.plugin.runtime.status.RequiredPluginPolicy;
import top.sywyar.pixivdownload.plugin.verification.PluginVerificationProjector;
import top.sywyar.pixivdownload.plugin.verification.PluginVerificationView;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import top.sywyar.pixivdownload.plugin.lifecycle.ClassifiedPluginLifecycleException;
import top.sywyar.pixivdownload.plugin.lifecycle.ExternalPluginLifecycleCoordinator;
import top.sywyar.pixivdownload.plugin.lifecycle.ExternalPluginOperation;
import top.sywyar.pixivdownload.plugin.lifecycle.ExternalPluginOperationSnapshot;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginLifecycleException;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginLifecycleService;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginRuntimePhase;
import top.sywyar.pixivdownload.plugin.recovery.RecoveryModeService;
import top.sywyar.pixivdownload.plugin.runtime.status.RecoveryModeReason;
import top.sywyar.pixivdownload.plugin.BuiltInPlugins;

/**
 * 插件管理后端服务（admin-grade、不依赖任何 UI）：在只读状态报告 {@link PluginStatusService} 之上叠加外置插件
 * 运行期生命周期 {@link PluginLifecycleService} 的当前阶段，综合为「可由后端查询 + 可由管理操作驱动」的管理视图，
 * 并把 load / start / quiesce / stop / unload / remove / restart / reload 八个运行期动词收口为带前置守卫的统一入口
 * （{@link #perform}）。它是 Web / GUI 管理入口共用的后端落点——上层不各自实现插件扫描或生命周期编排。
 *
 * <h2>读模型（{@link #list()}）</h2>
 * 覆盖内置 + 外置 + 必选但未安装的全部插件 id（来自状态报告），每条附上：来源（built-in / external / not-installed）、
 * 运行期阶段（仅受管外置插件有）、是否受管、必选性 / 是否允许停用、可用动词与诊断说明。
 *
 * <h2>运行期动词（{@link #perform}）</h2>
 * 仅作用于<b>受管外置插件</b>（{@link PluginLifecycleService#managedPluginIds()}）。前置守卫：内置插件随主程序编译、
 * <b>不可</b>运行期热启停（拒 409）；经 {@code plugins.<id>.enabled} 配置禁用而未激活的外置插件不在受管范围（拒 409）；
 * 未知 id 拒 404；必选插件（{@link RequiredPluginPolicy} 声明 {@code allowDisable=false}）<b>不允许</b>被停用类动词
 * （quiesce / stop / unload / remove）降级（拒 409）。守卫通过后委托统一生命周期编排器执行；其非法状态流转
 * （{@link PluginLifecycleException}）转 409。
 *
 * <p>本服务只读 + 编排、<b>不持久化</b>启停状态：运行期动词在内存生效、不写配置；跨重启的禁用仍由
 * {@code plugins.<id>.enabled} 配置承载，不在本服务范围内改配置。它不触碰鉴权——HTTP 入口由 {@code AuthFilter}
 * 按 {@code /api/plugins/**} = ADMIN 独立校验。
 */
@Service
public class PluginManagementService {

    private static final int MAX_MANAGEMENT_PROVENANCE_RECORDS = 512;
    private static final long MAX_MANAGEMENT_PROVENANCE_BYTES = 64L * 1024L * 1024L;

    private final PluginStatusService pluginStatusService;
    private final PluginLifecycleService pluginLifecycleService;
    private final RequiredPluginPolicy requiredPluginPolicy;
    private final RecoveryModeService recoveryModeService;
    private final PluginToggleProperties pluginToggles;
    private final ExternalPluginLifecycleCoordinator coordinator;
    private final ExternalPluginInstaller installer;

    public PluginManagementService(PluginStatusService pluginStatusService,
                                   PluginLifecycleService pluginLifecycleService,
                                   RequiredPluginPolicy requiredPluginPolicy,
                                   RecoveryModeService recoveryModeService) {
        this(pluginStatusService, pluginLifecycleService, requiredPluginPolicy, recoveryModeService,
                new PluginToggleProperties());
    }

    public PluginManagementService(PluginStatusService pluginStatusService,
                                   PluginLifecycleService pluginLifecycleService,
                                   RequiredPluginPolicy requiredPluginPolicy,
                                   RecoveryModeService recoveryModeService,
                                   PluginToggleProperties pluginToggles) {
        this.pluginStatusService = pluginStatusService;
        this.pluginLifecycleService = pluginLifecycleService;
        this.requiredPluginPolicy = requiredPluginPolicy;
        this.recoveryModeService = recoveryModeService;
        this.pluginToggles = pluginToggles;
        this.coordinator = null;
        this.installer = null;
    }

    @Autowired
    public PluginManagementService(PluginStatusService pluginStatusService,
                                   PluginLifecycleService pluginLifecycleService,
                                   RequiredPluginPolicy requiredPluginPolicy,
                                   RecoveryModeService recoveryModeService,
                                   ExternalPluginLifecycleCoordinator coordinator,
                                   ExternalPluginInstaller installer,
                                   PluginToggleProperties pluginToggles) {
        this.pluginStatusService = pluginStatusService;
        this.pluginLifecycleService = pluginLifecycleService;
        this.requiredPluginPolicy = requiredPluginPolicy;
        this.recoveryModeService = recoveryModeService;
        this.pluginToggles = pluginToggles;
        this.coordinator = coordinator;
        this.installer = installer;
    }

    /**
     * 计算当前插件管理视图：是否处于恢复模式 + 每个插件 id 的状态 / 来源 / 运行期阶段 / 是否受管 / 必选性 /
     * 可用动词 / 诊断说明。每次调用按当前状态报告与生命周期快照重新评估。
     */
    public PluginManagementReport list() {
        for (int attempt = 0; attempt < 3; attempt++) {
            long mutationBefore = lifecycleMutationEpoch();
            if ((mutationBefore & 1L) != 0L) {
                continue;
            }
            PluginRecoveryGateSnapshot gateBefore = pluginStatusService.recoveryGateSnapshot();
            try {
                PluginStatusReport status = pluginStatusService.report();
                PluginRecoveryGateSnapshot gateAfterStatus = pluginStatusService.recoveryGateSnapshot();
                if (!gateBefore.equals(gateAfterStatus)) {
                    continue;
                }
                List<PluginManagementEntry> entries = buildEntries(
                        status, gateBefore, gateBefore.safeToScan(), true);
                boolean recoveryMode = recoveryModeService.isActive();
                List<RecoveryModeReason> recoveryReasons = recoveryModeService.reasons();
                if (gateBefore.equals(pluginStatusService.recoveryGateSnapshot())
                        && mutationBefore == lifecycleMutationEpoch()) {
                    return new PluginManagementReport(recoveryMode,
                            TransactionRecoveryView.from(gateBefore), recoveryReasons, entries);
                }
            } catch (RecoveryGateChangedException ignored) {
                // gate 单调变化时丢弃混合快照，用新状态重建。
            } catch (IllegalStateException readFailure) {
                PluginRecoveryGateSnapshot gateAfterFailure = pluginStatusService.recoveryGateSnapshot();
                if (gateBefore.equals(gateAfterFailure)) {
                    throw readFailure;
                }
                // 安装器在 SAFE→BLOCKED 窗口内会拒绝继续读盘；丢弃本轮并按新 gate 重建。
            }
        }
        // 连续并发变化时不再读取或发布任何跨组件状态，等待下一次请求取得稳定 seqlock 快照。
        return new PluginManagementReport(false,
                TransactionRecoveryView.unstableLifecycleSnapshot(), List.of(), List.of());
    }

    private List<PluginManagementEntry> buildEntries(
            PluginStatusReport status,
            PluginRecoveryGateSnapshot expectedGate,
            boolean allowProvenanceReads,
            boolean allowLifecycleReads) {
        Set<String> managedIds = allowLifecycleReads
                ? pluginLifecycleService.managedPluginIds() : Set.of();
        Map<String, List<InstalledPluginSnapshot>> installedArtifacts =
                allowProvenanceReads ? installedArtifactsById() : Map.of();
        Map<String, List<PluginRuntimeVerificationSnapshot>> runtimeVerifications =
                allowProvenanceReads ? runtimeVerificationsById() : Map.of();
        List<PluginManagementEntry> entries = new ArrayList<>();
        for (PluginDiagnostic diagnostic : status.diagnostics()) {
            if ("plugin-runtime".equals(diagnostic.id()) && diagnostic.descriptor() == null) {
                continue;
            }
            entries.add(toEntry(diagnostic, managedIds, installedArtifacts, runtimeVerifications,
                    expectedGate, allowProvenanceReads, allowLifecycleReads));
        }
        return List.copyOf(entries);
    }

    private PluginManagementEntry toEntry(
            PluginDiagnostic diagnostic,
            Set<String> managedIds,
            Map<String, List<InstalledPluginSnapshot>> installedArtifacts,
            Map<String, List<PluginRuntimeVerificationSnapshot>> runtimeVerifications,
            PluginRecoveryGateSnapshot expectedGate,
            boolean allowProvenanceReads,
            boolean allowLifecycleReads) {
        String id = diagnostic.id();
        PluginDescriptor descriptor = diagnostic.descriptor();
        PluginRuntimePhase phase = allowLifecycleReads
                ? pluginLifecycleService.phase(id).orElse(null) : null;
        boolean builtIn = BuiltInPlugins.isBuiltIn(id);
        PluginLifecyclePolicy lifecyclePolicy = descriptor != null ? descriptor.lifecyclePolicy() : null;
        boolean installedOnly = descriptor != null && !builtIn
                && diagnostic.status() == PluginStatus.INSTALLED && phase == null;
        boolean managed = allowLifecycleReads && lifecyclePolicy == PluginLifecyclePolicy.HOT_RELOAD
                && (managedIds.contains(id) || phase == PluginRuntimePhase.UNLOADED
                || installedOnly);
        boolean allowDisable = !builtIn && allowDisable(id);
        boolean toggleable = descriptor != null && !builtIn && !requiredPluginPolicy.isRequired(id);
        ExternalPluginOperationSnapshot operation = allowLifecycleReads && coordinator != null
                ? coordinator.operation(id).orElse(null) : null;
        return new PluginManagementEntry(
                id,
                descriptor != null ? descriptor.displayNamespace() : null,
                descriptor != null ? descriptor.displayName() : null,
                descriptor != null ? descriptor.description() : null,
                iconTokenOf(descriptor),
                colorTokenOf(descriptor),
                descriptor != null ? descriptor.version() : null,
                descriptor != null ? descriptor.kind() : null,
                descriptor != null ? SdkRequirementView.from(descriptor.requires()) : null,
                dependencyViews(descriptor),
                sourceOf(id, descriptor),
                diagnostic.status(),
                phase,
                managed,
                diagnostic.requiredByPolicy(),
                allowDisable,
                availableActions(managed, phase, allowDisable, installedOnly),
                List.copyOf(diagnostic.messages()),
                verificationOf(id, descriptor, phase, installedArtifacts, runtimeVerifications,
                        expectedGate, allowProvenanceReads, allowLifecycleReads),
                trustOf(id, descriptor, installedArtifacts, allowProvenanceReads, allowLifecycleReads),
                allowLifecycleReads ? pluginLifecycleService.generation(id).orElse(null) : null,
                operation != null ? operation.operation() : ExternalPluginOperation.IDLE,
                operation != null ? operation.transactionId() : null,
                operation != null ? operation.diagnostic() : null,
                descriptor != null ? descriptor.executionMode() : null,
                lifecyclePolicy,
                pluginToggles.isEnabled(id),
                toggleable);
    }

    public PluginTrustView approveTrust(String pluginId, String confirmedArtifactSha256) {
        if (installer == null) {
            throw trustFailure(pluginId, "approve-trust", "plugin trust store is unavailable");
        }
        try {
            return PluginTrustView.from(installer.approveTrust(pluginId, confirmedArtifactSha256));
        } catch (IllegalArgumentException | IllegalStateException failure) {
            throw trustFailure(pluginId, "approve-trust", failure.getMessage());
        }
    }

    public PluginTrustView revokeTrust(String pluginId) {
        if (installer == null) {
            throw trustFailure(pluginId, "revoke-trust", "plugin trust store is unavailable");
        }
        try {
            return PluginTrustView.from(installer.revokeTrust(pluginId));
        } catch (IllegalArgumentException | IllegalStateException failure) {
            throw trustFailure(pluginId, "revoke-trust", failure.getMessage());
        }
    }

    private static PluginManagementException trustFailure(String pluginId, String action, String detail) {
        return new PluginManagementException(
                PluginManagementErrorCode.TRUST_UPDATE_REJECTED, pluginId, action, null, detail);
    }

    private PluginTrustView trustOf(
            String id,
            PluginDescriptor descriptor,
            Map<String, List<InstalledPluginSnapshot>> installedArtifacts,
            boolean allowProvenanceReads,
            boolean allowLifecycleReads) {
        if (descriptor == null) {
            return PluginTrustView.state(PluginTrustState.NOT_INSTALLED);
        }
        if (BuiltInPlugins.isBuiltIn(id)) {
            return PluginTrustView.state(PluginTrustState.BUILT_IN);
        }
        if (!allowLifecycleReads || !allowProvenanceReads || installer == null) {
            return PluginTrustView.state(PluginTrustState.INVALID);
        }
        if (pluginLifecycleService.isDevelopmentArtifact(id)) {
            return PluginTrustView.state(PluginTrustState.DEVELOPMENT);
        }
        List<InstalledPluginSnapshot> installed = installedArtifacts.getOrDefault(id, List.of());
        if (installed.size() != 1) {
            return PluginTrustView.state(PluginTrustState.INVALID);
        }
        InstalledPluginSnapshot snapshot = installed.get(0);
        if (snapshot.provenanceState() != ProvenanceSnapshotState.PRESENT
                || snapshot.provenance() == null
                || !snapshot.artifactSha256().equals(snapshot.provenance().artifactSha256())) {
            return PluginTrustView.state(PluginTrustState.INVALID);
        }
        return PluginTrustView.from(snapshot.provenance());
    }

    /** 描述符的插件间依赖声明投影（未安装的必选项无描述符 → 空列表）。 */
    private static List<PluginDependencyView> dependencyViews(PluginDescriptor descriptor) {
        if (descriptor == null) {
            return List.of();
        }
        return descriptor.dependencies().stream().map(PluginDependencyView::from).toList();
    }

    private static String sourceOf(String id, PluginDescriptor descriptor) {
        if (descriptor == null) {
            return "not-installed"; // 必选策略要求但未安装的 id：只有要求、没有描述符
        }
        return BuiltInPlugins.isBuiltIn(id) ? "built-in" : "external";
    }

    private PluginVerificationView verificationOf(
            String id,
            PluginDescriptor descriptor,
            PluginRuntimePhase phase,
            Map<String, List<InstalledPluginSnapshot>> installedArtifacts,
            Map<String, List<PluginRuntimeVerificationSnapshot>> runtimeVerifications,
            PluginRecoveryGateSnapshot expectedGate,
            boolean allowProvenanceReads,
            boolean allowLifecycleReads) {
        if (descriptor == null) {
            return PluginVerificationProjector.notInstalled();
        }
        if (BuiltInPlugins.isBuiltIn(id)) {
            return PluginVerificationProjector.builtInOfficial();
        }
        if (!allowLifecycleReads) {
            return PluginVerificationProjector.invalidProvenance();
        }
        Optional<Path> runtimePath = pluginLifecycleService.artifactPath(id)
                .map(path -> path.toAbsolutePath().normalize());
        if (runtimePath.isPresent() && pluginLifecycleService.isDevelopmentArtifact(id)) {
            return PluginVerificationProjector.unverifiedLocal();
        }
        if (installer == null || !allowProvenanceReads) {
            return PluginVerificationProjector.invalidProvenance();
        }
        List<InstalledPluginSnapshot> installed =
                installedArtifacts.getOrDefault(id, List.of());
        if (installed.size() > 1) {
            return PluginVerificationProjector.invalidProvenance();
        }
        if (installed.isEmpty()) {
            return PluginVerificationProjector.invalidProvenance();
        }
        InstalledPluginSnapshot snapshot = installed.get(0);
        Path snapshotPath = snapshot.plugin().path().toAbsolutePath().normalize();
        boolean runtimeArtifactRequired = phase != null && phase != PluginRuntimePhase.UNLOADED;
        if (runtimeArtifactRequired && runtimePath.isEmpty()
                || runtimePath.isPresent() && !runtimePath.get().equals(snapshotPath)
                || !descriptor.version().equals(snapshot.plugin().version())) {
            return PluginVerificationProjector.invalidProvenance();
        }
        requireStableRecoveryGate(expectedGate);
        List<PluginRuntimeVerificationSnapshot> currentRuntimeVerifications =
                runtimeVerifications.getOrDefault(id, List.of()).stream()
                        .filter(candidate -> candidate.binds(
                                snapshotPath,
                                id,
                                snapshot.plugin().version(),
                                snapshot.artifactSizeBytes(),
                                snapshot.artifactSha256()))
                        .toList();
        if (currentRuntimeVerifications.size() > 1) {
            return PluginVerificationProjector.invalidProvenance();
        }
        if (currentRuntimeVerifications.size() == 1) {
            PluginRuntimeVerificationSnapshot runtimeVerification = currentRuntimeVerifications.get(0);
            boolean currentProvenance = switch (snapshot.provenanceState()) {
                case PRESENT -> runtimeVerification.matchesProvenance(snapshot.provenance());
                case ABSENT -> false;
                case INVALID, BUDGET_EXHAUSTED -> false;
            };
            if (!currentProvenance) {
                return PluginVerificationProjector.invalidProvenance();
            }
            return PluginVerificationProjector.fromRuntimeVerification(runtimeVerification);
        }
        return switch (snapshot.provenanceState()) {
            case PRESENT -> snapshot.provenance().artifactSizeBytes() == snapshot.artifactSizeBytes()
                    && snapshot.provenance().artifactSha256().equals(snapshot.artifactSha256())
                    ? PluginVerificationProjector.fromProvenance(snapshot.provenance())
                    : PluginVerificationProjector.invalidProvenance();
            case ABSENT -> PluginVerificationProjector.missingProvenance();
            case INVALID, BUDGET_EXHAUSTED -> PluginVerificationProjector.invalidProvenance();
        };
    }

    private Map<String, List<InstalledPluginSnapshot>> installedArtifactsById() {
        if (installer == null) {
            return Map.of();
        }
        InstalledPluginInventorySnapshot inventory =
                installer.snapshotInstalledWithProvenance(
                        MAX_MANAGEMENT_PROVENANCE_RECORDS, MAX_MANAGEMENT_PROVENANCE_BYTES);
        Map<String, List<InstalledPluginSnapshot>> grouped = new LinkedHashMap<>();
        for (InstalledPluginSnapshot entry : inventory.entries()) {
            grouped.computeIfAbsent(entry.plugin().id(), ignored -> new ArrayList<>()).add(entry);
        }
        grouped.replaceAll((ignored, paths) -> List.copyOf(paths));
        return Map.copyOf(grouped);
    }

    private Map<String, List<PluginRuntimeVerificationSnapshot>> runtimeVerificationsById() {
        Map<String, List<PluginRuntimeVerificationSnapshot>> grouped = new LinkedHashMap<>();
        for (PluginRuntimeVerificationSnapshot snapshot : pluginStatusService.runtimeVerificationSnapshots()) {
            grouped.computeIfAbsent(snapshot.pluginId(), ignored -> new ArrayList<>()).add(snapshot);
        }
        grouped.replaceAll((ignored, snapshots) -> List.copyOf(snapshots));
        return Map.copyOf(grouped);
    }

    private void requireStableRecoveryGate(PluginRecoveryGateSnapshot expectedGate) {
        if (!expectedGate.equals(pluginStatusService.recoveryGateSnapshot())) {
            throw RecoveryGateChangedException.INSTANCE;
        }
    }

    private long lifecycleMutationEpoch() {
        return coordinator != null ? coordinator.lifecycleMutationEpoch() : 0L;
    }

    private static final class RecoveryGateChangedException extends RuntimeException {
        private static final RecoveryGateChangedException INSTANCE = new RecoveryGateChangedException();

        private RecoveryGateChangedException() {
            super(null, null, false, false);
        }
    }

    /**
     * 展示图标受控 token：取描述符声明的 token；无描述符（未安装的必选项）或包级描述符无 token 时回退到 plugin-api
     * 默认 token（{@link PixivFeaturePlugin#DEFAULT_ICON_KEY}），使每个条目恒有稳定 token（前端再按本地白名单渲染）。
     */
    private static String iconTokenOf(PluginDescriptor descriptor) {
        return descriptor != null && descriptor.iconKey() != null
                ? descriptor.iconKey() : PixivFeaturePlugin.DEFAULT_ICON_KEY;
    }

    /** 卡片强调色受控 token：语义同 {@link #iconTokenOf}，缺省回退到 {@link PixivFeaturePlugin#DEFAULT_COLOR_TOKEN}。 */
    private static String colorTokenOf(PluginDescriptor descriptor) {
        return descriptor != null && descriptor.colorToken() != null
                ? descriptor.colorToken() : PixivFeaturePlugin.DEFAULT_COLOR_TOKEN;
    }

    /**
     * 某插件当前可用的运行期动词（建议性，供管理入口呈现；最终正确性以 {@link #perform} 的守卫与
     * {@link PluginLifecycleService} 的流转校验为准）。不受管（内置 / 未激活外置 / 未安装）无运行期动词；
     * 受管外置插件按当前阶段给出启用类（恢复 / 重建足迹，必选插件也可用）与停用类（降级，必选插件不提供）动词。
     */
    private static List<String> availableActions(boolean managed, PluginRuntimePhase phase, boolean allowDisable,
                                                 boolean installedOnly) {
        if (!managed) {
            return List.of();
        }
        List<String> actions = new ArrayList<>();
        if (installedOnly) {
            actions.add(LifecycleAction.LOAD.token());
            if (allowDisable) {
                actions.add(LifecycleAction.REMOVE.token());
            }
            return List.copyOf(actions);
        }
        if (phase == null) {
            return List.of();
        }
        if (phase == PluginRuntimePhase.STOPPED || phase == PluginRuntimePhase.LOADED) {
            actions.add(LifecycleAction.START.token());
        }
        if (phase == PluginRuntimePhase.UNLOADED) {
            actions.add(LifecycleAction.LOAD.token());
        }
        if (phase != PluginRuntimePhase.UNLOADED) {
            actions.add(LifecycleAction.RESTART.token());
            actions.add(LifecycleAction.RELOAD.token());
        }
        if (allowDisable) {
            if (phase == PluginRuntimePhase.STARTED) {
                actions.add(LifecycleAction.QUIESCE.token());
            }
            if (phase == PluginRuntimePhase.STARTED || phase == PluginRuntimePhase.QUIESCED) {
                actions.add(LifecycleAction.STOP.token());
            }
            if (phase != PluginRuntimePhase.UNLOADED) {
                actions.add(LifecycleAction.UNLOAD.token());
            }
            actions.add(LifecycleAction.REMOVE.token());
        }
        return List.copyOf(actions);
    }

    /**
     * 执行一个运行期生命周期动词。前置守卫（受管 / 内置 / 未激活 / 未知 / 必选不可停用）不满足即抛
     * {@link PluginManagementException}；委托 {@link PluginLifecycleService} 时其非法流转
     * （{@link PluginLifecycleException}）转为 409。成功返回动词执行后的运行期阶段。
     */
    public PluginActionResult perform(String id, LifecycleAction action) {
        requireManaged(id, action);
        if (action.isDisabling() && !allowDisable(id)) {
            throw new PluginManagementException(PluginManagementErrorCode.REQUIRED_PLUGIN, id, action.token(),
                    pluginLifecycleService.phase(id).orElse(null),
                    "Required plugin cannot be disabled: " + id);
        }
        if (action.isEnabling()) {
            requireSatisfiedDependencies(id, action);
        }
        try {
            if (coordinator != null) {
                action.apply(coordinator, id);
            } else {
                action.apply(pluginLifecycleService, id);
            }
        } catch (PluginLifecycleException e) {
            PluginManagementErrorCode code = e instanceof ClassifiedPluginLifecycleException classified
                    ? classified.code() : PluginManagementErrorCode.ILLEGAL_TRANSITION;
            throw new PluginManagementException(code, id, action.token(),
                    pluginLifecycleService.phase(id).orElse(null), e.getMessage());
        }
        return new PluginActionResult(id, action.token(), pluginLifecycleService.phase(id).orElse(null));
    }

    /** 校验 id 是受管外置插件；否则按「内置 / 未激活外置 / 未知」分别给出明确拒绝（附尝试的动词 token 供诊断）。 */
    private void requireManaged(String id, LifecycleAction action) {
        if (BuiltInPlugins.isBuiltIn(id)) {
            throw new PluginManagementException(PluginManagementErrorCode.BUILT_IN_PLUGIN, id, action.token(), null,
                    "Built-in plugin cannot be hot-managed at runtime: " + id);
        }
        var report = pluginStatusService.report();
        var diagnostic = report != null ? report.byId(id) : Optional.<PluginDiagnostic>empty();
        PluginDescriptor descriptor = diagnostic.map(PluginDiagnostic::descriptor).orElse(null);
        if (descriptor != null && descriptor.lifecyclePolicy() != PluginLifecyclePolicy.HOT_RELOAD) {
            throw new PluginManagementException(PluginManagementErrorCode.RESTART_REQUIRED_PLUGIN,
                    id, action.token(), null,
                    "Plugin lifecycle policy does not allow hot management: "
                            + descriptor.lifecyclePolicy().token());
        }
        if (pluginLifecycleService.managedPluginIds().contains(id)) {
            return;
        }
        if (pluginLifecycleService.phase(id).orElse(null) == PluginRuntimePhase.UNLOADED) {
            return;
        }
        if (diagnostic.isPresent()
                && diagnostic.get().descriptor() != null
                && diagnostic.get().status() == PluginStatus.INSTALLED
                && (action == LifecycleAction.LOAD || action == LifecycleAction.REMOVE)) {
            return;
        }
        if (diagnostic.isPresent()) {
            throw new PluginManagementException(PluginManagementErrorCode.INACTIVE_PLUGIN, id, action.token(), null,
                    "External plugin is not currently active (disabled via config); runtime actions unavailable: " + id);
        }
        throw new PluginManagementException(PluginManagementErrorCode.UNKNOWN_PLUGIN, id, action.token(), null,
                "Unknown plugin: " + id);
    }

    private void requireSatisfiedDependencies(String id, LifecycleAction action) {
        var report = pluginStatusService.report();
        if (report == null) {
            return;
        }
        PluginDiagnostic target = report.byId(id).orElse(null);
        if (target == null || target.descriptor() == null) {
            return;
        }
        for (PluginDependencyRef dependency : target.descriptor().dependencies()) {
            if (dependency.optional()) {
                continue;
            }
            PluginDiagnostic depended = report.byId(dependency.pluginId()).orElse(null);
            if (depended == null || depended.descriptor() == null) {
                throw dependencyUnsatisfied(id, action, "missing required dependency: " + dependency.pluginId());
            }
            VersionRequirement required = dependency.requirement();
            VersionRequirement actual = VersionRequirement.parse(depended.descriptor().version());
            if (!required.isSatisfiedBy(actual.major(), actual.minor())) {
                throw dependencyUnsatisfied(id, action,
                        "required dependency " + dependency.pluginId() + " needs version "
                                + required.display() + ", but installed version is "
                                + depended.descriptor().version());
            }
            if (depended.status() != PluginStatus.STARTED) {
                throw dependencyUnsatisfied(id, action,
                        "required dependency " + dependency.pluginId()
                                + " is not available (status " + depended.status() + ")");
            }
        }
    }

    private PluginManagementException dependencyUnsatisfied(String id, LifecycleAction action, String detail) {
        return new PluginManagementException(PluginManagementErrorCode.DEPENDENCY_UNSATISFIED,
                id, action.token(), pluginLifecycleService.phase(id).orElse(null), detail);
    }

    private boolean allowDisable(String id) {
        return requiredPluginPolicy.requirement(id)
                .map(RequiredPluginPolicy.RequiredPlugin::allowDisable)
                .orElse(true);
    }

    /** 运行期生命周期动词（与 {@link PluginLifecycleService} 的核心内部 API 一一对应）。 */
    public enum LifecycleAction {
        LOAD("load", false),
        START("start", false),
        QUIESCE("quiesce", true),
        STOP("stop", true),
        UNLOAD("unload", true),
        REMOVE("remove", true),
        RESTART("restart", false),
        RELOAD("reload", false);

        private final String token;
        private final boolean disabling;

        LifecycleAction(String token, boolean disabling) {
            this.token = token;
            this.disabling = disabling;
        }

        /** 动词在 URL / 响应里的稳定标记（小写）。 */
        public String token() {
            return token;
        }

        /** 是否为停用 / 降级类动词（会让插件离开 {@link PluginRuntimePhase#STARTED}）：必选插件不允许。 */
        public boolean isDisabling() {
            return disabling;
        }

        /** 是否会让插件进入或恢复可服务状态，必须先满足非可选依赖。 */
        public boolean isEnabling() {
            return !disabling;
        }

        void apply(PluginLifecycleService service, String id) {
            switch (this) {
                case LOAD -> service.load(id);
                case START -> service.start(id);
                case QUIESCE -> service.quiesce(id);
                case STOP -> service.stop(id);
                case UNLOAD -> service.unload(id);
                case REMOVE -> throw new PluginLifecycleException("remove requires the external lifecycle coordinator");
                case RESTART -> service.restart(id);
                case RELOAD -> service.reload(id);
            }
        }

        void apply(ExternalPluginLifecycleCoordinator coordinator, String id) {
            switch (this) {
                case LOAD -> coordinator.load(id);
                case START -> coordinator.start(id);
                case QUIESCE -> coordinator.quiesce(id);
                case STOP -> coordinator.stop(id);
                case UNLOAD -> coordinator.unload(id);
                case REMOVE -> coordinator.remove(id);
                case RESTART -> coordinator.restart(id);
                case RELOAD -> coordinator.reload(id);
            }
        }
    }

    /**
     * 插件管理视图（对外）。
     *
     * @param recoveryMode 核心壳当前是否处于恢复模式（存在未满足的必选插件或插件启动失败）
     * @param transactionRecovery 插件事务恢复准入状态与结构化失败；不触发磁盘扫描
     * @param recoveryReasons 触发恢复模式的结构化原因
     * @param plugins      各插件状态条目（按状态报告评估顺序）
     */
    public record PluginManagementReport(
            boolean recoveryMode,
            TransactionRecoveryView transactionRecovery,
            List<RecoveryModeReason> recoveryReasons,
            List<PluginManagementEntry> plugins) {
    }

    public record TransactionRecoveryView(
            String state,
            boolean safeToScan,
            List<TransactionRecoveryFailureView> failures) {

        private static TransactionRecoveryView from(PluginRecoveryGateSnapshot snapshot) {
            return new TransactionRecoveryView(snapshot.state().name(), snapshot.safeToScan(),
                    snapshot.report().failures().stream()
                            .map(failure -> new TransactionRecoveryFailureView(
                                    failure.transactionId(),
                                    failure.transactionDirectory().toString(),
                                    failure.kind().name(),
                                    failure.detail()))
                            .toList());
        }

        private static TransactionRecoveryView unstableLifecycleSnapshot() {
            return new TransactionRecoveryView("UNCHECKED", false, List.of());
        }
    }

    public record TransactionRecoveryFailureView(
            String transactionId,
            String transactionDirectory,
            String kind,
            String detail) {
    }

    /**
     * 单个插件管理条目（对外）。{@code displayNameKey} / {@code descriptionKey} 是<b>纯</b> i18n key、
     * {@code displayNamespace} 是其所在 namespace（前端在该 namespace 按当前语言解析、不在后端 bake 文案）；
     * {@code iconKey} / {@code colorToken} 是<b>受控展示 token</b>（不是 URL / CSS / 远程资源，前端按共享 token 映射、
     * 未知值回退默认），仅供本地卡片展示、非插件市场字段；{@code messages} 是评估器给出的诊断说明（自由文本、供管理诊断）。
     *
     * @param id               插件 id
     * @param displayNamespace 展示名称 / 简介所在 i18n namespace（前端在此 namespace 解析 {@code displayNameKey} / {@code descriptionKey}；未安装的必选项 / 无 namespace 时为 {@code null}）
     * @param displayNameKey   展示名称 i18n key（<b>纯 key</b>；未安装的必选项为 {@code null}）
     * @param descriptionKey   简介 i18n key（<b>纯 key</b>，在 {@code displayNamespace} 内解析；未安装 / 无简介时为 {@code null}，前端优雅回退）
     * @param iconKey          展示图标受控 token（恒非空：缺省回退到 plugin-api 默认 token，前端按本地白名单渲染）
     * @param colorToken       卡片强调色受控 token（恒非空：缺省回退到 plugin-api 默认 token，前端映射到固定 CSS class）
     * @param version          插件版本（未安装的必选项为 {@code null}）
     * @param kind             插件类别（未安装的必选项为 {@code null}）
     * @param sdkRequirement   对SDK 的版本要求投影（未安装的必选项无描述符时为 {@code null}）
     * @param dependencies     对其它插件的依赖声明投影（无描述符 / 无依赖时为空列表）
     * @param source           来源：{@code built-in} / {@code external} / {@code not-installed}
     * @param status           评估状态
     * @param runtimePhase     运行期阶段（仅受管外置插件有，否则 {@code null}）
     * @param managed          是否受运行期生命周期管理（可施加运行期动词）
     * @param requiredByPolicy 是否被必选策略声明为必选
     * @param allowDisable     是否允许被停用（必选且不可停用时为 {@code false}）
     * @param availableActions 当前建议可用的运行期动词（建议性）
     * @param messages         诊断说明
     * @param verification     验签状态投影（前端只消费本字段，不自行推断可信来源）
     * @param executionMode    插件代码执行隔离级别（无描述符时为 {@code null}）
     * @param lifecyclePolicy  描述符声明的生命周期策略（无描述符时为 {@code null}）
     * @param configuredEnabled 当前配置中的期望启用态（缺项默认 {@code true}）
     * @param toggleable       是否允许管理入口修改期望启用态（内置 / 必选 / 无描述符均为 {@code false}）
     */
    public record PluginManagementEntry(
            String id,
            String displayNamespace,
            String displayNameKey,
            String descriptionKey,
            String iconKey,
            String colorToken,
            String version,
            PluginKind kind,
            SdkRequirementView sdkRequirement,
            List<PluginDependencyView> dependencies,
            String source,
            PluginStatus status,
            PluginRuntimePhase runtimePhase,
            boolean managed,
            boolean requiredByPolicy,
            boolean allowDisable,
            List<String> availableActions,
            List<String> messages,
            PluginVerificationView verification,
            PluginTrustView trust,
            Long generation,
            ExternalPluginOperation operation,
            String transactionId,
            String operationDiagnostic,
            PluginExecutionMode executionMode,
            PluginLifecyclePolicy lifecyclePolicy,
            boolean configuredEnabled,
            boolean toggleable) {

        /** 兼容不关心运行操作元数据的调用方与测试夹具。 */
        public PluginManagementEntry(
                String id,
                String displayNamespace,
                String displayNameKey,
                String descriptionKey,
                String iconKey,
                String colorToken,
                String version,
                PluginKind kind,
                SdkRequirementView sdkRequirement,
                List<PluginDependencyView> dependencies,
                String source,
                PluginStatus status,
                PluginRuntimePhase runtimePhase,
                boolean managed,
                boolean requiredByPolicy,
                boolean allowDisable,
                List<String> availableActions,
                List<String> messages) {
            this(id, displayNamespace, displayNameKey, descriptionKey, iconKey, colorToken, version, kind,
                    sdkRequirement, dependencies, source, status, runtimePhase, managed, requiredByPolicy,
                    allowDisable, availableActions, messages, PluginVerificationProjector.unverifiedLocal(),
                    PluginTrustView.state(PluginTrustState.INVALID), null,
                    ExternalPluginOperation.IDLE, null, null,
                    PluginExecutionMode.HOST_PROCESS_FULL_TRUST,
                    PluginLifecyclePolicy.HOT_RELOAD, true, false);
        }

        /** 兼容需要显式断言启用配置与生命周期策略、但不关心运行操作元数据的调用方。 */
        public PluginManagementEntry(
                String id,
                String displayNamespace,
                String displayNameKey,
                String descriptionKey,
                String iconKey,
                String colorToken,
                String version,
                PluginKind kind,
                SdkRequirementView sdkRequirement,
                List<PluginDependencyView> dependencies,
                String source,
                PluginStatus status,
                PluginRuntimePhase runtimePhase,
                boolean managed,
                boolean requiredByPolicy,
                boolean allowDisable,
                List<String> availableActions,
                List<String> messages,
                PluginLifecyclePolicy lifecyclePolicy,
                boolean configuredEnabled,
                boolean toggleable) {
            this(id, displayNamespace, displayNameKey, descriptionKey, iconKey, colorToken, version, kind,
                    sdkRequirement, dependencies, source, status, runtimePhase, managed, requiredByPolicy,
                    allowDisable, availableActions, messages, PluginVerificationProjector.unverifiedLocal(),
                    PluginTrustView.state(PluginTrustState.INVALID), null,
                    ExternalPluginOperation.IDLE, null, null,
                    PluginExecutionMode.HOST_PROCESS_FULL_TRUST,
                    lifecyclePolicy, configuredEnabled, toggleable);
        }
    }

    public enum PluginTrustState {
        NOT_INSTALLED,
        BUILT_IN,
        DEVELOPMENT,
        OFFICIAL,
        APPROVED,
        CONFIRMATION_REQUIRED,
        REVOKED,
        INVALID
    }

    public record PluginTrustView(
            PluginTrustState state,
            String artifactSha256,
            String publisherKeyFingerprint,
            PluginTrustDecision.ApprovalType approvalType,
            Instant approvedAt,
            Instant revokedAt,
            boolean approvable,
            boolean revocable) {

        private static PluginTrustView state(PluginTrustState state) {
            return new PluginTrustView(state, null, null, null, null, null, false, false);
        }

        private static PluginTrustView from(PluginProvenanceRecord provenance) {
            PluginTrustDecision decision = provenance.trustDecision();
            PluginTrustState state;
            if (provenance.developmentOnly()) {
                state = PluginTrustState.DEVELOPMENT;
            } else if (provenance.trustRevokedAt() != null) {
                state = PluginTrustState.REVOKED;
            } else if (decision != null) {
                state = decision.approvalType() == PluginTrustDecision.ApprovalType.OFFICIAL
                        ? PluginTrustState.OFFICIAL : PluginTrustState.APPROVED;
            } else {
                state = provenance.officialRepository()
                        ? PluginTrustState.OFFICIAL : PluginTrustState.CONFIRMATION_REQUIRED;
            }
            boolean production = !provenance.developmentOnly();
            return new PluginTrustView(
                    state,
                    provenance.artifactSha256(),
                    provenance.publisherKeyFingerprint(),
                    decision != null ? decision.approvalType() : null,
                    decision != null ? decision.approvedAt() : null,
                    provenance.trustRevokedAt(),
                    production && (state == PluginTrustState.REVOKED
                            || state == PluginTrustState.CONFIRMATION_REQUIRED),
                    production && (decision != null || provenance.officialRepository()));
        }
    }

    /**
     * 插件对SDK 的版本要求投影（对外）：从 {@link PluginDescriptor#requires()} 映射，不泄露内部描述符模型。
     *
     * @param specified 是否声明了 {@code requires}（未声明视为兼容任何版本）
     * @param satisfied 当前SDK 是否满足该要求（未声明恒为 {@code true}，无法解析恒为 {@code false}）
     * @param required  人类可读的版本要求（未声明为 {@code "(unspecified)"}，无法解析时回显原始串）
     */
    public record SdkRequirementView(boolean specified, boolean satisfied, String required) {

        static SdkRequirementView from(VersionRequirement requirement) {
            return new SdkRequirementView(
                    requirement.present(), requirement.isSatisfiedByCurrentSdk(), requirement.display());
        }
    }

    /**
     * 插件对另一个插件的依赖声明投影（对外）：从 {@link PluginDependencyRef} 映射，不泄露内部描述符模型。
     *
     * @param pluginId       被依赖插件 id
     * @param versionSupport 版本要求声明（{@code *} / 空表示不限版本）
     * @param optional       是否为可选依赖（缺失不阻止依赖方启动）
     */
    public record PluginDependencyView(String pluginId, String versionSupport, boolean optional) {

        public static PluginDependencyView from(PluginDependencyRef dependency) {
            return new PluginDependencyView(
                    dependency.pluginId(), dependency.versionSupport(), dependency.optional());
        }
    }

    /**
     * 运行期动词执行结果（对外）。
     *
     * @param id     插件 id
     * @param action 执行的动词标记
     * @param phase  执行后的运行期阶段（{@code null} 表示未受管，理论上不会出现在成功路径）
     */
    public record PluginActionResult(String id, String action, PluginRuntimePhase phase) {
    }
}
