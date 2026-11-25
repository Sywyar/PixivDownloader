package top.sywyar.pixivdownload.plugin.lifecycle;

import static top.sywyar.pixivdownload.plugin.runtime.lifecycle.PluginLifecycleFailures.addSuppressedSafely;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.context.ConfigurableApplicationContext;
import top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeManager;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDescriptor;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginLifecyclePolicy;
import top.sywyar.pixivdownload.plugin.runtime.lifecycle.UnloadedPluginPackage;
import top.sywyar.pixivdownload.plugin.runtime.install.ExternalPluginInstaller;
import top.sywyar.pixivdownload.plugin.runtime.install.model.InstalledPlugin;
import top.sywyar.pixivdownload.plugin.runtime.install.provenance.InstalledPluginSnapshot;
import top.sywyar.pixivdownload.plugin.runtime.install.provenance.PluginProvenanceRecord;
import top.sywyar.pixivdownload.plugin.runtime.install.transaction.PreparedPluginTransaction;
import top.sywyar.pixivdownload.plugin.runtime.install.transaction.CommittedPluginTransaction;
import top.sywyar.pixivdownload.plugin.runtime.install.transaction.PluginRemovalAttempt;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginInstallOutcome;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginInstallResult;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageOrigin;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import top.sywyar.pixivdownload.plugin.install.PluginActivationResult;
import top.sywyar.pixivdownload.plugin.install.PluginDependencyProblem;
import top.sywyar.pixivdownload.plugin.install.PluginDependencyResolver;
import top.sywyar.pixivdownload.plugin.management.PluginManagementErrorCode;
import top.sywyar.pixivdownload.plugin.recovery.RecoveryModeService;

/**
 * 外置插件全部运行期写动作的唯一编排入口。可即时生效的替换依次清退应用足迹、执行 PF4J 物理生命周期和代际替换；
 * process-restart 安装与移除保留当前实例至进程退出。packageId 锁阻止同包动作交错。
 */
@Service
public class ExternalPluginLifecycleCoordinator {
    private final PluginLifecycleService lifecycleService;

    private static final Logger log = LoggerFactory.getLogger(ExternalPluginLifecycleCoordinator.class);

    private final PluginRuntimeManager runtimeManager;
    private final ExternalPluginRuntimeController runtimeController;
    private final ExternalPluginInstaller installer;
    private final RecoveryModeService recoveryModeService;
    private final PluginDependencyResolver dependencyResolver;
    /**
     * 插件生命周期写事务的进程内总预约。安装必须在发布可恢复事务前取得，并持有到提交或回滚结束；
     * 其它启停、卸载和删除动作走同一预约，避免依赖或旧 artifact 在检查与激活之间变化。
     */
    private final ReentrantLock lifecycleMutationLock = new ReentrantLock();
    private volatile boolean backendStopFailed;
    private boolean backendClosing; // 由 lifecycleMutationLock 保护
    /** 偶数表示稳定快照，奇数表示有生命周期写事务；管理读模型用作跨组件 seqlock。 */
    private final AtomicLong lifecycleMutationEpoch = new AtomicLong();
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final Map<String, ExternalPluginOperationSnapshot> operations = new ConcurrentHashMap<>();

    public ExternalPluginLifecycleCoordinator(PluginRuntimeManager runtimeManager,
                                              PluginLifecycleService lifecycleService,
                                              ExternalPluginInstaller installer,
                                              RecoveryModeService recoveryModeService,
                                              PluginDependencyResolver dependencyResolver) {
        this.runtimeManager = runtimeManager;
        this.installer = installer;
        this.lifecycleService = lifecycleService;
        this.recoveryModeService = recoveryModeService;
        this.dependencyResolver = dependencyResolver;
        this.runtimeController = new ExternalPluginRuntimeController(
                runtimeManager,
                lifecycleService,
                installer,
                recoveryModeService);
    }

    public void start(String packageId) {
        withHotManagement(packageId, ExternalPluginOperation.INSTALLING, () -> {
            requireActivationDependencies(packageId, dependencyResolver.activationDescriptor(packageId));
            runtimeController.start(packageId);
            runtimeManager.confirmDeferredStart(packageId);
            recoveryModeService.refresh();
            return null;
        });
    }

    public void quiesce(String packageId) {
        withHotManagement(packageId, ExternalPluginOperation.REMOVING, () -> {
            runtimeController.quiesce(packageId);
            return null;
        });
    }

    public void stop(String packageId) {
        withHotManagement(packageId, ExternalPluginOperation.REMOVING, () -> {
            runtimeController.stop(packageId);
            return null;
        });
    }

    public UnloadedPluginPackage unload(String packageId) {
        return withHotManagement(packageId, ExternalPluginOperation.REMOVING, () -> {
            UnloadedPluginPackage unloaded = runtimeController.unload(packageId);
            recoveryModeService.refresh();
            return unloaded;
        });
    }

    public void load(String packageId) {
        withHotManagement(packageId, ExternalPluginOperation.INSTALLING, () -> {
            requireActivationDependencies(packageId, descriptorForCodeLoad(packageId));
            runtimeController.load(packageId, runtimeController.installedArtifact(packageId));
            return null;
        });
    }

    /** 服务重启：保留 generation/classloader。 */
    public void restart(String packageId) {
        withHotManagement(packageId, ExternalPluginOperation.UPDATING, () -> {
            requireActivationDependencies(packageId, dependencyResolver.activationDescriptor(packageId));
            try {
                runtimeController.stop(packageId);
                runtimeController.start(packageId);
            } catch (Throwable failure) {
                PluginLifecycleFailureAccumulator failures = new PluginLifecycleFailureAccumulator(failure);
                refreshRecoveryModeSafely(failures);
                throw failures.propagate("failed to restart plugin '" + packageId + "'");
            }
            runtimeManager.confirmDeferredStart(packageId);
            recoveryModeService.refresh();
            return null;
        });
    }

    /** 代码重载：物理 unload/load，必须产生新 generation。失败时恢复重载前的运行工件。 */
    public void reload(String packageId) {
        withHotManagement(packageId, ExternalPluginOperation.UPDATING, () -> {
            boolean pendingInstall = runtimeManager.hasPendingInstall(packageId);
            requireActivationDependencies(packageId, descriptorForCodeLoad(packageId, pendingInstall));
            runtimeController.requireNoActiveDependents(packageId);
            Path artifact = pendingInstall ? runtimeController.installedArtifact(packageId)
                    : runtimeController.artifactPath(packageId).orElseGet(() -> runtimeController.installedArtifact(packageId));
            long previousGeneration = runtimeController.generation(packageId).orElse(0L);
            var previousVersion = runtimeManager.loadedDescriptor(packageId).map(PluginDescriptor::version).orElse(null);
            try (var previous = retainRuntime(packageId, runtimeController.artifactPath(packageId).orElse(artifact))) {
                try {
                    runtimeController.unload(packageId);
                    runtimeController.load(packageId, artifact);
                    runtimeController.start(packageId);
                    long next = runtimeController.generation(packageId).orElse(0L);
                    if (next <= previousGeneration) {
                        throw new PluginLifecycleException("reload did not create a new generation for " + packageId);
                    }
                } catch (Throwable failure) {
                    operations.put(packageId, new ExternalPluginOperationSnapshot(packageId,
                            ExternalPluginOperation.ROLLING_BACK, currentTransaction(packageId),
                            PluginLifecycleFailureAccumulator.describe(failure)));
                    rollbackReload(previous, previousVersion, failure);
                }
                runtimeManager.confirmDeferredStart(packageId);
            }
            return null;
        });
    }

    /** 删除安装包；返回是否保留当前实例至完整进程重启，不删除插件用户数据。 */
    public boolean remove(String packageId) {
        return withLock(packageId, ExternalPluginOperation.REMOVING, () -> {
            boolean wasLoaded = runtimeController.isLoaded(packageId);
            boolean effectiveAfterRestart = wasLoaded && runtimeController.requiresProcessRestart(packageId);
            if (!wasLoaded || effectiveAfterRestart) {
                runtimeController.requireNoActiveDependents(packageId);
            }
            Path previousArtifact = runtimeController.artifactPath(packageId)
                    .orElseGet(() -> runtimeController.installedArtifact(packageId));
            var previous = wasLoaded && !effectiveAfterRestart ? new RetiredRuntime(packageId, currentPhase(packageId),
                    previousArtifact, runtimeManager.prepareRollbackArtifact(packageId, true)) : null;
            PluginRemovalAttempt removal = new PluginRemovalAttempt(packageId);
            try {
                if (wasLoaded && !effectiveAfterRestart) {
                    runtimeController.unload(packageId);
                }
                if (!installer.removeInstalled(removal)) {
                    throw new PluginLifecycleException("installed artifact not found: " + packageId);
                }
            } catch (Throwable failure) {
                PluginLifecycleFailureAccumulator failures = new PluginLifecycleFailureAccumulator(failure);
                if (removal.outcome() == PluginRemovalAttempt.Outcome.REMOVED) {
                    try {
                        finishDurableRemoval(packageId, effectiveAfterRestart);
                    } catch (Throwable projectionFailure) {
                        failures.record(projectionFailure);
                    }
                    throw failures.propagate("plugin was durably removed before a terminal failure for '"
                            + packageId + "'");
                }
                boolean runtimeRestored = removal.outcome() != PluginRemovalAttempt.Outcome.UNSAFE
                        && (previous == null || restoreOldRuntime(previous, failures));
                if (!runtimeRestored) {
                    operations.put(packageId, new ExternalPluginOperationSnapshot(packageId,
                            ExternalPluginOperation.FAILED, currentTransaction(packageId),
                            PluginLifecycleFailureAccumulator.describe(failure)));
                    throw failures.propagate("remove failed and previous runtime could not be restored for '"
                            + packageId + "'");
                }
                throw failures.propagate("remove failed for '" + packageId + "'");
            } finally {
                if (previous != null) previous.close();
            }
            finishDurableRemoval(packageId, effectiveAfterRestart);
            return effectiveAfterRestart;
        });
    }

    public Optional<ExternalPluginOperationSnapshot> operation(String packageId) {
        return Optional.ofNullable(operations.get(packageId));
    }

    /** 当前进程仍持有的描述符；安装目录的替换或删除不改变此运行期身份。 */
    public Optional<PluginDescriptor> processRestartDescriptor(String packageId) {
        return runtimeController.processRestartDescriptor(packageId);
    }

    public boolean isLoaded(String id) {
        return runtimeController.isLoaded(id);
    }

    public List<String> activeDependents(String id) {
        return runtimeManager.activeDependents(id);
    }

    public boolean waitsForReplacementRestart(PluginDescriptor descriptor) {
        return runtimeManager.waitsForReplacementRestart(descriptor);
    }

    /** load / reload 取待生效安装包的描述符，否则沿用当前运行工件的依赖。 */
    public Optional<PluginDescriptor> descriptorForCodeLoad(String id) {
        return descriptorForCodeLoad(id, runtimeManager.hasPendingInstall(id));
    }

    private Optional<PluginDescriptor> descriptorForCodeLoad(String id, boolean pendingInstall) {
        return pendingInstall ? dependencyResolver.installedDescriptor(id) : dependencyResolver.activationDescriptor(id);
    }

    public Set<String> pendingInstalls(List<InstalledPluginSnapshot> inventory) {
        var pending = new HashSet<String>();
        var candidates = new HashSet<String>();
        for (var item : inventory) {
            var plugin = item.plugin();
            if (runtimeManager.matchesDeferredInstall(plugin.id(), plugin.path(), item.artifactSha256())) {
                if (retainsGeneration(plugin.descriptor())) pending.add(plugin.id());
                else candidates.add(plugin.id());
            } else if (disabledOnPreviousStart(plugin.descriptor())) {
                candidates.add(plugin.id());
            }
        }
        if (!candidates.isEmpty()) pending.addAll(dependencyResolver.selectedAfterRestart(inventory, candidates));
        return Set.copyOf(pending);
    }

    private boolean disabledOnPreviousStart(PluginDescriptor descriptor) {
        return descriptor.lifecyclePolicy() != PluginLifecyclePolicy.HOT_RELOAD
                && runtimeManager.skippedDisabledAtStartup(descriptor.id())
                && runtimeManager.loadedDescriptor(descriptor.id()).isEmpty();
    }

    private boolean retainsGeneration(PluginDescriptor descriptor) {
        return runtimeManager.hasDisplacedGeneration(descriptor.id()) || waitsForReplacementRestart(descriptor);
    }

    private boolean expectsChangeAfterRestart(PluginDescriptor descriptor) {
        return retainsGeneration(descriptor)
                || dependencyResolver.selectedAfterRestart(descriptor.id());
    }

    /** 管理读模型的跨组件一致性版本；奇数时不得发布来源证明结论。 */
    public long lifecycleMutationEpoch() {
        return lifecycleMutationEpoch.get();
    }

    /** Spring 可能在 SmartLifecycle 停止失败后仍销毁父 context，因此先确认插件已完成清退。 */
    public static void stopBeforeContextClose(ConfigurableApplicationContext context) {
        if (context.isActive()) {
            context.getBeanProvider(ExternalPluginLifecycleCoordinator.class)
                    .ifAvailable(ExternalPluginLifecycleCoordinator::stopForBackendClose);
        }
    }

    /** 在父 context 仍可用时完成插件清退；失败后同一 owner 可再次清退。 */
    public void stopForBackendClose() {
        lifecycleMutationLock.lock();
        try {
            backendClosing = true;
            lifecycleMutationEpoch.incrementAndGet();
            lifecycleService.stopAll();
            backendStopFailed = false;
        } catch (RuntimeException | Error failure) {
            backendStopFailed = true;
            throw failure;
        } finally {
            lifecycleMutationEpoch.incrementAndGet();
            lifecycleMutationLock.unlock();
        }
    }

    public boolean backendStopFailed() {
        return backendStopFailed;
    }

    private void acquireReservation() {
        if (!lifecycleMutationLock.tryLock()) {
            throw new ClassifiedPluginLifecycleException(PluginManagementErrorCode.OPERATION_IN_PROGRESS,
                    "another plugin lifecycle mutation is already in progress");
        }
    }

    private void acquireMutationLock() {
        lifecycleService.requireServiceMutationAllowed();
        acquireReservation();
        if (backendClosing) {
            lifecycleMutationLock.unlock();
            if (backendStopFailed) throw new ClassifiedPluginLifecycleException(PluginManagementErrorCode.BACKEND_STOP_FAILED,
                    "backend shutdown failed; retry shutdown before changing plugins");
            throw new ClassifiedPluginLifecycleException(PluginManagementErrorCode.OPERATION_IN_PROGRESS,
                    "backend context is closing");
        }
    }

    public <T> T withReadReservation(java.util.function.Supplier<T> action) {
        acquireReservation();
        try {
            return action.get();
        } finally {
            lifecycleMutationLock.unlock();
        }
    }

    /** 预约一组目录安装；下载不改变读模型 epoch，各包仍独立提交，不提供跨包回滚。 */
    public <T> T withMutationReservation(java.util.function.Supplier<T> action) {
        acquireMutationLock();
        try {
            return action.get();
        } finally {
            lifecycleMutationLock.unlock();
        }
    }

    /** 统一的本地/市场安装更新事务；全局写预约从安全校验前持续到提交或回滚终态。 */
    public PluginActivationResult installOrUpdate(Path packageFile, boolean allowDowngrade,
                                                  PluginPackageOrigin origin) {
        acquireMutationLock();
        boolean outermostMutation = (lifecycleMutationEpoch.get() & 1L) == 0L;
        if (outermostMutation) {
            lifecycleMutationEpoch.incrementAndGet();
        }
        try {
            return installOrUpdateExclusive(packageFile, allowDowngrade, origin);
        } finally {
            if (outermostMutation) {
                lifecycleMutationEpoch.incrementAndGet();
            }
            lifecycleMutationLock.unlock();
        }
    }

    private PluginActivationResult installOrUpdateExclusive(Path packageFile, boolean allowDowngrade,
                                                             PluginPackageOrigin origin) {
        PreparedPluginTransaction prepared = null;
        try {
            prepared = installer.prepareTransaction(packageFile, allowDowngrade, origin);
            return finishPreparedInstall(prepared);
        } catch (Throwable failure) {
            PluginLifecycleFailureAccumulator failures = new PluginLifecycleFailureAccumulator(failure);
            if (prepared != null && prepared.readyToCommit()
                    && prepared.commitState() == PreparedPluginTransaction.CommitState.PREPARED) {
                // 任何尚未进入 commit 状态机的失败都在这里统一退役 PREPARED；已完成内部回滚、已开始
                // commit 或已 durable 的事务由 installer 的幂等状态校验保持原终态。
                discardPreparedSafely(prepared, failures);
            }
            throw failures.propagate("failed to finish prepared plugin install");
        }
    }

    private PluginActivationResult finishPreparedInstall(PreparedPluginTransaction prepared) {
        PluginInstallResult stagedResult = prepared.result();
        if (!prepared.readyToCommit()) {
            boolean deferred = false;
            if (stagedResult != null && stagedResult.descriptor() != null
                    && stagedResult.outcome().accepted()) {
                List<PluginDependencyProblem> problems =
                        installDependencyProblems(stagedResult);
                if (!problems.isEmpty() && !onlyDeferredDependencies(problems)) {
                    return dependencyRejected(prepared, problems);
                }
                var descriptor = stagedResult.descriptor();
                var installed = new InstalledPlugin(descriptor, stagedResult.installedPath());
                if (runtimeManager.matchesDeferredInstall(installed) || disabledOnPreviousStart(descriptor)) {
                    deferred = expectsChangeAfterRestart(descriptor);
                }
            }
            String packageId = stagedResult != null ? stagedResult.pluginId() : null;
            PluginRuntimePhase phase = currentPhase(packageId);
            return new PluginActivationResult(prepared.transactionId(), stagedResult,
                    stagedResult != null && stagedResult.outcome() == PluginInstallOutcome.DUPLICATE
                            && phase == PluginRuntimePhase.STARTED
                            && runtimeController.loadedArtifactMatches(stagedResult),
                    false, null, ExternalPluginOperation.IDLE, phase, false, List.of(), deferred);
        }
        String packageId = stagedResult.pluginId();
        ExternalPluginOperation operation = operationFor(stagedResult);
        return withLock(packageId, operation, prepared.transactionId(), () ->
                withReplacementLocks(prepared, operation, () -> {
                    var descriptor = stagedResult.descriptor();
                    var problems = installDependencyProblems(stagedResult);
                    boolean deferred = onlyDeferredDependencies(problems);
                    if (!problems.isEmpty() && !deferred) return dependencyRejected(prepared, problems);
                    boolean development = activationBlockedByDevelopment(descriptor);
                    boolean activate = !deferred && !development && !dependencyResolver.configuredDisabled(packageId)
                            && !requiresDeferredActivation(descriptor);
                    var plan = new RetirementPlan();
                    if (!development) planRetirement(descriptor, activate, plan);
                    if (!plan.blockers.isEmpty()) return dependencyRejected(prepared, plan.blockers);
                    return activate ? activatePrepared(prepared, plan) : commitWithoutActivation(prepared, !development, plan);
                }));
    }

    private static boolean onlyDeferredDependencies(List<PluginDependencyProblem> problems) {
        return !problems.isEmpty() && problems.stream().allMatch(PluginDependencyProblem::availableAfterRestart);
    }

    private boolean requiresDeferredActivation(PluginDescriptor descriptor) {
        return descriptor.lifecyclePolicy().requiresProcessRestart()
                || waitsForReplacementRestart(descriptor)
                || runtimeController.requiresProcessRestart(descriptor.id())
                || descriptor.replaces().stream().anyMatch(runtimeController::requiresProcessRestart)
                || headlessDesktop(descriptor) && runtimeManager.loadedDescriptor(descriptor.id()).isEmpty();
    }

    private boolean headlessDesktop(PluginDescriptor descriptor) {
        return descriptor.desktopOnly() && runtimeManager.headlessStartup();
    }

    private List<PluginDependencyProblem> installDependencyProblems(PluginInstallResult install) {
        var descriptor = install.descriptor();
        if (activationBlockedByDevelopment(descriptor)) return dependencyResolver.installedProblems(descriptor);
        return dependencyResolver.installActivationProblems(install, requiresDeferredActivation(descriptor));
    }

    private PluginActivationResult commitWithoutActivation(PreparedPluginTransaction prepared, boolean deferred, RetirementPlan plan) {
        String packageId = prepared.result().pluginId();
        PluginRuntimePhase phaseBeforeCommit = currentPhase(packageId);
        boolean pendingRestart = deferred && (!headlessDesktop(prepared.result().descriptor())
                || runtimeManager.loadedDescriptor(packageId).isPresent() || !plan.retained.isEmpty()
                || waitsForReplacementRestart(prepared.result().descriptor()));
        CommittedPluginTransaction committed = null;
        List<RetiredRuntime> retired = new ArrayList<>();
        String digest = null;
        PluginProvenanceRecord previous = null;
        try {
            installer.verifyCurrentArtifacts(prepared);
            if (deferred) previous = runtimeManager.provenanceBeforeDisplacement(packageId);
            retirePackages(plan, retired);
            committed = installer.commitTransaction(prepared);
            if (deferred) {
                digest = installer.verifyCommittedTargetDigest(committed);
            } else {
                installer.verifyCommittedTarget(committed);
            }
            installer.markActivated(committed);
            installer.completeTransaction(committed);
        } catch (Throwable failure) {
            PluginLifecycleFailureAccumulator failures = new PluginLifecycleFailureAccumulator(failure);
            if (committed != null && committed.durableState().keepsCommittedArtifact()) {
                refreshRecoveryModeSafely(failures);
                boolean recoveryBlocked = markRecoveryBlockedIfNeeded(packageId, prepared, committed);
                failures.rethrowFatal();
                // 已提交的工件在 finally 中登记，此处被替换的代次仍然驻留。
                boolean resident = deferred && (!plan.retained.isEmpty() || runtimeManager.loadedDescriptor(packageId).isPresent()
                        || waitsForReplacementRestart(prepared.result().descriptor()));
                return new PluginActivationResult(prepared.transactionId(), prepared.result(), false,
                        false, null, operationFor(prepared.result()), phaseBeforeCommit, recoveryBlocked, List.of(),
                        resident || deferred && !recoveryBlocked && expectsChangeAfterRestart(prepared.result().descriptor()));
            }
            boolean filesRestored = committed != null
                    ? rollbackTransactionSafely(committed, failures)
                    : recoverPreparedTransaction(prepared, failures);
            boolean rolledBack = filesRestored && restoreRetiredRuntimes(retired, null, failures);
            refreshRecoveryModeSafely(failures);
            failures.rethrowFatal();
            PluginInstallResult failed = new PluginInstallResult(PluginInstallOutcome.FAILED,
                    prepared.result().descriptor(), null, prepared.result().previousVersion(),
                    List.of("deferred activation commit failed: "
                                    + PluginLifecycleFailureAccumulator.describe(failure),
                            rolledBack
                                    ? "previous version restored" : "previous version recovery failed"));
            boolean recoveryBlocked = markRecoveryBlockedIfNeeded(packageId, prepared, committed);
            return new PluginActivationResult(prepared.transactionId(), failed, false, rolledBack,
                    rollbackVersion(rolledBack, prepared.result().previousVersion(), retired),
                    operationFor(prepared.result()), currentPhase(packageId), recoveryBlocked);
        } finally {
            retired.forEach(RetiredRuntime::close);
            if (committed != null && committed.durableState().keepsCommittedArtifact()) {
                prepared.result().descriptor().replaces().forEach(runtimeManager::forgetDeferredArtifact);
                if (deferred) runtimeManager.recordDeferredInstall(
                        new InstalledPlugin(prepared.result().descriptor(), prepared.target()), digest, previous, plan.retained, pendingRestart);
                else runtimeManager.forgetDeferredArtifact(packageId);
            }
        }
        refreshAfterDurableMutation(packageId, "plugin install committed without runtime activation");
        boolean recoveryBlocked = markRecoveryBlockedIfNeeded(packageId, prepared, committed);
        return new PluginActivationResult(prepared.transactionId(), prepared.result(), false, false, null,
                operationFor(prepared.result()), phaseBeforeCommit, recoveryBlocked, List.of(),
                deferred && !recoveryBlocked && expectsChangeAfterRestart(prepared.result().descriptor()));
    }

    /** 保存安装包时保留正在调试的源码实例，避免安装或替换操作卸载它。 */
    public boolean activationBlockedByDevelopment(PluginDescriptor descriptor) {
        return descriptor != null && (runtimeController.isDevelopmentArtifact(descriptor.id())
                || descriptor.replaces().stream().anyMatch(runtimeController::isDevelopmentArtifact));
    }

    private PluginActivationResult dependencyRejected(PreparedPluginTransaction prepared,
                                                      List<PluginDependencyProblem> problems) {
        PluginInstallResult staged = prepared.result();
        String packageId = staged != null ? staged.pluginId() : null;
        if (prepared.readyToCommit()) {
            PluginLifecycleFailureAccumulator failures = new PluginLifecycleFailureAccumulator(
                    new PluginLifecycleException("plugin dependency validation rejected the prepared package"));
            if (!discardPreparedSafely(prepared, failures)) {
                throw failures.propagate("failed to discard dependency-rejected plugin transaction");
            }
        }
        PluginInstallResult rejected = new PluginInstallResult(PluginInstallOutcome.REJECTED_DEPENDENCY,
                staged != null ? staged.descriptor() : null, null,
                staged != null ? staged.previousVersion() : null,
                problems.stream().map(PluginDependencyProblem::detail).toList());
        return new PluginActivationResult(prepared.transactionId(), rejected, false, false, null,
                staged != null ? operationFor(staged) : ExternalPluginOperation.IDLE,
                currentPhase(packageId), problems);
    }

    private PluginActivationResult activatePrepared(PreparedPluginTransaction prepared, RetirementPlan plan) {
        String packageId = prepared.result().pluginId();
        String previousVersion = runtimeManager.loadedDescriptor(packageId).map(PluginDescriptor::version)
                .orElse(prepared.result().previousVersion());
        CommittedPluginTransaction committed = null;
        List<RetiredRuntime> retired = new ArrayList<>();
        boolean candidateLoadAttempted = false;
        try {
            // 下载 / 预校验不持包锁；进入停机窗口后先复核安装态，避免过期事务先卸下新 generation。
            installer.verifyCurrentArtifacts(prepared);
            retirePackages(plan, retired);
            committed = installer.commitTransaction(prepared);
            installer.verifyCommittedTarget(committed);
            candidateLoadAttempted = true;
            runtimeController.load(packageId, prepared.target());
            runtimeController.start(packageId);
            installer.markActivated(committed);
            installer.completeTransaction(committed);
        } catch (Throwable activationFailure) {
            PluginLifecycleFailureAccumulator failures = new PluginLifecycleFailureAccumulator(activationFailure);
            if (committed != null && committed.durableState().keepsCommittedArtifact()) {
                refreshRecoveryModeSafely(failures);
                boolean recoveryBlocked = markRecoveryBlockedIfNeeded(packageId, prepared, committed);
                failures.rethrowFatal();
                return new PluginActivationResult(prepared.transactionId(), prepared.result(), true,
                        false, null, operationFor(prepared.result()), PluginRuntimePhase.STARTED,
                        recoveryBlocked);
            }
            operations.put(packageId, new ExternalPluginOperationSnapshot(packageId,
                    ExternalPluginOperation.ROLLING_BACK, prepared.transactionId(),
                    PluginLifecycleFailureAccumulator.describe(activationFailure)));
            boolean currentGenerationCleared = !candidateLoadAttempted || cleanupCurrentGeneration(packageId, failures);
            boolean filesRestored = committed == null
                    ? recoverPreparedTransaction(prepared, failures)
                    : rollbackTransactionSafely(committed, failures);
            boolean rolledBack = filesRestored && restoreRetiredRuntimes(retired,
                    currentGenerationCleared ? null : packageId, failures);
            PluginInstallResult failed = new PluginInstallResult(PluginInstallOutcome.FAILED,
                    prepared.result().descriptor(), null, prepared.result().previousVersion(),
                    List.of("activation failed: "
                                    + PluginLifecycleFailureAccumulator.describe(activationFailure),
                            rolledBack ? "previous version restored" : "previous version recovery failed"));
            if (!rolledBack) {
                operations.put(packageId, new ExternalPluginOperationSnapshot(packageId,
                        ExternalPluginOperation.FAILED, prepared.transactionId(),
                        PluginLifecycleFailureAccumulator.describe(activationFailure)));
            }
            refreshRecoveryModeSafely(failures);
            failures.rethrowFatal();
            boolean recoveryBlocked = markRecoveryBlockedIfNeeded(packageId, prepared, committed);
            return new PluginActivationResult(prepared.transactionId(), failed, false, rolledBack,
                    rollbackVersion(rolledBack, previousVersion, retired), operationFor(prepared.result()),
                    currentPhase(packageId), recoveryBlocked);
        } finally {
            retired.forEach(RetiredRuntime::close);
            if (committed != null && committed.durableState().keepsCommittedArtifact()) {
                runtimeManager.forgetDeferredArtifact(packageId);
                prepared.result().descriptor().replaces().forEach(runtimeManager::forgetDeferredArtifact);
            }
        }
        refreshAfterDurableMutation(packageId, "plugin activation committed");
        boolean recoveryBlocked = markRecoveryBlockedIfNeeded(packageId, prepared, committed);
        return new PluginActivationResult(prepared.transactionId(), prepared.result(), true, false, null,
                operationFor(prepared.result()), PluginRuntimePhase.STARTED, recoveryBlocked);
    }

    private boolean markRecoveryBlockedIfNeeded(
            String packageId,
            PreparedPluginTransaction prepared,
            CommittedPluginTransaction committed) {
        boolean blocked = prepared.commitState() == PreparedPluginTransaction.CommitState.UNSAFE
                || committed != null && committed.recoveryBlocked();
        if (blocked) {
            operations.put(packageId, new ExternalPluginOperationSnapshot(packageId,
                    ExternalPluginOperation.FAILED, prepared.transactionId(),
                    "plugin transaction reached a durable state but recovery cleanup remains blocked"));
        }
        return blocked;
    }

    private void finishDurableRemoval(String packageId, boolean effectiveAfterRestart) {
        runtimeManager.forgetDeferredArtifact(packageId);
        Throwable failure = null;
        try {
            if (!effectiveAfterRestart) {
                runtimeController.forgetInstallation(packageId);
            }
        } catch (Throwable cleanupFailure) {
            failure = cleanupFailure;
        }
        try {
            recoveryModeService.refresh();
        } catch (Throwable refreshFailure) {
            if (failure == null) {
                failure = refreshFailure;
            } else {
                addSuppressedSafely(failure, refreshFailure);
            }
        }
        if (failure != null) {
            operations.put(packageId, new ExternalPluginOperationSnapshot(packageId,
                    ExternalPluginOperation.FAILED, currentTransaction(packageId),
                    PluginLifecycleFailureAccumulator.describe(failure)));
            log.error("Plugin '{}' was durably removed but post-removal state projection failed", packageId, failure);
            new PluginLifecycleFailureAccumulator(failure).rethrowFatal();
        }
    }

    private void refreshAfterDurableMutation(String packageId, String operation) {
        try {
            recoveryModeService.refresh();
        } catch (Throwable failure) {
            log.error("{} for '{}' but recovery-mode projection refresh failed", operation, packageId, failure);
            new PluginLifecycleFailureAccumulator(failure).rethrowFatal();
        }
    }

    private static ExternalPluginOperation operationFor(PluginInstallResult result) {
        return result.previousVersion() == null
                ? ExternalPluginOperation.INSTALLING : ExternalPluginOperation.UPDATING;
    }

    private static final class RetirementPlan {
        final Map<String, Path> ordered = new LinkedHashMap<>();
        final Set<String> retained = new HashSet<>();
        final List<PluginDependencyProblem> blockers = new ArrayList<>();
    }

    private void planRetirement(PluginDescriptor candidate, boolean activate, RetirementPlan plan) {
        var targets = new LinkedHashSet<>(candidate.replaces());
        if (activate) targets.add(candidate.id());
        if (targets.isEmpty()) return;
        targets.retainAll(runtimeManager.packagePhases().keySet());
        Set<String> visited = new HashSet<>();
        for (String id : targets) planRetirement(id, targets, activate ? null : candidate.id(), visited, plan);
    }

    private void planRetirement(String id, Set<String> targets, String continuingConsumer,
                                Set<String> visited, RetirementPlan plan) {
        if (!visited.add(id)) return;
        boolean keep = runtimeController.requiresProcessRestart(id);
        for (String consumer : runtimeManager.activeDependents(id)) {
            if (consumer.equals(continuingConsumer)) {
                keep = true;
            } else if (targets.contains(consumer)) {
                planRetirement(consumer, targets, continuingConsumer, visited, plan);
                if (plan.retained.contains(consumer)) keep = true;
            } else {
                var edge = runtimeManager.loadedDescriptor(consumer).orElseThrow().dependencies().stream()
                        .filter(ref -> !ref.optional() && ref.pluginId().equals(id)).findFirst().orElseThrow();
                String version = runtimeManager.loadedDescriptor(id).orElseThrow().version();
                plan.blockers.add(PluginDependencyProblem.retirementBlocked(consumer, edge, version));
            }
        }
        if (keep) plan.retained.add(id);
        else plan.ordered.put(id, runtimeController.artifactPath(id).orElse(null));
    }

    private void retirePackages(RetirementPlan plan, List<RetiredRuntime> retired) {
        plan.ordered.forEach((id, artifact) -> {
            retired.add(retainRuntime(id, artifact));
            runtimeController.unload(id);
        });
    }

    private RetiredRuntime retainRuntime(String id, Path artifact) {
        return new RetiredRuntime(id, runtimeController.phase(id), artifact,
                runtimeManager.prepareRollbackArtifact(id, false));
    }

    private String rollbackVersion(boolean rolledBack, String previousVersion, List<RetiredRuntime> retired) {
        if (!rolledBack) return null;
        if (previousVersion != null || retired.size() != 1) return previousVersion;
        return runtimeManager.loadedDescriptor(retired.getFirst().packageId()).map(PluginDescriptor::version).orElse(null);
    }

    private boolean restoreRetiredRuntimes(
            List<RetiredRuntime> retired, String occupiedId,
            PluginLifecycleFailureAccumulator failures
    ) {
        boolean restored = occupiedId == null;
        for (RetiredRuntime runtime : retired.reversed()) {
            if (!runtime.packageId().equals(occupiedId)) restored = restoreOldRuntime(runtime, failures) && restored;
        }
        return restored;
    }

    private <T> T withReplacementLocks(PreparedPluginTransaction prepared,
                                       ExternalPluginOperation operation,
                                       Operation<T> action) {
        List<String> ids = prepared.result().descriptor().replaces().stream().sorted().toList();
        List<ReentrantLock> acquired = new ArrayList<>();
        try {
            for (String id : ids) {
                ReentrantLock lock = locks.computeIfAbsent(id, ignored -> new ReentrantLock());
                if (!lock.tryLock()) {
                    throw new ClassifiedPluginLifecycleException(PluginManagementErrorCode.OPERATION_IN_PROGRESS,
                            "operation already in progress for plugin package '" + id + "'");
                }
                acquired.add(lock);
                operations.put(id, new ExternalPluginOperationSnapshot(id, operation,
                        prepared.transactionId(), null));
            }
            return action.run();
        } finally {
            for (int i = ids.size() - 1; i >= 0; i--) {
                String id = ids.get(i);
                if (i < acquired.size()) {
                    operations.put(id, new ExternalPluginOperationSnapshot(id,
                            ExternalPluginOperation.IDLE, prepared.transactionId(), null));
                    acquired.get(i).unlock();
                }
            }
        }
    }

    private record RetiredRuntime(String packageId, PluginRuntimePhase phase, Path artifact, PluginRuntimeManager.RollbackArtifact retained)
            implements AutoCloseable {
        @Override
        public void close() {
            if (retained != null) retained.close();
        }
    }

    private PluginRuntimePhase currentPhase(String packageId) {
        return runtimeController.phase(packageId);
    }

    private void requireActivationDependencies(String packageId, Optional<PluginDescriptor> source) {
        var descriptor = source.orElseThrow(() -> new ClassifiedPluginLifecycleException(PluginManagementErrorCode.UNKNOWN_PLUGIN,
                        "installed artifact not found: " + packageId));
        List<PluginDependencyProblem> problems = dependencyResolver.activationProblems(descriptor);
        if (!problems.isEmpty()) {
            throw new ClassifiedPluginLifecycleException(PluginManagementErrorCode.DEPENDENCY_UNSATISFIED,
                    problems.get(0).detail());
        }
    }

    private boolean cleanupCurrentGeneration(
            String packageId,
            PluginLifecycleFailureAccumulator failures
    ) {
        try {
            return runtimeController.cleanupCurrentGeneration(packageId);
        } catch (Throwable cleanupFailure) {
            failures.record(cleanupFailure);
            // 回滚路径继续尝试恢复文件，最终结果会标记恢复失败。
            return false;
        }
    }

    private boolean restoreOldRuntime(RetiredRuntime previous, PluginLifecycleFailureAccumulator failures) {
        try {
            runtimeController.restoreOldRuntime(previous.packageId(), previous.artifact(), previous.retained(), previous.phase());
            return true;
        } catch (Throwable rollbackFailure) {
            failures.record(rollbackFailure);
            return false;
        }
    }

    public Map<String, ExternalPluginOperationSnapshot> operations() {
        return Map.copyOf(operations);
    }

    private void rollbackReload(RetiredRuntime previous, String previousVersion, Throwable original) {
        String packageId = previous.packageId();
        PluginLifecycleFailureAccumulator failures = new PluginLifecycleFailureAccumulator(original);
        boolean restored = cleanupCurrentGeneration(packageId, failures)
                && (previousVersion == null || restoreOldRuntime(previous, failures));
        var version = runtimeManager.loadedDescriptor(packageId).map(PluginDescriptor::version).orElse(null);
        var phase = currentPhase(packageId);
        restored = restored && java.util.Objects.equals(version, previousVersion) && phase == previous.phase();
        if (!restored) {
            operations.put(packageId, new ExternalPluginOperationSnapshot(packageId,
                    ExternalPluginOperation.FAILED, currentTransaction(packageId), "reload rollback failed"));
            refreshRecoveryModeSafely(failures);
        }
        failures.rethrowFatal();
        var outcome = new ClassifiedPluginLifecycleException.ReloadRollback(restored, version, phase);
        log.warn("Plugin reload failed: package={}, restored={}, loadedVersion={}, phase={}",
                packageId, restored, version, outcome.phase(), failures.primary());
        throw new ClassifiedPluginLifecycleException(outcome, failures.primary());
    }

    private String currentTransaction(String packageId) {
        ExternalPluginOperationSnapshot snapshot = operations.get(packageId);
        return snapshot != null ? snapshot.transactionId() : null;
    }

    private boolean rollbackTransactionSafely(
            CommittedPluginTransaction committed,
            PluginLifecycleFailureAccumulator failures
    ) {
        try {
            return installer.rollbackTransaction(committed);
        } catch (Throwable rollbackFailure) {
            failures.record(rollbackFailure);
            return false;
        }
    }

    private boolean recoverPreparedTransaction(
            PreparedPluginTransaction prepared,
            PluginLifecycleFailureAccumulator failures
    ) {
        return switch (prepared.commitState()) {
            case ROLLED_BACK, DISCARDED -> true;
            case PREPARED -> discardPreparedSafely(prepared, failures);
            case COMMITTED, UNSAFE -> false;
        };
    }

    private boolean discardPreparedSafely(
            PreparedPluginTransaction prepared,
            PluginLifecycleFailureAccumulator failures
    ) {
        try {
            boolean discarded = installer.discardPrepared(prepared);
            if (!discarded) {
                failures.record(new PluginLifecycleException(
                        "prepared plugin transaction was not confirmed discarded"));
            }
            return discarded;
        } catch (Throwable discardFailure) {
            failures.record(discardFailure);
            return false;
        }
    }

    private void refreshRecoveryModeSafely(PluginLifecycleFailureAccumulator failures) {
        try {
            recoveryModeService.refresh();
        } catch (Throwable refreshFailure) {
            failures.record(refreshFailure);
        }
    }

    private <T> T withHotManagement(String id, ExternalPluginOperation operation, Operation<T> action) {
        return withLock(id, operation, () -> {
            var loaded = runtimeManager.loadedDescriptor(id);
            var restricted = loaded.filter(d -> d.lifecyclePolicy().requiresRestart() || waitsForReplacementRestart(d));
            if (restricted.isEmpty() && (loaded.isEmpty() || runtimeManager.hasPendingInstall(id))) {
                restricted = dependencyResolver.installedDescriptor(id)
                        .filter(d -> d.lifecyclePolicy().requiresRestart() || waitsForReplacementRestart(d));
            }
            if (restricted.isPresent()) {
                throw new ClassifiedPluginLifecycleException(PluginManagementErrorCode.RESTART_REQUIRED_PLUGIN,
                        "Plugin requires restart before hot management: " + id);
            }
            return action.run();
        });
    }

    private <T> T withLock(String packageId, ExternalPluginOperation operation, Operation<T> action) {
        return withLock(packageId, operation, UUID.randomUUID().toString(), action);
    }

    private <T> T withLock(String packageId, ExternalPluginOperation operation,
                           String transactionId, Operation<T> action) {
        acquireMutationLock();
        boolean outermostMutation = (lifecycleMutationEpoch.get() & 1L) == 0L;
        if (outermostMutation) {
            lifecycleMutationEpoch.incrementAndGet();
        }
        try {
            ReentrantLock lock = locks.computeIfAbsent(packageId, ignored -> new ReentrantLock());
            if (!lock.tryLock()) {
                throw new ClassifiedPluginLifecycleException(PluginManagementErrorCode.OPERATION_IN_PROGRESS,
                        "operation already in progress for plugin package '" + packageId + "'");
            }
            try {
                operations.put(packageId, new ExternalPluginOperationSnapshot(
                        packageId, operation, transactionId, null));
                T result = action.run();
                ExternalPluginOperationSnapshot current = operations.get(packageId);
                if (current == null || current.operation() != ExternalPluginOperation.FAILED) {
                    operations.put(packageId, new ExternalPluginOperationSnapshot(packageId,
                            ExternalPluginOperation.IDLE, transactionId, null));
                }
                return result;
            } catch (Throwable failure) {
                ExternalPluginOperationSnapshot current = operations.get(packageId);
                if (current == null || current.operation() != ExternalPluginOperation.FAILED) {
                    operations.put(packageId, new ExternalPluginOperationSnapshot(packageId,
                            ExternalPluginOperation.IDLE, transactionId,
                            PluginLifecycleFailureAccumulator.describe(failure)));
                }
                throw new PluginLifecycleFailureAccumulator(failure)
                        .propagate("plugin operation failed for '" + packageId + "'");
            } finally {
                lock.unlock();
            }
        } finally {
            if (outermostMutation) {
                lifecycleMutationEpoch.incrementAndGet();
            }
            lifecycleMutationLock.unlock();
        }
    }

    @FunctionalInterface
    private interface Operation<T> {
        T run();
    }
}
