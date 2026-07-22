package top.sywyar.pixivdownload.plugin.lifecycle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import top.sywyar.pixivdownload.plugin.runtime.lifecycle.LoadedPluginPackage;
import top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeManager;
import top.sywyar.pixivdownload.plugin.runtime.lifecycle.UnloadedPluginPackage;
import top.sywyar.pixivdownload.plugin.runtime.install.ExternalPluginInstaller;
import top.sywyar.pixivdownload.plugin.runtime.install.model.InstalledPlugin;
import top.sywyar.pixivdownload.plugin.runtime.install.transaction.PreparedPluginTransaction;
import top.sywyar.pixivdownload.plugin.runtime.install.transaction.CommittedPluginTransaction;
import top.sywyar.pixivdownload.plugin.runtime.install.transaction.PluginRemovalAttempt;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginInstallOutcome;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginInstallResult;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageOrigin;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import top.sywyar.pixivdownload.plugin.install.PluginActivationResult;
import top.sywyar.pixivdownload.plugin.install.PluginDependencyProblem;
import top.sywyar.pixivdownload.plugin.install.PluginDependencyResolver;
import top.sywyar.pixivdownload.plugin.management.PluginManagementErrorCode;
import top.sywyar.pixivdownload.plugin.recovery.RecoveryModeService;

/**
 * 外置插件全部运行期写动作的唯一编排入口。固定顺序为应用足迹清退、PF4J 物理生命周期、代际替换；
 * 进程级写预约保护跨包依赖与替代关系，packageId 锁进一步固定目标包与被替代包的提交窗口。
 */
@Service
public class ExternalPluginLifecycleCoordinator {

    private static final Logger log = LoggerFactory.getLogger(ExternalPluginLifecycleCoordinator.class);

    private final PluginRuntimeManager runtimeManager;
    private final PluginLifecycleService lifecycleService;
    private final ExternalPluginInstaller installer;
    private final RecoveryModeService recoveryModeService;
    private final PluginDependencyResolver dependencyResolver;
    /**
     * 插件生命周期写事务的进程内总预约。安装必须在发布可恢复事务前取得，并持有到提交或回滚结束；
     * 其它启停、卸载和删除动作走同一预约，避免依赖或旧 artifact 在检查与激活之间变化。
     */
    private final ReentrantLock lifecycleMutationLock = new ReentrantLock();
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final Map<String, ExternalPluginOperationSnapshot> operations = new ConcurrentHashMap<>();

    public ExternalPluginLifecycleCoordinator(PluginRuntimeManager runtimeManager,
                                              PluginLifecycleService lifecycleService,
                                              ExternalPluginInstaller installer,
                                              RecoveryModeService recoveryModeService,
                                              PluginDependencyResolver dependencyResolver) {
        this.runtimeManager = runtimeManager;
        this.lifecycleService = lifecycleService;
        this.installer = installer;
        this.recoveryModeService = recoveryModeService;
        this.dependencyResolver = dependencyResolver;
    }

    public void start(String packageId) {
        withLock(packageId, ExternalPluginOperation.INSTALLING, () -> {
            requireActivationDependencies(packageId);
            startExclusive(packageId);
            recoveryModeService.refresh();
            return null;
        });
    }

    public void quiesce(String packageId) {
        withLock(packageId, ExternalPluginOperation.REMOVING, () -> {
            lifecycleService.quiesce(packageId);
            return null;
        });
    }

    public void stop(String packageId) {
        withLock(packageId, ExternalPluginOperation.REMOVING, () -> {
            stopExclusive(packageId);
            return null;
        });
    }

    public UnloadedPluginPackage unload(String packageId) {
        return withLock(packageId, ExternalPluginOperation.REMOVING, () -> {
            UnloadedPluginPackage unloaded = unloadExclusive(packageId);
            recoveryModeService.refresh();
            return unloaded;
        });
    }

    public void load(String packageId) {
        withLock(packageId, ExternalPluginOperation.INSTALLING, () -> {
            requireActivationDependencies(packageId);
            loadExclusive(packageId, installedArtifact(packageId));
            return null;
        });
    }

    /** 服务重启：保留 generation/classloader。 */
    public void restart(String packageId) {
        withLock(packageId, ExternalPluginOperation.UPDATING, () -> {
            requireActivationDependencies(packageId);
            stopExclusive(packageId);
            startExclusive(packageId);
            return null;
        });
    }

    /** 代码重载：物理 unload/load，必须产生新 generation。失败时尝试从同一磁盘包恢复。 */
    public void reload(String packageId) {
        withLock(packageId, ExternalPluginOperation.UPDATING, () -> {
            requireActivationDependencies(packageId);
            Path artifact = runtimeManager.artifactPath(packageId).orElseGet(() -> installedArtifact(packageId));
            long previousGeneration = lifecycleService.generation(packageId).orElse(0L);
            try {
                unloadExclusive(packageId);
                loadExclusive(packageId, artifact);
                startExclusive(packageId);
                long next = lifecycleService.generation(packageId).orElse(0L);
                if (next <= previousGeneration) {
                    throw new PluginLifecycleException("reload did not create a new generation for " + packageId);
                }
            } catch (Throwable failure) {
                operations.put(packageId, new ExternalPluginOperationSnapshot(packageId,
                        ExternalPluginOperation.ROLLING_BACK, currentTransaction(packageId), describe(failure)));
                rollbackReload(packageId, artifact, failure);
            }
            return null;
        });
    }

    /** 物理卸载后删除磁盘包。 */
    public void remove(String packageId) {
        withLock(packageId, ExternalPluginOperation.REMOVING, () -> {
            boolean wasLoaded = runtimeManager.packagePhases().containsKey(packageId);
            Path previousArtifact = runtimeManager.artifactPath(packageId).orElseGet(() -> installedArtifact(packageId));
            PluginRemovalAttempt removal = new PluginRemovalAttempt(packageId);
            try {
                if (wasLoaded) {
                    unloadExclusive(packageId);
                }
                if (!installer.removeInstalled(removal)) {
                    throw new PluginLifecycleException("installed artifact not found: " + packageId);
                }
            } catch (Throwable failure) {
                DeferredFailure failures = new DeferredFailure(failure);
                if (removal.outcome() == PluginRemovalAttempt.Outcome.REMOVED) {
                    try {
                        finishDurableRemoval(packageId);
                    } catch (Throwable projectionFailure) {
                        failures.record(projectionFailure);
                    }
                    throw propagateFailure("plugin was durably removed before a terminal failure for '"
                            + packageId + "'", failures.primary());
                }
                boolean runtimeRestored = removal.outcome() != PluginRemovalAttempt.Outcome.UNSAFE
                        && (!wasLoaded || restoreOldRuntime(packageId, previousArtifact, failures));
                if (!runtimeRestored) {
                    operations.put(packageId, new ExternalPluginOperationSnapshot(packageId,
                            ExternalPluginOperation.FAILED, currentTransaction(packageId), describe(failure)));
                }
                if (!runtimeRestored) {
                    throw propagateFailure("remove failed and previous runtime could not be restored for '"
                            + packageId + "'", failures.primary());
                }
                throw propagateFailure("remove failed for '" + packageId + "'", failures.primary());
            }
            finishDurableRemoval(packageId);
            return null;
        });
    }

    public Optional<ExternalPluginOperationSnapshot> operation(String packageId) {
        return Optional.ofNullable(operations.get(packageId));
    }

    /** 统一的本地/市场安装更新事务；全局写预约从安全校验前持续到提交或回滚终态。 */
    public PluginActivationResult installOrUpdate(Path packageFile, boolean allowDowngrade,
                                                  PluginPackageOrigin origin) {
        if (!lifecycleMutationLock.tryLock()) {
            throw new ClassifiedPluginLifecycleException(PluginManagementErrorCode.OPERATION_IN_PROGRESS,
                    "another plugin lifecycle mutation is already in progress");
        }
        try {
            return installOrUpdateExclusive(packageFile, allowDowngrade, origin);
        } finally {
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
            DeferredFailure failures = new DeferredFailure(failure);
            if (prepared != null && prepared.readyToCommit()
                    && prepared.commitState() == PreparedPluginTransaction.CommitState.PREPARED) {
                // 任何尚未进入 commit 状态机的失败都在这里统一退役 PREPARED；已完成内部回滚、已开始
                // commit 或已 durable 的事务由 installer 的幂等状态校验保持原终态。
                discardPreparedSafely(prepared, failures);
            }
            throw propagateFailure("failed to finish prepared plugin install", failures.primary());
        }
    }

    private PluginActivationResult finishPreparedInstall(PreparedPluginTransaction prepared) {
        PluginInstallResult stagedResult = prepared.result();
        if (!prepared.readyToCommit()) {
            if (stagedResult != null && stagedResult.descriptor() != null
                    && stagedResult.outcome().accepted()) {
                List<PluginDependencyProblem> problems =
                        dependencyResolver.activationProblems(stagedResult.descriptor());
                if (!problems.isEmpty()) {
                    return dependencyRejected(prepared, problems);
                }
            }
            String packageId = stagedResult != null ? stagedResult.pluginId() : null;
            PluginRuntimePhase phase = currentPhase(packageId);
            return new PluginActivationResult(prepared.transactionId(), stagedResult,
                    stagedResult != null && stagedResult.outcome() == PluginInstallOutcome.DUPLICATE
                            && phase == PluginRuntimePhase.STARTED,
                    false, null, ExternalPluginOperation.IDLE, phase);
        }
        String packageId = stagedResult.pluginId();
        ExternalPluginOperation operation = stagedResult.previousVersion() == null
                ? ExternalPluginOperation.INSTALLING : ExternalPluginOperation.UPDATING;
        // 进程重启插件不能安全进入当前 PF4J generation，只提交文件等待下次启动；后端重启策略在安装时仍
        // 即时激活，其重启约束只应用于管理页启停，避免把已 activated 的安装结果误报为待重启生效。
        if (stagedResult.descriptor().lifecyclePolicy().requiresProcessRestart()) {
            return withLock(packageId, operation, prepared.transactionId(), () ->
                    withReplacementLocks(prepared, operation, () -> activatePreparedWithDependencies(
                            prepared, () -> commitForProcessRestart(prepared))));
        }
        return withLock(packageId, operation, prepared.transactionId(), () ->
                withReplacementLocks(prepared, operation, () -> activatePreparedWithDependencies(
                        prepared, () -> activatePrepared(prepared))));
    }

    private PluginActivationResult activatePreparedWithDependencies(
            PreparedPluginTransaction prepared, Operation<PluginActivationResult> action) {
        List<PluginDependencyProblem> problems =
                dependencyResolver.activationProblems(prepared.result().descriptor());
        if (!problems.isEmpty()) {
            return dependencyRejected(prepared, problems);
        }
        return action.run();
    }

    private PluginActivationResult commitForProcessRestart(PreparedPluginTransaction prepared) {
        String packageId = prepared.result().pluginId();
        PluginRuntimePhase phaseBeforeCommit = currentPhase(packageId);
        CommittedPluginTransaction committed = null;
        List<RetiredRuntime> retired = List.of();
        try {
            installer.verifyCurrentArtifacts(prepared);
            retired = retireReplacedPackages(prepared.result().descriptor().replaces());
            committed = installer.commitTransaction(prepared);
            installer.verifyCommittedTarget(committed);
            installer.markActivated(committed);
            installer.completeTransaction(committed);
        } catch (Throwable failure) {
            DeferredFailure failures = new DeferredFailure(failure);
            if (committed != null && committed.durableState().keepsCommittedArtifact()) {
                refreshRecoveryModeSafely(failures);
                boolean recoveryBlocked = markRecoveryBlockedIfNeeded(packageId, prepared, committed);
                rethrowFatal(failures.primary());
                return new PluginActivationResult(prepared.transactionId(), prepared.result(), false,
                        false, null, operationFor(prepared.result()), phaseBeforeCommit, recoveryBlocked);
            }
            boolean filesRestored = committed != null
                    ? rollbackTransactionSafely(committed, failures)
                    : recoverPreparedTransaction(prepared, failures);
            boolean runtimeRestored = filesRestored && restoreRetiredRuntimes(retired, failures);
            boolean rolledBack = filesRestored && runtimeRestored;
            refreshRecoveryModeSafely(failures);
            rethrowFatal(failures.primary());
            PluginInstallResult failed = new PluginInstallResult(PluginInstallOutcome.FAILED,
                    prepared.result().descriptor(), null, prepared.result().previousVersion(),
                    List.of("process-restart commit failed: " + describe(failure),
                            rolledBack
                                    ? "previous version restored" : "previous version recovery failed"));
            boolean recoveryBlocked = markRecoveryBlockedIfNeeded(packageId, prepared, committed);
            return new PluginActivationResult(prepared.transactionId(), failed, false, rolledBack,
                    rolledBack ? prepared.result().previousVersion() : null,
                    operationFor(prepared.result()), currentPhase(packageId), recoveryBlocked);
        }
        refreshAfterDurableMutation(packageId, "plugin install committed for process restart");
        boolean recoveryBlocked = markRecoveryBlockedIfNeeded(packageId, prepared, committed);
        return new PluginActivationResult(prepared.transactionId(), prepared.result(), false, false, null,
                operationFor(prepared.result()), phaseBeforeCommit, recoveryBlocked);
    }

    private PluginActivationResult dependencyRejected(PreparedPluginTransaction prepared,
                                                      List<PluginDependencyProblem> problems) {
        PluginInstallResult staged = prepared.result();
        String packageId = staged != null ? staged.pluginId() : null;
        if (prepared.readyToCommit()) {
            DeferredFailure failures = new DeferredFailure(
                    new PluginLifecycleException("plugin dependency validation rejected the prepared package"));
            if (!discardPreparedSafely(prepared, failures)) {
                throw propagateFailure("failed to discard dependency-rejected plugin transaction",
                        failures.primary());
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

    private PluginActivationResult activatePrepared(PreparedPluginTransaction prepared) {
        String packageId = prepared.result().pluginId();
        boolean wasLoaded = runtimeManager.packagePhases().containsKey(packageId);
        Path previousArtifact = runtimeManager.artifactPath(packageId).orElse(null);
        String previousVersion = prepared.result().previousVersion();
        CommittedPluginTransaction committed = null;
        List<RetiredRuntime> retired = List.of();
        boolean runtimeMutationStarted = false;
        try {
            // 下载 / 预校验不持包锁；进入停机窗口后先复核安装态，避免过期事务先卸下新 generation。
            installer.verifyCurrentArtifacts(prepared);
            retired = retireReplacedPackages(prepared.result().descriptor().replaces());
            if (wasLoaded) {
                runtimeMutationStarted = true;
                unloadExclusive(packageId);
            }
            committed = installer.commitTransaction(prepared);
            installer.verifyCommittedTarget(committed);
            runtimeMutationStarted = true;
            loadExclusive(packageId, prepared.target());
            startExclusive(packageId);
            installer.markActivated(committed);
            installer.completeTransaction(committed);
        } catch (Throwable activationFailure) {
            DeferredFailure failures = new DeferredFailure(activationFailure);
            if (committed != null && committed.durableState().keepsCommittedArtifact()) {
                refreshRecoveryModeSafely(failures);
                boolean recoveryBlocked = markRecoveryBlockedIfNeeded(packageId, prepared, committed);
                rethrowFatal(failures.primary());
                return new PluginActivationResult(prepared.transactionId(), prepared.result(), true,
                        false, null, operationFor(prepared.result()), PluginRuntimePhase.STARTED,
                        recoveryBlocked);
            }
            operations.put(packageId, new ExternalPluginOperationSnapshot(packageId,
                    ExternalPluginOperation.ROLLING_BACK, prepared.transactionId(), describe(activationFailure)));
            boolean safeToCompensate = committed != null
                    || prepared.commitState() != PreparedPluginTransaction.CommitState.UNSAFE;
            boolean currentGenerationCleared = true;
            if (committed != null && safeToCompensate) {
                currentGenerationCleared = cleanupCurrentGeneration(packageId, failures);
            }
            boolean rollbackDeferred = committed != null && !currentGenerationCleared;
            boolean filesRestored;
            if (committed == null) {
                filesRestored = recoverPreparedTransaction(prepared, failures);
            } else if (rollbackDeferred) {
                deferRollbackUntilRestartSafely(committed, failures);
                filesRestored = false;
            } else {
                filesRestored = rollbackTransactionSafely(committed, failures);
            }
            boolean runtimeRestored = true;
            if (committed == null && runtimeMutationStarted && wasLoaded && filesRestored) {
                runtimeRestored = restoreUnchangedOld(packageId, true, previousArtifact, failures);
            } else if (committed != null && wasLoaded && filesRestored) {
                Path restoreArtifact = previousArtifact;
                if (restoreArtifact == null) {
                    try {
                        restoreArtifact = installedArtifact(packageId);
                    } catch (Throwable artifactFailure) {
                        failures.record(artifactFailure);
                        runtimeRestored = false;
                    }
                }
                if (runtimeRestored) {
                    runtimeRestored = restoreOldRuntime(
                            packageId, restoreArtifact, failures);
                }
            }
            if (filesRestored) {
                runtimeRestored = restoreRetiredRuntimes(retired, failures) && runtimeRestored;
            } else {
                runtimeRestored = false;
            }
            boolean rolledBack = filesRestored && runtimeRestored;
            PluginInstallResult failed = new PluginInstallResult(PluginInstallOutcome.FAILED,
                    prepared.result().descriptor(), null, previousVersion,
                    List.of("activation failed: " + describe(activationFailure),
                            rolledBack ? "previous version restored" : "previous version recovery failed"));
            if (!rolledBack) {
                operations.put(packageId, new ExternalPluginOperationSnapshot(packageId,
                        ExternalPluginOperation.FAILED, prepared.transactionId(), describe(activationFailure)));
            }
            refreshRecoveryModeSafely(failures);
            rethrowFatal(failures.primary());
            boolean recoveryBlocked = markRecoveryBlockedIfNeeded(
                    packageId, prepared, committed, rollbackDeferred);
            return new PluginActivationResult(prepared.transactionId(), failed, false, rolledBack,
                    rolledBack ? previousVersion : null, operationFor(prepared.result()),
                    currentPhase(packageId), recoveryBlocked);
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
        return markRecoveryBlockedIfNeeded(packageId, prepared, committed, false);
    }

    private boolean markRecoveryBlockedIfNeeded(
            String packageId,
            PreparedPluginTransaction prepared,
            CommittedPluginTransaction committed,
            boolean explicitlyBlocked) {
        boolean blocked = explicitlyBlocked
                || prepared.commitState() == PreparedPluginTransaction.CommitState.UNSAFE
                || committed != null && committed.recoveryBlocked();
        if (blocked) {
            operations.put(packageId, new ExternalPluginOperationSnapshot(packageId,
                    ExternalPluginOperation.FAILED, prepared.transactionId(),
                    "plugin transaction reached a durable state but recovery cleanup remains blocked"));
        }
        return blocked;
    }

    private void finishDurableRemoval(String packageId) {
        Throwable failure = null;
        try {
            lifecycleService.forgetInstallation(packageId);
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
                    ExternalPluginOperation.FAILED, currentTransaction(packageId), describe(failure)));
            log.error("Plugin '{}' was durably removed but post-removal state projection failed", packageId, failure);
            rethrowFatal(failure);
        }
    }

    private void refreshAfterDurableMutation(String packageId, String operation) {
        try {
            recoveryModeService.refresh();
        } catch (Throwable failure) {
            log.error("{} for '{}' but recovery-mode projection refresh failed", operation, packageId, failure);
            rethrowFatal(failure);
        }
    }

    private static ExternalPluginOperation operationFor(PluginInstallResult result) {
        return result.previousVersion() == null
                ? ExternalPluginOperation.INSTALLING : ExternalPluginOperation.UPDATING;
    }

    private List<RetiredRuntime> retireReplacedPackages(List<String> replacedIds) {
        List<RetiredRuntime> retired = new java.util.ArrayList<>();
        try {
            for (String replacedId : replacedIds) {
                Path artifact = installer.listInstalled().stream()
                        .filter(plugin -> replacedId.equals(plugin.id()))
                        .map(InstalledPlugin::path)
                        .findFirst()
                        .orElse(null);
                boolean wasLoaded = runtimeManager.packagePhases().containsKey(replacedId);
                RetiredRuntime runtime = new RetiredRuntime(replacedId, artifact, wasLoaded);
                retired.add(runtime);
                if (wasLoaded) {
                    unloadExclusive(replacedId);
                }
            }
            return List.copyOf(retired);
        } catch (Throwable failure) {
            DeferredFailure failures = new DeferredFailure(failure);
            if (!restoreRetiredRuntimes(retired, failures)) {
                failures.record(new PluginLifecycleException("replaced plugin runtime recovery failed"));
            }
            throw propagateFailure("failed to retire replaced plugin runtimes", failures.primary());
        }
    }

    private boolean restoreRetiredRuntimes(List<RetiredRuntime> retired, DeferredFailure failures) {
        boolean restored = true;
        for (RetiredRuntime runtime : retired) {
            if (runtime.wasLoaded() && runtime.artifact() != null) {
                restored = restoreOldRuntime(runtime.packageId(), runtime.artifact(), failures) && restored;
            }
        }
        return restored;
    }

    private <T> T withReplacementLocks(PreparedPluginTransaction prepared,
                                       ExternalPluginOperation operation,
                                       Operation<T> action) {
        List<String> ids = prepared.result().descriptor().replaces().stream().sorted().toList();
        List<ReentrantLock> acquired = new java.util.ArrayList<>();
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

    private record RetiredRuntime(String packageId, Path artifact, boolean wasLoaded) {
    }

    private PluginRuntimePhase currentPhase(String packageId) {
        return packageId == null ? null : lifecycleService.phase(packageId).orElse(null);
    }

    private void requireActivationDependencies(String packageId) {
        var descriptor = dependencyResolver.activationDescriptor(packageId)
                .orElseThrow(() -> new ClassifiedPluginLifecycleException(PluginManagementErrorCode.UNKNOWN_PLUGIN,
                        "installed artifact not found: " + packageId));
        List<PluginDependencyProblem> problems = dependencyResolver.activationProblems(descriptor);
        if (!problems.isEmpty()) {
            throw new ClassifiedPluginLifecycleException(PluginManagementErrorCode.DEPENDENCY_UNSATISFIED,
                    problems.get(0).detail());
        }
    }

    private boolean cleanupCurrentGeneration(String packageId, DeferredFailure failures) {
        try {
            if (!runtimeManager.packagePhases().containsKey(packageId)) {
                return true;
            }
            if (lifecycleService.managedPluginIds().contains(packageId)) {
                unloadExclusive(packageId);
            } else {
                runtimeManager.unloadPlugin(packageId);
            }
            return !runtimeManager.packagePhases().containsKey(packageId);
        } catch (Throwable cleanupFailure) {
            failures.record(cleanupFailure);
            return false;
        }
    }

    private boolean restoreUnchangedOld(
            String packageId, boolean wasLoaded, Path previousArtifact, DeferredFailure failures) {
        if (!wasLoaded) {
            return true;
        }
        return restoreOldRuntime(packageId, previousArtifact, failures);
    }

    private boolean restoreOldRuntime(String packageId, Path previousArtifact, DeferredFailure failures) {
        try {
            if (runtimeManager.packagePhases().containsKey(packageId)) {
                if (lifecycleService.phase(packageId).orElse(null) == PluginRuntimePhase.UNLOADED) {
                    lifecycleService.load(packageId);
                }
            } else {
                loadExclusive(packageId, previousArtifact);
            }
            startExclusive(packageId);
            return true;
        } catch (Throwable rollbackFailure) {
            failures.record(rollbackFailure);
            return false;
        }
    }

    public Map<String, ExternalPluginOperationSnapshot> operations() {
        return Map.copyOf(operations);
    }

    private void stopExclusive(String packageId) {
        lifecycleService.stop(packageId);
        runtimeManager.stopPlugin(packageId);
        PluginRuntimePhase phase = lifecycleService.phase(packageId).orElse(null);
        if (phase != PluginRuntimePhase.STOPPED && phase != PluginRuntimePhase.LOADED) {
            throw new PluginLifecycleException("plugin '" + packageId
                    + "' expected phase STOPPED or LOADED but is " + phase);
        }
    }

    /** PF4J 先启动；应用足迹启动失败时立即把 PF4J 恢复为停止态，避免两套状态分叉。 */
    private void startExclusive(String packageId) {
        try {
            runtimeManager.startPlugin(packageId);
            lifecycleService.start(packageId);
            requirePhase(packageId, PluginRuntimePhase.STARTED);
        } catch (Throwable failure) {
            DeferredFailure failures = new DeferredFailure(failure);
            try {
                lifecycleService.stop(packageId);
            } catch (Throwable cleanupFailure) {
                failures.record(cleanupFailure);
            }
            try {
                runtimeManager.stopPlugin(packageId);
            } catch (Throwable cleanupFailure) {
                failures.record(cleanupFailure);
            }
            throw propagateFailure(
                    "failed to start application footprint for '" + packageId + "'", failures.primary());
        }
    }

    private UnloadedPluginPackage unloadExclusive(String packageId) {
        if (!runtimeManager.packagePhases().containsKey(packageId)
                && lifecycleService.phase(packageId).orElse(null) == PluginRuntimePhase.UNLOADED) {
            InstalledPlugin installed = installer.listInstalled().stream()
                    .filter(plugin -> packageId.equals(plugin.id())).findFirst()
                    .orElseThrow(() -> new PluginLifecycleException("installed artifact not found: " + packageId));
            return new UnloadedPluginPackage(packageId, installed.path(), installed.version(), 0L);
        }
        List<String> blockers = runtimeManager.activeDependents(packageId);
        if (!blockers.isEmpty()) {
            throw new ClassifiedPluginLifecycleException(PluginManagementErrorCode.DEPENDENCY_BLOCKED,
                    "plugin package '" + packageId
                    + "' is required by: " + String.join(", ", blockers));
        }
        stopExclusive(packageId);
        long generation = lifecycleService.generation(packageId).orElseThrow(() ->
                new PluginLifecycleException("missing managed generation for " + packageId));
        lifecycleService.unload(packageId);
        if (lifecycleService.coreRegistrationPresent(packageId, generation)) {
            throw new PluginLifecycleException(
                    "refusing physical unload while exact core registration remains: " + packageId);
        }
        try {
            UnloadedPluginPackage unloaded = runtimeManager.unloadPlugin(packageId);
            lifecycleService.forgetUnloadedGeneration(packageId, generation);
            return unloaded;
        } catch (Throwable failure) {
            DeferredFailure failures = new DeferredFailure(failure);
            // wrapper 仍在时恢复核心注册记录；若 PF4J 已先移除 wrapper，则旧 generation 已不可恢复，
            // 只能清除应用侧强引用并把物理卸载失败如实上报。
            boolean wrapperPresent = packagePresentAfterFailure(packageId, failures);
            if (wrapperPresent) {
                try {
                    lifecycleService.load(packageId);
                } catch (Throwable restoreFailure) {
                    failures.record(restoreFailure);
                }
            } else {
                try {
                    lifecycleService.forgetUnloadedGeneration(packageId, generation);
                } catch (Throwable cleanupFailure) {
                    failures.record(cleanupFailure);
                }
            }
            refreshRecoveryModeSafely(failures);
            Throwable primaryFailure = failures.primary();
            rethrowFatal(primaryFailure);
            throw new ClassifiedPluginLifecycleException(PluginManagementErrorCode.PHYSICAL_UNLOAD_FAILED,
                    "physical unload failed for plugin package '" + packageId + "'", primaryFailure);
        }
    }

    private void loadExclusive(String packageId, Path artifact) {
        LoadedPluginPackage loaded = runtimeManager.loadPlugin(artifact);
        if (!packageId.equals(loaded.packageId())) {
            PluginLifecycleException mismatch = new PluginLifecycleException(
                    "artifact package id mismatch: expected " + packageId + ", got " + loaded.packageId());
            DeferredFailure failures = new DeferredFailure(mismatch);
            try {
                runtimeManager.unloadPlugin(loaded.packageId());
            } catch (Throwable cleanupFailure) {
                failures.record(cleanupFailure);
            }
            throw propagateFailure("failed to clean up mismatched plugin package", failures.primary());
        }
        try {
            lifecycleService.adoptLoadedPackage(loaded);
        } catch (Throwable failure) {
            DeferredFailure failures = new DeferredFailure(failure);
            try {
                runtimeManager.unloadPlugin(packageId);
            } catch (Throwable cleanupFailure) {
                failures.record(cleanupFailure);
            }
            throw propagateFailure(
                    "failed to adopt loaded plugin package '" + packageId + "'", failures.primary());
        }
    }

    private Path installedArtifact(String packageId) {
        List<InstalledPlugin> matches = installer.listInstalled().stream()
                .filter(plugin -> packageId.equals(plugin.id())).toList();
        if (matches.size() != 1) {
            throw new PluginLifecycleException("expected exactly one installed artifact for '" + packageId
                    + "', found " + matches.size());
        }
        return matches.get(0).path();
    }

    private void rollbackReload(String packageId, Path artifact, Throwable original) {
        DeferredFailure failures = new DeferredFailure(original);
        try {
            boolean currentGenerationCleared = cleanupCurrentGeneration(packageId, failures);
            if (!currentGenerationCleared || !restoreOldRuntime(packageId, artifact, failures)) {
                throw new PluginLifecycleException("previous generation could not be restored");
            }
        } catch (Throwable rollbackFailure) {
            failures.record(rollbackFailure);
            operations.put(packageId, new ExternalPluginOperationSnapshot(packageId,
                    ExternalPluginOperation.FAILED, currentTransaction(packageId),
                    "reload failed: " + describe(original) + ": rollback failed: " + describe(rollbackFailure)));
            refreshRecoveryModeSafely(failures);
            throw propagateFailure("reload and rollback failed for '" + packageId + "'", failures.primary());
        }
        throw propagateFailure(
                "reload failed for '" + packageId + "'; previous code restored", failures.primary());
    }

    private void requirePhase(String packageId, PluginRuntimePhase expected) {
        PluginRuntimePhase actual = lifecycleService.phase(packageId).orElse(null);
        if (actual != expected) {
            throw new PluginLifecycleException("plugin '" + packageId + "' expected phase " + expected
                    + " but is " + actual);
        }
    }

    private String currentTransaction(String packageId) {
        ExternalPluginOperationSnapshot snapshot = operations.get(packageId);
        return snapshot != null ? snapshot.transactionId() : null;
    }

    private boolean rollbackTransactionSafely(
            CommittedPluginTransaction committed, DeferredFailure failures) {
        try {
            return installer.rollbackTransaction(committed);
        } catch (Throwable rollbackFailure) {
            failures.record(rollbackFailure);
            return false;
        }
    }

    private void deferRollbackUntilRestartSafely(
            CommittedPluginTransaction committed, DeferredFailure failures) {
        try {
            installer.deferRollbackUntilRestart(committed, failures.primary());
        } catch (Throwable deferFailure) {
            failures.record(deferFailure);
        }
    }

    private boolean recoverPreparedTransaction(
            PreparedPluginTransaction prepared, DeferredFailure failures) {
        return switch (prepared.commitState()) {
            case ROLLED_BACK, DISCARDED -> true;
            case PREPARED -> discardPreparedSafely(prepared, failures);
            case COMMITTED, UNSAFE -> false;
        };
    }

    private boolean discardPreparedSafely(PreparedPluginTransaction prepared, DeferredFailure failures) {
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

    private void refreshRecoveryModeSafely(DeferredFailure failures) {
        try {
            recoveryModeService.refresh();
        } catch (Throwable refreshFailure) {
            failures.record(refreshFailure);
        }
    }

    private boolean packagePresentAfterFailure(String packageId, DeferredFailure failures) {
        try {
            return runtimeManager.packagePhases().containsKey(packageId);
        } catch (Throwable inspectionFailure) {
            failures.record(inspectionFailure);
            return true;
        }
    }

    private static RuntimeException propagateFailure(String message, Throwable failure) {
        rethrowFatal(failure);
        if (failure instanceof RuntimeException runtimeFailure) {
            return runtimeFailure;
        }
        return new PluginLifecycleException(message + " (failureType="
                + failure.getClass().getName() + ")", failure);
    }

    private static void rethrowFatal(Throwable failure) {
        if (failure instanceof VirtualMachineError fatal) {
            throw fatal;
        }
        if (failure instanceof ThreadDeath fatal) {
            throw fatal;
        }
    }

    private static void addSuppressedSafely(Throwable target, Throwable suppressed) {
        if (target == null || suppressed == null || target == suppressed) {
            return;
        }
        try {
            target.addSuppressed(suppressed);
        } catch (Throwable ignored) {
            // 诊断附加失败不得覆盖主失败。
        }
    }

    /** 延后 JVM 致命失败直到补偿动作全部尝试完；原失败已致命时始终保留其对象身份。 */
    private static final class DeferredFailure {
        private Throwable primary;

        private DeferredFailure(Throwable failure) {
            this.primary = failure;
        }

        private void record(Throwable failure) {
            if (!isFatal(primary) && isFatal(failure)) {
                Throwable previous = primary;
                primary = failure;
                addSuppressedSafely(primary, previous);
                return;
            }
            addSuppressedSafely(primary, failure);
        }

        private Throwable primary() {
            return primary;
        }
    }

    private static boolean isFatal(Throwable failure) {
        return failure instanceof VirtualMachineError || failure instanceof ThreadDeath;
    }

    private static String describe(Throwable failure) {
        if (failure == null) {
            return "unknown failure";
        }
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getName() : message;
    }

    private <T> T withLock(String packageId, ExternalPluginOperation operation, Operation<T> action) {
        return withLock(packageId, operation, UUID.randomUUID().toString(), action);
    }

    private <T> T withLock(String packageId, ExternalPluginOperation operation,
                           String transactionId, Operation<T> action) {
        if (!lifecycleMutationLock.tryLock()) {
            throw new ClassifiedPluginLifecycleException(PluginManagementErrorCode.OPERATION_IN_PROGRESS,
                    "another plugin lifecycle mutation is already in progress");
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
                            ExternalPluginOperation.IDLE, transactionId, describe(failure)));
                }
                throw propagateFailure("plugin operation failed for '" + packageId + "'", failure);
            } finally {
                lock.unlock();
            }
        } finally {
            lifecycleMutationLock.unlock();
        }
    }

    @FunctionalInterface
    private interface Operation<T> {
        T run();
    }
}
