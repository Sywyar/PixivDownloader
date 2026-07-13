package top.sywyar.pixivdownload.plugin.lifecycle;

import org.springframework.stereotype.Service;
import top.sywyar.pixivdownload.plugin.runtime.lifecycle.LoadedPluginPackage;
import top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeManager;
import top.sywyar.pixivdownload.plugin.runtime.lifecycle.UnloadedPluginPackage;
import top.sywyar.pixivdownload.plugin.runtime.install.ExternalPluginInstaller;
import top.sywyar.pixivdownload.plugin.runtime.install.model.InstalledPlugin;
import top.sywyar.pixivdownload.plugin.runtime.install.transaction.PreparedPluginTransaction;
import top.sywyar.pixivdownload.plugin.runtime.install.transaction.CommittedPluginTransaction;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginInstallOutcome;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginInstallResult;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageOrigin;
import top.sywyar.pixivdownload.plugin.policy.StartupOnlyPlugins;

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
 * 外置插件全部运行期写动作的唯一编排入口。固定顺序为应用足迹清退、PF4J 物理生命周期、代际替换，
 * 并以 packageId 锁阻止同包动作交错。
 */
@Service
public class ExternalPluginLifecycleCoordinator {

    private final PluginRuntimeManager runtimeManager;
    private final PluginLifecycleService lifecycleService;
    private final ExternalPluginInstaller installer;
    private final RecoveryModeService recoveryModeService;
    private final PluginDependencyResolver dependencyResolver;
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
            boolean artifactRemoved = false;
            try {
                if (wasLoaded) {
                    unloadExclusive(packageId);
                }
                if (!installer.removeInstalled(packageId)) {
                    throw new PluginLifecycleException("installed artifact not found: " + packageId);
                }
                artifactRemoved = true;
                lifecycleService.forgetInstallation(packageId);
                recoveryModeService.refresh();
            } catch (Throwable failure) {
                DeferredFailure failures = new DeferredFailure(failure);
                boolean runtimeRestored = !wasLoaded
                        || restoreOldRuntime(packageId, previousArtifact, failures);
                if (artifactRemoved || !runtimeRestored) {
                    operations.put(packageId, new ExternalPluginOperationSnapshot(packageId,
                            ExternalPluginOperation.FAILED, currentTransaction(packageId), describe(failure)));
                }
                if (!runtimeRestored) {
                    throw propagateFailure("remove failed and previous runtime could not be restored for '"
                            + packageId + "'", failures.primary());
                }
                throw propagateFailure("remove failed for '" + packageId + "'", failures.primary());
            }
            return null;
        });
    }

    public Optional<ExternalPluginOperationSnapshot> operation(String packageId) {
        return Optional.ofNullable(operations.get(packageId));
    }

    /**
     * 统一的本地/市场安装更新事务。安全校验在包锁外完成；最终版本复核、卸载、替换、激活和回滚在包锁内完成。
     */
    public PluginActivationResult installOrUpdate(Path packageFile, boolean allowDowngrade,
                                                  PluginPackageOrigin origin) {
        PreparedPluginTransaction prepared = installer.prepareTransaction(packageFile, allowDowngrade, origin);
        PluginInstallResult stagedResult = prepared.result();
        if (stagedResult != null && stagedResult.descriptor() != null && stagedResult.outcome().accepted()) {
            List<PluginDependencyProblem> problems =
                    dependencyResolver.activationProblems(stagedResult.descriptor());
            if (!problems.isEmpty()) {
                return dependencyRejected(prepared, problems);
            }
        }
        if (!prepared.readyToCommit()) {
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
        if (StartupOnlyPlugins.isStartupOnly(packageId)) {
            return withLock(packageId, operation, prepared.transactionId(), () ->
                    withReplacementLocks(prepared, operation, () -> commitStartupOnly(prepared)));
        }
        return withLock(packageId, operation, prepared.transactionId(), () ->
                withReplacementLocks(prepared, operation, () -> activatePrepared(prepared)));
    }

    private PluginActivationResult commitStartupOnly(PreparedPluginTransaction prepared) {
        String packageId = prepared.result().pluginId();
        CommittedPluginTransaction committed = null;
        List<RetiredRuntime> retired = List.of();
        try {
            installer.verifyCurrentArtifacts(prepared);
            retired = retireReplacedPackages(prepared.result().descriptor().replaces());
            committed = installer.commitTransaction(prepared);
            installer.markActivated(committed);
            installer.completeTransaction(committed);
            recoveryModeService.refresh();
            return new PluginActivationResult(prepared.transactionId(), prepared.result(), false, false, null,
                    operationFor(prepared.result()), currentPhase(packageId));
        } catch (Throwable failure) {
            DeferredFailure failures = new DeferredFailure(failure);
            boolean rolledBack = committed != null && rollbackTransactionSafely(committed, failures);
            boolean runtimeRestored = restoreRetiredRuntimes(retired, failures);
            if (committed == null) {
                discardPreparedSafely(prepared, failures);
            }
            refreshRecoveryModeSafely(failures);
            rethrowFatal(failures.primary());
            PluginInstallResult failed = new PluginInstallResult(PluginInstallOutcome.FAILED,
                    prepared.result().descriptor(), null, prepared.result().previousVersion(),
                    List.of("startup-only commit failed: " + describe(failure),
                            rolledBack && runtimeRestored
                                    ? "previous version restored" : "previous version recovery failed"));
            return new PluginActivationResult(prepared.transactionId(), failed, false, rolledBack,
                    rolledBack && runtimeRestored ? prepared.result().previousVersion() : null,
                    operationFor(prepared.result()), currentPhase(packageId));
        }
    }

    private PluginActivationResult dependencyRejected(PreparedPluginTransaction prepared,
                                                      List<PluginDependencyProblem> problems) {
        PluginInstallResult staged = prepared.result();
        String packageId = staged != null ? staged.pluginId() : null;
        if (prepared.readyToCommit()) {
            installer.discardPrepared(prepared);
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
        try {
            // 下载 / 预校验不持包锁；进入停机窗口后先复核安装态，避免过期事务先卸下新 generation。
            installer.verifyCurrentArtifacts(prepared);
            retired = retireReplacedPackages(prepared.result().descriptor().replaces());
            if (wasLoaded) {
                unloadExclusive(packageId);
            }
            committed = installer.commitTransaction(prepared);
            loadExclusive(packageId, prepared.target());
            startExclusive(packageId);
            installer.markActivated(committed);
            installer.completeTransaction(committed);
            recoveryModeService.refresh();
            return new PluginActivationResult(prepared.transactionId(), prepared.result(), true, false, null,
                    operationFor(prepared.result()), PluginRuntimePhase.STARTED);
        } catch (Throwable activationFailure) {
            DeferredFailure failures = new DeferredFailure(activationFailure);
            operations.put(packageId, new ExternalPluginOperationSnapshot(packageId,
                    ExternalPluginOperation.ROLLING_BACK, prepared.transactionId(), describe(activationFailure)));
            cleanupCurrentGeneration(packageId, failures);
            boolean filesRestored = committed == null
                    ? restoreUnchangedOld(packageId, wasLoaded, previousArtifact, failures)
                    : rollbackTransactionSafely(committed, failures);
            boolean runtimeRestored = true;
            if (committed != null && wasLoaded && filesRestored) {
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
            runtimeRestored = restoreRetiredRuntimes(retired, failures) && runtimeRestored;
            if (committed == null) {
                discardPreparedSafely(prepared, failures);
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
            return new PluginActivationResult(prepared.transactionId(), failed, false, rolledBack,
                    rolledBack ? previousVersion : null, operationFor(prepared.result()), currentPhase(packageId));
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
                if (wasLoaded) {
                    unloadExclusive(replacedId);
                }
                retired.add(new RetiredRuntime(replacedId, artifact, wasLoaded));
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

    private void cleanupCurrentGeneration(String packageId, DeferredFailure failures) {
        try {
            if (!runtimeManager.packagePhases().containsKey(packageId)) {
                return;
            }
            if (lifecycleService.managedPluginIds().contains(packageId)) {
                unloadExclusive(packageId);
            } else {
                runtimeManager.unloadPlugin(packageId);
            }
        } catch (Throwable cleanupFailure) {
            failures.record(cleanupFailure);
            // 回滚路径继续尝试恢复文件，最终结果会标记恢复失败。
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
            cleanupCurrentGeneration(packageId, failures);
            if (!restoreOldRuntime(packageId, artifact, failures)) {
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

    private void discardPreparedSafely(PreparedPluginTransaction prepared, DeferredFailure failures) {
        try {
            installer.discardPrepared(prepared);
        } catch (Throwable discardFailure) {
            failures.record(discardFailure);
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
    }

    @FunctionalInterface
    private interface Operation<T> {
        T run();
    }
}
