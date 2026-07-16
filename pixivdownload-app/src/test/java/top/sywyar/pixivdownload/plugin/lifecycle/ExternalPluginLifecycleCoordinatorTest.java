package top.sywyar.pixivdownload.plugin.lifecycle;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.install.PluginActivationResult;
import top.sywyar.pixivdownload.plugin.install.PluginDependencyResolver;
import top.sywyar.pixivdownload.plugin.management.PluginManagementErrorCode;
import top.sywyar.pixivdownload.plugin.recovery.RecoveryModeService;
import top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeManager;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginApiRequirement;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDescriptor;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginLifecyclePolicy;
import top.sywyar.pixivdownload.plugin.runtime.install.ExternalPluginInstaller;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginInstallOutcome;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginInstallResult;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageOrigin;
import top.sywyar.pixivdownload.plugin.runtime.install.transaction.CommittedPluginTransaction;
import top.sywyar.pixivdownload.plugin.runtime.install.transaction.PreparedPluginTransaction;
import top.sywyar.pixivdownload.plugin.runtime.lifecycle.PluginRuntimePackagePhase;
import top.sywyar.pixivdownload.plugin.runtime.lifecycle.LoadedPluginPackage;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("外置插件运行期生命周期协调")
class ExternalPluginLifecycleCoordinatorTest {

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
    @DisplayName("进程重启策略安装只提交文件并延迟运行期激活")
    void processRestartInstallCommitsWithoutRuntimeActivation() {
        String pluginId = "gui-theme";
        Path staged = Path.of("plugins", ".staging", "tx-process", "new.jar");
        Path target = Path.of("plugins", "gui-theme.jar");
        PluginDescriptor descriptor = descriptor(pluginId, PluginLifecyclePolicy.PROCESS_RESTART);
        PluginInstallResult result = new PluginInstallResult(
                PluginInstallOutcome.INSTALLED, descriptor, target, null, List.of());
        PreparedPluginTransaction prepared = new PreparedPluginTransaction(
                "tx-process", result, staged.getParent(), staged, target, List.of());
        CommittedPluginTransaction committed = new CommittedPluginTransaction(prepared, List.of());
        when(installer.prepareTransaction(staged, false, PluginPackageOrigin.localUpload())).thenReturn(prepared);
        when(dependencyResolver.activationProblems(descriptor)).thenReturn(List.of());
        when(installer.commitTransaction(prepared)).thenReturn(committed);
        when(lifecycleService.phase(pluginId)).thenReturn(Optional.empty());

        PluginActivationResult activation = coordinator().installOrUpdate(
                staged, false, PluginPackageOrigin.localUpload());

        assertThat(activation.activated()).isFalse();
        assertThat(activation.installResult().accepted()).isTrue();
        verify(installer).markActivated(committed);
        verify(installer).completeTransaction(committed);
        verify(runtimeManager, never()).loadPlugin(target);
        verify(runtimeManager, never()).startPlugin(pluginId);
        verify(lifecycleService, never()).start(pluginId);
    }

    @Test
    @DisplayName("后端重启策略安装仍即时完成物理与应用激活")
    void backendRestartInstallStillActivatesImmediately() {
        String pluginId = "backend-plugin";
        Path staged = Path.of("plugins", ".staging", "tx-backend", "new.jar");
        Path target = Path.of("plugins", "backend-plugin.jar");
        PluginDescriptor descriptor = descriptor(pluginId, PluginLifecyclePolicy.BACKEND_RESTART);
        PluginInstallResult result = new PluginInstallResult(
                PluginInstallOutcome.INSTALLED, descriptor, target, null, List.of());
        PreparedPluginTransaction prepared = new PreparedPluginTransaction(
                "tx-backend", result, staged.getParent(), staged, target, List.of());
        CommittedPluginTransaction committed = new CommittedPluginTransaction(prepared, List.of());
        LoadedPluginPackage loaded = mock(LoadedPluginPackage.class);
        when(loaded.packageId()).thenReturn(pluginId);
        when(installer.prepareTransaction(staged, false, PluginPackageOrigin.localUpload())).thenReturn(prepared);
        when(dependencyResolver.activationProblems(descriptor)).thenReturn(List.of());
        when(runtimeManager.packagePhases()).thenReturn(Map.of());
        when(installer.commitTransaction(prepared)).thenReturn(committed);
        when(runtimeManager.loadPlugin(target)).thenReturn(loaded);
        when(lifecycleService.phase(pluginId)).thenReturn(Optional.of(PluginRuntimePhase.STARTED));

        PluginActivationResult activation = coordinator().installOrUpdate(
                staged, false, PluginPackageOrigin.localUpload());

        assertThat(activation.activated()).isTrue();
        assertThat(activation.runtimePhase()).isEqualTo(PluginRuntimePhase.STARTED);
        verify(runtimeManager).loadPlugin(target);
        verify(lifecycleService).adoptLoadedPackage(loaded);
        verify(runtimeManager).startPlugin(pluginId);
        verify(lifecycleService).start(pluginId);
    }

    @Test
    @DisplayName("停止后的插件按可激活描述符校验并依次恢复框架与服务足迹")
    void startRestoresStoppedPluginFromActivationDescriptor() {
        String pluginId = "dev-plugin";
        PluginDescriptor descriptor = new PluginDescriptor(pluginId, pluginId, "1.0.0",
                PluginApiRequirement.unspecified(), List.of(), "example.Plugin", null,
                "plugin.label", null, null, null, PluginKind.FEATURE);
        when(dependencyResolver.activationDescriptor(pluginId)).thenReturn(Optional.of(descriptor));
        when(dependencyResolver.activationProblems(descriptor)).thenReturn(List.of());
        when(lifecycleService.phase(pluginId)).thenReturn(Optional.of(PluginRuntimePhase.STARTED));

        new ExternalPluginLifecycleCoordinator(runtimeManager, lifecycleService, installer,
                recoveryModeService, dependencyResolver).start(pluginId);

        InOrder order = inOrder(dependencyResolver, runtimeManager, lifecycleService);
        order.verify(dependencyResolver).activationDescriptor(pluginId);
        order.verify(dependencyResolver).activationProblems(descriptor);
        order.verify(runtimeManager).startPlugin(pluginId);
        order.verify(lifecycleService).start(pluginId);
        verify(recoveryModeService).refresh();
    }

    @Test
    @DisplayName("Error 越过动作时包锁操作快照恢复 IDLE 并转为宿主异常")
    void errorLeavesOperationIdle() {
        String pluginId = "dev-plugin";
        AssertionError failure = new AssertionError("quiesce failed");
        doThrow(failure).when(lifecycleService).quiesce(pluginId);
        ExternalPluginLifecycleCoordinator coordinator = coordinator();

        assertThatThrownBy(() -> coordinator.quiesce(pluginId))
                .isInstanceOf(PluginLifecycleException.class)
                .hasCause(failure);

        assertThat(coordinator.operation(pluginId)).hasValueSatisfying(snapshot -> {
            assertThat(snapshot.operation()).isEqualTo(ExternalPluginOperation.IDLE);
            assertThat(snapshot.diagnostic()).contains("quiesce failed");
        });
    }

    @Test
    @DisplayName("fatal start 失败先停止应用足迹与 PF4J 再原对象重抛")
    void fatalStartFailureCompensatesBeforeRethrow() {
        String pluginId = "dev-plugin";
        PluginDescriptor descriptor = descriptor(pluginId);
        TestVirtualMachineError fatal = new TestVirtualMachineError("fatal lifecycle start");
        when(dependencyResolver.activationDescriptor(pluginId)).thenReturn(Optional.of(descriptor));
        when(dependencyResolver.activationProblems(descriptor)).thenReturn(List.of());
        doThrow(fatal).when(lifecycleService).start(pluginId);
        ExternalPluginLifecycleCoordinator coordinator = coordinator();

        assertThatThrownBy(() -> coordinator.start(pluginId)).isSameAs(fatal);

        InOrder order = inOrder(runtimeManager, lifecycleService);
        order.verify(runtimeManager).startPlugin(pluginId);
        order.verify(lifecycleService).start(pluginId);
        order.verify(lifecycleService).stop(pluginId);
        order.verify(runtimeManager).stopPlugin(pluginId);
        assertThat(coordinator.operation(pluginId)).hasValueSatisfying(snapshot ->
                assertThat(snapshot.operation()).isEqualTo(ExternalPluginOperation.IDLE));
    }

    @Test
    @DisplayName("普通 start 失败的补偿 fatal 成为主失败且后续补偿仍执行")
    void fatalStartCleanupOverridesOrdinaryFailureAndContinuesCompensation() {
        String pluginId = "dev-plugin";
        PluginDescriptor descriptor = descriptor(pluginId);
        IllegalStateException startFailure = new IllegalStateException("lifecycle start failed");
        TestVirtualMachineError cleanupFatal = new TestVirtualMachineError("fatal lifecycle cleanup");
        IllegalArgumentException laterCleanupFailure = new IllegalArgumentException("runtime cleanup failed");
        when(dependencyResolver.activationDescriptor(pluginId)).thenReturn(Optional.of(descriptor));
        when(dependencyResolver.activationProblems(descriptor)).thenReturn(List.of());
        doThrow(startFailure).when(lifecycleService).start(pluginId);
        doThrow(cleanupFatal).when(lifecycleService).stop(pluginId);
        doThrow(laterCleanupFailure).when(runtimeManager).stopPlugin(pluginId);
        ExternalPluginLifecycleCoordinator coordinator = coordinator();

        assertThatThrownBy(() -> coordinator.start(pluginId))
                .isSameAs(cleanupFatal)
                .satisfies(thrown -> assertThat(thrown.getSuppressed())
                        .containsExactly(startFailure, laterCleanupFailure));

        InOrder order = inOrder(runtimeManager, lifecycleService);
        order.verify(runtimeManager).startPlugin(pluginId);
        order.verify(lifecycleService).start(pluginId);
        order.verify(lifecycleService).stop(pluginId);
        order.verify(runtimeManager).stopPlugin(pluginId);
        assertThat(coordinator.operation(pluginId)).hasValueSatisfying(snapshot ->
                assertThat(snapshot.operation()).isEqualTo(ExternalPluginOperation.IDLE));
    }

    @Test
    @DisplayName("文件提交后 load 抛 Error 时回滚磁盘事务并返回失败结果")
    void activationErrorAfterCommitRollsBackTransaction() {
        String pluginId = "dev-plugin";
        Path staged = Path.of("plugins", ".staging", "tx", "new.jar");
        Path target = Path.of("plugins", "dev-plugin.jar");
        PluginDescriptor descriptor = descriptor(pluginId);
        PluginInstallResult result = new PluginInstallResult(
                PluginInstallOutcome.INSTALLED, descriptor, target, null, List.of());
        PreparedPluginTransaction prepared = new PreparedPluginTransaction(
                "tx", result, staged.getParent(), staged, target, List.of());
        CommittedPluginTransaction committed = new CommittedPluginTransaction(prepared, List.of());
        when(installer.prepareTransaction(staged, false, PluginPackageOrigin.localUpload())).thenReturn(prepared);
        when(dependencyResolver.activationProblems(descriptor)).thenReturn(List.of());
        when(runtimeManager.packagePhases()).thenReturn(Map.of());
        when(runtimeManager.artifactPath(pluginId)).thenReturn(Optional.empty());
        when(installer.commitTransaction(prepared)).thenReturn(committed);
        doThrow(new AssertionError("load failed after commit")).when(runtimeManager).loadPlugin(target);
        when(installer.rollbackTransaction(same(committed))).thenReturn(true);
        ExternalPluginLifecycleCoordinator coordinator = coordinator();

        PluginActivationResult activation = coordinator.installOrUpdate(
                staged, false, PluginPackageOrigin.localUpload());

        assertThat(activation.installResult().outcome()).isEqualTo(PluginInstallOutcome.FAILED);
        assertThat(activation.rolledBack()).isTrue();
        verify(installer).rollbackTransaction(same(committed));
        assertThat(coordinator.operation(pluginId)).hasValueSatisfying(snapshot ->
                assertThat(snapshot.operation()).isEqualTo(ExternalPluginOperation.IDLE));
    }

    @Test
    @DisplayName("普通 activation 失败的事务回滚 ThreadDeath 成为主失败并保留原失败")
    void fatalActivationCleanupOverridesOrdinaryFailure() {
        String pluginId = "dev-plugin";
        Path staged = Path.of("plugins", ".staging", "tx-fatal", "new.jar");
        Path target = Path.of("plugins", "dev-plugin.jar");
        PluginDescriptor descriptor = descriptor(pluginId);
        PluginInstallResult result = new PluginInstallResult(
                PluginInstallOutcome.INSTALLED, descriptor, target, null, List.of());
        PreparedPluginTransaction prepared = new PreparedPluginTransaction(
                "tx-fatal", result, staged.getParent(), staged, target, List.of());
        CommittedPluginTransaction committed = new CommittedPluginTransaction(prepared, List.of());
        IllegalStateException activationFailure = new IllegalStateException("load failed after commit");
        ThreadDeath cleanupFatal = new ThreadDeath();
        when(installer.prepareTransaction(staged, false, PluginPackageOrigin.localUpload())).thenReturn(prepared);
        when(dependencyResolver.activationProblems(descriptor)).thenReturn(List.of());
        when(runtimeManager.packagePhases()).thenReturn(Map.of());
        when(runtimeManager.artifactPath(pluginId)).thenReturn(Optional.empty());
        when(installer.commitTransaction(prepared)).thenReturn(committed);
        doThrow(activationFailure).when(runtimeManager).loadPlugin(target);
        when(installer.rollbackTransaction(same(committed))).thenThrow(cleanupFatal);
        ExternalPluginLifecycleCoordinator coordinator = coordinator();

        assertThatThrownBy(() -> coordinator.installOrUpdate(
                staged, false, PluginPackageOrigin.localUpload()))
                .isSameAs(cleanupFatal)
                .satisfies(thrown -> assertThat(thrown.getSuppressed()).containsExactly(activationFailure));

        verify(recoveryModeService).refresh();
        assertThat(coordinator.operation(pluginId)).hasValueSatisfying(snapshot ->
                assertThat(snapshot.operation()).isEqualTo(ExternalPluginOperation.FAILED));
    }

    @Test
    @DisplayName("PF4J unload 移除 wrapper 后抛 Error 时清除旧代强引用")
    void unloadErrorAfterWrapperRemovalForgetsGeneration() {
        String pluginId = "dev-plugin";
        when(runtimeManager.packagePhases()).thenReturn(
                Map.of(pluginId, PluginRuntimePackagePhase.STOPPED), Map.of());
        when(runtimeManager.activeDependents(pluginId)).thenReturn(List.of());
        when(lifecycleService.phase(pluginId)).thenReturn(Optional.of(PluginRuntimePhase.STOPPED));
        when(lifecycleService.generation(pluginId)).thenReturn(Optional.of(7L));
        doThrow(new AssertionError("unload failed after wrapper removal"))
                .when(runtimeManager).unloadPlugin(pluginId);
        ExternalPluginLifecycleCoordinator coordinator = coordinator();

        assertThatThrownBy(() -> coordinator.unload(pluginId))
                .isInstanceOfSatisfying(ClassifiedPluginLifecycleException.class, failure ->
                        assertThat(failure.code()).isEqualTo(PluginManagementErrorCode.PHYSICAL_UNLOAD_FAILED));

        verify(lifecycleService).forgetUnloadedGeneration(pluginId, 7L);
        verify(lifecycleService, never()).load(pluginId);
        assertThat(coordinator.operation(pluginId)).hasValueSatisfying(snapshot ->
                assertThat(snapshot.operation()).isEqualTo(ExternalPluginOperation.IDLE));
    }

    private ExternalPluginLifecycleCoordinator coordinator() {
        return new ExternalPluginLifecycleCoordinator(runtimeManager, lifecycleService, installer,
                recoveryModeService, dependencyResolver);
    }

    private static PluginDescriptor descriptor(String pluginId) {
        return descriptor(pluginId, PluginLifecyclePolicy.HOT_RELOAD);
    }

    private static PluginDescriptor descriptor(String pluginId, PluginLifecyclePolicy lifecyclePolicy) {
        return new PluginDescriptor(pluginId, pluginId, "1.0.0",
                PluginApiRequirement.unspecified(), List.of(), "example.Plugin", null,
                "plugin.label", null, null, null, PluginKind.FEATURE, List.of(), lifecyclePolicy);
    }

    private static final class TestVirtualMachineError extends VirtualMachineError {
        private TestVirtualMachineError(String message) {
            super(message);
        }
    }
}
