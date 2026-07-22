package top.sywyar.pixivdownload.plugin.lifecycle;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.install.PluginDependencyResolver;
import top.sywyar.pixivdownload.plugin.recovery.RecoveryModeService;
import top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeManager;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginApiRequirement;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDescriptor;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginLifecyclePolicy;
import top.sywyar.pixivdownload.plugin.runtime.install.ExternalPluginInstaller;
import top.sywyar.pixivdownload.plugin.runtime.install.model.InstalledPlugin;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginInstallOutcome;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginInstallResult;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageOrigin;
import top.sywyar.pixivdownload.plugin.runtime.install.transaction.CommittedPluginTransaction;
import top.sywyar.pixivdownload.plugin.runtime.install.transaction.PreparedPluginTransaction;
import top.sywyar.pixivdownload.plugin.runtime.lifecycle.LoadedPluginPackage;
import top.sywyar.pixivdownload.plugin.runtime.lifecycle.PluginRuntimePackagePhase;
import top.sywyar.pixivdownload.plugin.runtime.lifecycle.UnloadedPluginPackage;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("外置插件替代生命周期")
class ExternalPluginLifecycleCoordinatorReplacementTest {

    @Mock
    PluginRuntimeManager runtimeManager;
    @Mock
    PluginLifecycleService lifecycleService;
    @Mock
    ExternalPluginInstaller installer;
    @Mock
    RecoveryModeService recoveryModeService;
    @Mock
    PluginDependencyResolver dependencyResolver;

    @Test
    @DisplayName("提交替代包前先停止并物理卸载精确声明的旧插件")
    void unloadsReplacedRuntimeBeforeCommittingArtifactTransaction() {
        String retiredId = "legacy-theme";
        Path retiredArtifact = Path.of("plugins", "legacy-theme-1.0.0.jar");
        PluginDescriptor descriptor = new PluginDescriptor("gui-theme", "gui-theme", "1.1.0",
                PluginApiRequirement.parse("1.0"), List.of(), "example.ThemePlugin", null,
                "theme.label", null, null, null, PluginKind.FEATURE, List.of(retiredId),
                PluginLifecyclePolicy.PROCESS_RESTART);
        PluginInstallResult result = new PluginInstallResult(PluginInstallOutcome.INSTALLED, descriptor,
                Path.of("plugins", "gui-theme-1.1.0.jar"), null, List.of());
        PreparedPluginTransaction prepared = new PreparedPluginTransaction("tx", result,
                Path.of("plugins", ".staging", "tx"), Path.of("plugins", ".staging", "tx", "new.jar"),
                result.installedPath(), List.of(retiredArtifact));
        CommittedPluginTransaction committed = new CommittedPluginTransaction(prepared, List.of());

        when(installer.prepareTransaction(prepared.stagedArtifact(), false, PluginPackageOrigin.localUpload()))
                .thenReturn(prepared);
        when(dependencyResolver.activationProblems(descriptor)).thenReturn(List.of());
        when(installer.listInstalled()).thenReturn(List.of(new InstalledPlugin(
                new PluginDescriptor(retiredId, retiredId, "1.0.0", PluginApiRequirement.parse("1.0"),
                        List.of(), "example.LegacyThemePlugin", null, "legacy.label", null, null, null,
                        PluginKind.FEATURE), retiredArtifact)));
        when(runtimeManager.packagePhases()).thenReturn(Map.of(retiredId, PluginRuntimePackagePhase.STARTED));
        when(runtimeManager.activeDependents(retiredId)).thenReturn(List.of());
        when(lifecycleService.phase("gui-theme")).thenReturn(Optional.empty());
        when(lifecycleService.phase(retiredId)).thenReturn(Optional.of(PluginRuntimePhase.STOPPED));
        when(lifecycleService.generation(retiredId)).thenReturn(Optional.of(7L));
        when(runtimeManager.unloadPlugin(retiredId)).thenReturn(
                new UnloadedPluginPackage(retiredId, retiredArtifact, "1.0.0", 7L));
        when(installer.commitTransaction(prepared)).thenReturn(committed);

        var activation = new ExternalPluginLifecycleCoordinator(runtimeManager, lifecycleService, installer,
                recoveryModeService, dependencyResolver)
                .installOrUpdate(prepared.stagedArtifact(), false, PluginPackageOrigin.localUpload());

        assertThat(activation.installResult().accepted()).isTrue();
        InOrder order = inOrder(lifecycleService, runtimeManager, installer);
        order.verify(installer).verifyCurrentArtifacts(prepared);
        order.verify(lifecycleService).stop(retiredId);
        order.verify(runtimeManager).stopPlugin(retiredId);
        order.verify(lifecycleService).unload(retiredId);
        order.verify(runtimeManager).unloadPlugin(retiredId);
        order.verify(installer).commitTransaction(prepared);
    }

    @Test
    @DisplayName("替代插件 wrapper 已移除后卸载失败仍恢复该旧运行时代际")
    void restoresCurrentReplacementWhenUnloadRemovedWrapperThenFailed() {
        String retiredId = "legacy-runtime";
        Path retiredArtifact = Path.of("plugins", "legacy-runtime-1.0.0.jar");
        PluginDescriptor descriptor = new PluginDescriptor("replacement", "replacement", "2.0.0",
                PluginApiRequirement.parse("1.0"), List.of(), "example.ReplacementPlugin", null,
                "replacement.label", null, null, null, PluginKind.FEATURE, List.of(retiredId),
                PluginLifecyclePolicy.PROCESS_RESTART);
        PluginInstallResult result = new PluginInstallResult(PluginInstallOutcome.INSTALLED, descriptor,
                Path.of("plugins", "replacement-2.0.0.jar"), null, List.of());
        PreparedPluginTransaction prepared = new PreparedPluginTransaction("tx-restore-retired", result,
                Path.of("plugins", ".staging", "tx-restore-retired"),
                Path.of("plugins", ".staging", "tx-restore-retired", "new.jar"),
                result.installedPath(), List.of(retiredArtifact));
        InstalledPlugin retired = new InstalledPlugin(
                new PluginDescriptor(retiredId, retiredId, "1.0.0", PluginApiRequirement.parse("1.0"),
                        List.of(), "example.LegacyPlugin", null, "legacy.label", null, null, null,
                        PluginKind.FEATURE), retiredArtifact);
        LoadedPluginPackage reloaded = mock(LoadedPluginPackage.class);
        when(reloaded.packageId()).thenReturn(retiredId);
        when(installer.prepareTransaction(
                prepared.stagedArtifact(), false, PluginPackageOrigin.localUpload())).thenReturn(prepared);
        when(dependencyResolver.activationProblems(descriptor)).thenReturn(List.of());
        when(installer.listInstalled()).thenReturn(List.of(retired));
        when(runtimeManager.packagePhases()).thenReturn(
                Map.of(retiredId, PluginRuntimePackagePhase.STARTED),
                Map.of(retiredId, PluginRuntimePackagePhase.STARTED),
                Map.of(),
                Map.of());
        when(runtimeManager.activeDependents(retiredId)).thenReturn(List.of());
        when(lifecycleService.phase("replacement")).thenReturn(Optional.empty());
        when(lifecycleService.phase(retiredId)).thenReturn(
                Optional.of(PluginRuntimePhase.STOPPED),
                Optional.of(PluginRuntimePhase.STARTED));
        when(lifecycleService.generation(retiredId)).thenReturn(Optional.of(7L));
        doThrow(new AssertionError("wrapper removed before unload failure"))
                .when(runtimeManager).unloadPlugin(retiredId);
        when(runtimeManager.loadPlugin(retiredArtifact)).thenReturn(reloaded);
        when(installer.discardPrepared(prepared)).thenReturn(true);

        var activation = new ExternalPluginLifecycleCoordinator(
                runtimeManager, lifecycleService, installer, recoveryModeService, dependencyResolver)
                .installOrUpdate(prepared.stagedArtifact(), false, PluginPackageOrigin.localUpload());

        assertThat(activation.installResult().outcome()).isEqualTo(PluginInstallOutcome.FAILED);
        verify(runtimeManager).loadPlugin(retiredArtifact);
        verify(lifecycleService).adoptLoadedPackage(reloaded);
        verify(runtimeManager).startPlugin(retiredId);
        verify(lifecycleService).start(retiredId);
        verify(installer).discardPrepared(prepared);
        verify(installer, never()).commitTransaction(prepared);
    }
}
