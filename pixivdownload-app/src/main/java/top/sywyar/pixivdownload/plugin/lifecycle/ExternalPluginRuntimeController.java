package top.sywyar.pixivdownload.plugin.lifecycle;

import top.sywyar.pixivdownload.plugin.management.PluginManagementErrorCode;
import top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeManager;
import top.sywyar.pixivdownload.plugin.runtime.install.ExternalPluginInstaller;
import top.sywyar.pixivdownload.plugin.runtime.install.model.InstalledPlugin;
import top.sywyar.pixivdownload.plugin.runtime.lifecycle.LoadedPluginPackage;
import top.sywyar.pixivdownload.plugin.runtime.lifecycle.UnloadedPluginPackage;
import top.sywyar.pixivdownload.plugin.recovery.RecoveryModeService;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** 保持 PF4J 物理包与应用 serving 足迹同步的运行期原语 owner。 */
final class ExternalPluginRuntimeController {

    private final PluginRuntimeManager runtimeManager;
    private final PluginLifecycleService lifecycleService;
    private final ExternalPluginInstaller installer;
    private final RecoveryModeService recoveryModeService;

    ExternalPluginRuntimeController(
            PluginRuntimeManager runtimeManager,
            PluginLifecycleService lifecycleService,
            ExternalPluginInstaller installer,
            RecoveryModeService recoveryModeService
    ) {
        this.runtimeManager = runtimeManager;
        this.lifecycleService = lifecycleService;
        this.installer = installer;
        this.recoveryModeService = recoveryModeService;
    }

    boolean isLoaded(String packageId) {
        return runtimeManager.packagePhases().containsKey(packageId);
    }

    Optional<Path> artifactPath(String packageId) {
        return runtimeManager.artifactPath(packageId);
    }

    Optional<Long> generation(String packageId) {
        return lifecycleService.generation(packageId);
    }

    PluginRuntimePhase phase(String packageId) {
        return packageId == null ? null : lifecycleService.phase(packageId).orElse(null);
    }

    void forgetInstallation(String packageId) {
        lifecycleService.forgetInstallation(packageId);
    }

    void stop(String packageId) {
        lifecycleService.stop(packageId);
        runtimeManager.stopPlugin(packageId);
        PluginRuntimePhase phase = lifecycleService.phase(packageId).orElse(null);
        if (phase != PluginRuntimePhase.STOPPED && phase != PluginRuntimePhase.LOADED) {
            throw new PluginLifecycleException("plugin '" + packageId
                    + "' expected phase STOPPED or LOADED but is " + phase);
        }
    }

    void quiesce(String packageId) {
        lifecycleService.quiesce(packageId);
    }

    /** PF4J 先启动；应用足迹启动失败时恢复停止态，避免两套状态分叉。 */
    void start(String packageId) {
        try {
            runtimeManager.startPlugin(packageId);
            lifecycleService.start(packageId);
            requirePhase(packageId, PluginRuntimePhase.STARTED);
        } catch (Throwable failure) {
            PluginLifecycleFailureAccumulator failures = new PluginLifecycleFailureAccumulator(failure);
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
            throw failures.propagate("failed to start application footprint for '" + packageId + "'");
        }
    }

    UnloadedPluginPackage unload(String packageId) {
        if (!isLoaded(packageId) && phase(packageId) == PluginRuntimePhase.UNLOADED) {
            InstalledPlugin installed = installer.listInstalled().stream()
                    .filter(plugin -> packageId.equals(plugin.id())).findFirst()
                    .orElseThrow(() -> new PluginLifecycleException(
                            "installed artifact not found: " + packageId));
            return new UnloadedPluginPackage(packageId, installed.path(), installed.version(), 0L);
        }
        List<String> blockers = runtimeManager.activeDependents(packageId);
        if (!blockers.isEmpty()) {
            throw new ClassifiedPluginLifecycleException(PluginManagementErrorCode.DEPENDENCY_BLOCKED,
                    "plugin package '" + packageId
                            + "' is required by: " + String.join(", ", blockers));
        }
        stop(packageId);
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
            PluginLifecycleFailureAccumulator failures = new PluginLifecycleFailureAccumulator(failure);
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
            try {
                recoveryModeService.refresh();
            } catch (Throwable refreshFailure) {
                failures.record(refreshFailure);
            }
            failures.rethrowFatal();
            throw new ClassifiedPluginLifecycleException(PluginManagementErrorCode.PHYSICAL_UNLOAD_FAILED,
                    "physical unload failed for plugin package '" + packageId + "'", failures.primary());
        }
    }

    void load(String packageId, Path artifact) {
        LoadedPluginPackage loaded = runtimeManager.loadPlugin(artifact);
        if (!packageId.equals(loaded.packageId())) {
            PluginLifecycleFailureAccumulator failures = new PluginLifecycleFailureAccumulator(
                    new PluginLifecycleException(
                            "artifact package id mismatch: expected " + packageId
                                    + ", got " + loaded.packageId()));
            try {
                runtimeManager.unloadPlugin(loaded.packageId());
            } catch (Throwable cleanupFailure) {
                failures.record(cleanupFailure);
            }
            throw failures.propagate("failed to clean up mismatched plugin package");
        }
        try {
            lifecycleService.adoptLoadedPackage(runtimeManager.initializePlugin(packageId));
        } catch (Throwable failure) {
            PluginLifecycleFailureAccumulator failures = new PluginLifecycleFailureAccumulator(failure);
            try {
                runtimeManager.unloadPlugin(packageId);
            } catch (Throwable cleanupFailure) {
                failures.record(cleanupFailure);
            }
            throw failures.propagate("failed to adopt loaded plugin package '" + packageId + "'");
        }
    }

    Path installedArtifact(String packageId) {
        List<InstalledPlugin> matches = installer.listInstalled().stream()
                .filter(plugin -> packageId.equals(plugin.id())).toList();
        if (matches.size() != 1) {
            throw new PluginLifecycleException("expected exactly one installed artifact for '" + packageId
                    + "', found " + matches.size());
        }
        return matches.get(0).path();
    }

    boolean cleanupCurrentGeneration(String packageId) {
        if (!isLoaded(packageId)) {
            return true;
        }
        if (lifecycleService.managedPluginIds().contains(packageId)) {
            unload(packageId);
        } else {
            runtimeManager.unloadPlugin(packageId);
        }
        return !isLoaded(packageId);
    }

    void restoreOldRuntime(String packageId, Path previousArtifact) {
        if (isLoaded(packageId)) {
            if (phase(packageId) == PluginRuntimePhase.UNLOADED) {
                lifecycleService.load(packageId);
            }
        } else {
            load(packageId, previousArtifact);
        }
        start(packageId);
    }

    private boolean packagePresentAfterFailure(
            String packageId,
            PluginLifecycleFailureAccumulator failures
    ) {
        try {
            return isLoaded(packageId);
        } catch (Throwable inspectionFailure) {
            failures.record(inspectionFailure);
            return true;
        }
    }

    private void requirePhase(String packageId, PluginRuntimePhase expected) {
        PluginRuntimePhase actual = phase(packageId);
        if (actual != expected) {
            throw new PluginLifecycleException("plugin '" + packageId + "' expected phase " + expected
                    + " but is " + actual);
        }
    }
}
