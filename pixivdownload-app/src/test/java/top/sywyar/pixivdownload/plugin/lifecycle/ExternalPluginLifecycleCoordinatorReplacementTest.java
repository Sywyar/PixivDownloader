package top.sywyar.pixivdownload.plugin.lifecycle;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.install.PluginDependencyResolver;
import top.sywyar.pixivdownload.plugin.recovery.RecoveryModeService;
import top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeManager;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.VersionRequirement;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

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

    @ParameterizedTest
    @EnumSource(PluginLifecyclePolicy.class)
    @DisplayName("替代包无论采用何种策略都保留全部已加载的进程重启旧代")
    void preservesProcessRestartReplacements(PluginLifecyclePolicy incomingPolicy) throws Exception {
        var replaced = List.of("first-provider", "second-provider");
        var descriptor = descriptor("replacement-provider", incomingPolicy, replaced);
        var prepared = prepared(descriptor, null);
        var committed = new CommittedPluginTransaction(prepared, List.of());
        when(installer.prepareTransaction(prepared.stagedArtifact(), false, PluginPackageOrigin.localUpload()))
                .thenReturn(prepared);
        when(dependencyResolver.activationProblems(descriptor)).thenReturn(List.of());
        when(runtimeManager.loadedDescriptor(anyString())).thenAnswer(call -> {
            String id = call.getArgument(0);
            return replaced.contains(id)
                    ? Optional.of(descriptor(id, PluginLifecyclePolicy.PROCESS_RESTART, List.of())) : Optional.empty();
        });
        for (String id : replaced) {
            when(runtimeManager.activeDependents(id)).thenReturn(List.of());
        }
        when(installer.commitTransaction(prepared)).thenReturn(committed);

        var result = coordinator().installOrUpdate(prepared.stagedArtifact(), false, PluginPackageOrigin.localUpload());

        assertThat(result.installResult().accepted()).isTrue();
        assertThat(result.activated()).isFalse();
        verify(installer).verifyCommittedTarget(committed);
        verify(installer).markActivated(committed);
        verify(installer).completeTransaction(committed);
        verifyNoRuntimeMutation();
    }

    @ParameterizedTest
    @EnumSource(value = PluginLifecyclePolicy.class, names = {"HOT_RELOAD", "BACKEND_RESTART"})
    @DisplayName("同 id 新包降低重启策略时仍保留当前进程重启代际")
    void policyChangeDoesNotHotReplaceCurrentGeneration(PluginLifecyclePolicy incomingPolicy) throws Exception {
        var descriptor = descriptor("selected-provider", incomingPolicy, List.of());
        var prepared = prepared(descriptor, "4.2.0");
        var committed = new CommittedPluginTransaction(prepared, List.of());
        when(installer.prepareTransaction(prepared.stagedArtifact(), false, PluginPackageOrigin.localUpload()))
                .thenReturn(prepared);
        when(dependencyResolver.activationProblems(descriptor)).thenReturn(List.of());
        when(runtimeManager.loadedDescriptor(descriptor.id())).thenReturn(Optional.of(
                descriptor(descriptor.id(), PluginLifecyclePolicy.PROCESS_RESTART, List.of())));
        when(lifecycleService.phase(descriptor.id())).thenReturn(Optional.of(PluginRuntimePhase.STARTED));
        when(installer.commitTransaction(prepared)).thenReturn(committed);

        var result = coordinator().installOrUpdate(prepared.stagedArtifact(), false, PluginPackageOrigin.localUpload());

        assertThat(result.installResult().accepted()).isTrue();
        assertThat(result.activated()).isFalse();
        assertThat(result.runtimePhase()).isEqualTo(PluginRuntimePhase.STARTED);
        verify(installer).completeTransaction(committed);
        verifyNoRuntimeMutation();
    }

    @Test
    @DisplayName("延迟替代提交失败只恢复文件，不停止或重新启动被保留的旧代")
    void failedDeferredReplacementLeavesGenerationUntouched() throws Exception {
        String retained = "selected-provider";
        var descriptor = descriptor("replacement-provider", PluginLifecyclePolicy.HOT_RELOAD, List.of(retained));
        var prepared = prepared(descriptor, null);
        when(installer.prepareTransaction(prepared.stagedArtifact(), false, PluginPackageOrigin.localUpload()))
                .thenReturn(prepared);
        when(dependencyResolver.activationProblems(descriptor)).thenReturn(List.of());
        when(runtimeManager.loadedDescriptor(anyString())).thenAnswer(call -> retained.equals(call.getArgument(0))
                ? Optional.of(descriptor(retained, PluginLifecyclePolicy.PROCESS_RESTART, List.of())) : Optional.empty());
        when(runtimeManager.activeDependents(retained)).thenReturn(List.of());
        when(installer.commitTransaction(prepared)).thenThrow(new IllegalStateException("fixture commit failure"));
        when(installer.discardPrepared(prepared)).thenReturn(true);

        var result = coordinator().installOrUpdate(prepared.stagedArtifact(), false, PluginPackageOrigin.localUpload());

        assertThat(result.installResult().outcome()).isEqualTo(PluginInstallOutcome.FAILED);
        assertThat(result.activated()).isFalse();
        assertThat(result.rolledBack()).isTrue();
        verify(installer).discardPrepared(prepared);
        verifyNoRuntimeMutation();
    }

    private void verifyNoRuntimeMutation() {
        verify(runtimeManager, never()).stopPlugin(anyString());
        verify(runtimeManager, never()).unloadPlugin(anyString());
        verify(runtimeManager, never()).loadPlugin(any());
        verify(runtimeManager, never()).startPlugin(anyString());
        verify(lifecycleService, never()).stop(anyString());
        verify(lifecycleService, never()).unload(anyString());
        verify(lifecycleService, never()).start(anyString());
    }

    private ExternalPluginLifecycleCoordinator coordinator() {
        return new ExternalPluginLifecycleCoordinator(runtimeManager, lifecycleService, installer,
                recoveryModeService, dependencyResolver);
    }

    private static PreparedPluginTransaction prepared(PluginDescriptor descriptor, String previousVersion) {
        Path staged = Path.of("plugins", ".staging", "retained-generation", "new.jar");
        var result = new PluginInstallResult(PluginInstallOutcome.INSTALLED, descriptor,
                Path.of("plugins", descriptor.id() + ".jar"), previousVersion, List.of());
        return new PreparedPluginTransaction("retained-generation", result, staged.getParent(), staged,
                result.installedPath(), List.of());
    }

    private static PluginDescriptor descriptor(String id, PluginLifecyclePolicy policy, List<String> replaces) {
        return new PluginDescriptor(id, id, "5.3.0", VersionRequirement.unspecified(), List.of(),
                "example.DatabasePlugin", null, "plugin.label", null, null, null, PluginKind.FEATURE, replaces, policy);
    }

    @Test
    @DisplayName("提交替代包前先停止并物理卸载精确声明的旧插件")
    void unloadsReplacedRuntimeBeforeCommittingArtifactTransaction() {
        String retiredId = "legacy-theme";
        Path retiredArtifact = Path.of("plugins", "legacy-theme-1.0.0.jar");
        PluginDescriptor descriptor = new PluginDescriptor("gui-swing", "gui-swing", "1.1.0",
                VersionRequirement.parse("1.0"), List.of(), "example.ThemePlugin", null,
                "theme.label", null, null, null, PluginKind.FEATURE, List.of(retiredId),
                PluginLifecyclePolicy.PROCESS_RESTART);
        PluginInstallResult result = new PluginInstallResult(PluginInstallOutcome.INSTALLED, descriptor,
                Path.of("plugins", "gui-swing-1.1.0.jar"), null, List.of());
        PreparedPluginTransaction prepared = new PreparedPluginTransaction("tx", result,
                Path.of("plugins", ".staging", "tx"), Path.of("plugins", ".staging", "tx", "new.jar"),
                result.installedPath(), List.of(retiredArtifact));
        CommittedPluginTransaction committed = new CommittedPluginTransaction(prepared, List.of());

        when(installer.prepareTransaction(prepared.stagedArtifact(), false, PluginPackageOrigin.localUpload()))
                .thenReturn(prepared);
        when(dependencyResolver.activationProblems(descriptor)).thenReturn(List.of());
        when(installer.listInstalled()).thenReturn(List.of(new InstalledPlugin(
                new PluginDescriptor(retiredId, retiredId, "1.0.0", VersionRequirement.parse("1.0"),
                        List.of(), "example.LegacyThemePlugin", null, "legacy.label", null, null, null,
                        PluginKind.FEATURE), retiredArtifact)));
        when(runtimeManager.packagePhases()).thenReturn(Map.of(retiredId, PluginRuntimePackagePhase.STARTED));
        when(runtimeManager.activeDependents(retiredId)).thenReturn(List.of());
        when(lifecycleService.phase("gui-swing")).thenReturn(Optional.empty());
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
                VersionRequirement.parse("1.0"), List.of(), "example.ReplacementPlugin", null,
                "replacement.label", null, null, null, PluginKind.FEATURE, List.of(retiredId),
                PluginLifecyclePolicy.PROCESS_RESTART);
        PluginInstallResult result = new PluginInstallResult(PluginInstallOutcome.INSTALLED, descriptor,
                Path.of("plugins", "replacement-2.0.0.jar"), null, List.of());
        PreparedPluginTransaction prepared = new PreparedPluginTransaction("tx-restore-retired", result,
                Path.of("plugins", ".staging", "tx-restore-retired"),
                Path.of("plugins", ".staging", "tx-restore-retired", "new.jar"),
                result.installedPath(), List.of(retiredArtifact));
        InstalledPlugin retired = new InstalledPlugin(
                new PluginDescriptor(retiredId, retiredId, "1.0.0", VersionRequirement.parse("1.0"),
                        List.of(), "example.LegacyPlugin", null, "legacy.label", null, null, null,
                        PluginKind.FEATURE), retiredArtifact);
        LoadedPluginPackage reloaded = mock(LoadedPluginPackage.class);
        when(reloaded.packageId()).thenReturn(retiredId);
        when(installer.prepareTransaction(
                prepared.stagedArtifact(), false, PluginPackageOrigin.localUpload())).thenReturn(prepared);
        when(dependencyResolver.activationProblems(descriptor)).thenReturn(List.of());
        when(installer.listInstalled()).thenReturn(List.of(retired));
        AtomicBoolean wrapperPresent = new AtomicBoolean(true);
        AtomicReference<PluginRuntimePhase> phase = new AtomicReference<>(PluginRuntimePhase.STARTED);
        when(runtimeManager.packagePhases()).thenAnswer(ignored -> wrapperPresent.get()
                ? Map.of(retiredId, PluginRuntimePackagePhase.STARTED) : Map.of());
        when(runtimeManager.activeDependents(retiredId)).thenReturn(List.of());
        when(lifecycleService.phase("replacement")).thenReturn(Optional.empty());
        when(lifecycleService.phase(retiredId)).thenAnswer(ignored -> Optional.of(phase.get()));
        doAnswer(ignored -> { phase.set(PluginRuntimePhase.STOPPED); return null; })
                .when(lifecycleService).stop(retiredId);
        doAnswer(ignored -> { phase.set(PluginRuntimePhase.STARTED); return null; })
                .when(lifecycleService).start(retiredId);
        when(lifecycleService.generation(retiredId)).thenReturn(Optional.of(7L));
        doAnswer(ignored -> {
            wrapperPresent.set(false);
            throw new AssertionError("wrapper removed before unload failure");
        })
                .when(runtimeManager).unloadPlugin(retiredId);
        when(runtimeManager.loadPlugin(retiredArtifact)).thenAnswer(ignored -> {
            wrapperPresent.set(true);
            return reloaded;
        });
        when(runtimeManager.initializePlugin(retiredId)).thenReturn(reloaded);
        when(installer.discardPrepared(prepared)).thenReturn(true);

        var activation = new ExternalPluginLifecycleCoordinator(
                runtimeManager, lifecycleService, installer, recoveryModeService, dependencyResolver)
                .installOrUpdate(prepared.stagedArtifact(), false, PluginPackageOrigin.localUpload());

        assertThat(activation.installResult().outcome()).isEqualTo(PluginInstallOutcome.FAILED);
        assertThat(wrapperPresent).isTrue();
        assertThat(phase).hasValue(PluginRuntimePhase.STARTED);
        verify(runtimeManager).unloadPlugin(retiredId);
        verify(runtimeManager).loadPlugin(retiredArtifact);
        verify(lifecycleService).adoptLoadedPackage(reloaded);
        verify(runtimeManager).startPlugin(retiredId);
        verify(lifecycleService).start(retiredId);
        verify(installer).discardPrepared(prepared);
        verify(installer, never()).commitTransaction(prepared);
    }
}
