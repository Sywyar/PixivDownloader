package top.sywyar.pixivdownload.plugin.lifecycle;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.install.PluginActivationResult;
import top.sywyar.pixivdownload.plugin.install.PluginDependencyProblem;
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
import top.sywyar.pixivdownload.plugin.runtime.install.transaction.PluginRemovalAttempt;
import top.sywyar.pixivdownload.plugin.runtime.install.transaction.PreparedPluginTransaction;
import top.sywyar.pixivdownload.plugin.runtime.lifecycle.UnloadedPluginPackage;
import top.sywyar.pixivdownload.plugin.runtime.lifecycle.PluginRuntimePackagePhase;
import top.sywyar.pixivdownload.plugin.runtime.lifecycle.LoadedPluginPackage;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
        when(installer.commitTransaction(prepared)).thenAnswer(invocation -> {
            prepared.confirmCommitState(PreparedPluginTransaction.CommitState.COMMITTED);
            return committed;
        });
        when(lifecycleService.phase(pluginId)).thenReturn(Optional.empty());

        PluginActivationResult activation = coordinator().installOrUpdate(
                staged, false, PluginPackageOrigin.localUpload());

        assertThat(activation.activated()).isFalse();
        assertThat(activation.installResult().accepted()).isTrue();
        verify(installer).markActivated(committed);
        verify(installer).completeTransaction(committed);
        verify(installer).verifyCommittedTarget(committed);
        verify(runtimeManager, never()).loadPlugin(target);
        verify(runtimeManager, never()).startPlugin(pluginId);
        verify(lifecycleService, never()).start(pluginId);
    }

    @Test
    @DisplayName("进程重启事务退役后抛 fatal 时保留已提交文件且不回滚")
    void fatalAfterRetiredProcessRestartCommitDoesNotRollback() {
        String pluginId = "process-retired-fatal";
        Path staged = Path.of("plugins", ".staging", "tx-process-fatal", "new.jar");
        Path target = Path.of("plugins", "process-retired-fatal.jar");
        PluginDescriptor descriptor = descriptor(pluginId, PluginLifecyclePolicy.PROCESS_RESTART);
        PluginInstallResult result = new PluginInstallResult(
                PluginInstallOutcome.INSTALLED, descriptor, target, null, List.of());
        PreparedPluginTransaction prepared = new PreparedPluginTransaction(
                "tx-process-fatal", result, staged.getParent(), staged, target, List.of());
        CommittedPluginTransaction committed = new CommittedPluginTransaction(prepared, List.of());
        TestVirtualMachineError fatal = new TestVirtualMachineError("fatal after process transaction retirement");
        when(installer.prepareTransaction(staged, false, PluginPackageOrigin.localUpload())).thenReturn(prepared);
        when(dependencyResolver.activationProblems(descriptor)).thenReturn(List.of());
        when(lifecycleService.phase(pluginId)).thenReturn(Optional.empty());
        when(installer.commitTransaction(prepared)).thenAnswer(invocation -> {
            prepared.confirmCommitState(PreparedPluginTransaction.CommitState.COMMITTED);
            return committed;
        });
        doAnswer(invocation -> {
            committed.confirmDurableState(CommittedPluginTransaction.DurableState.ACTIVATED);
            return null;
        }).when(installer).markActivated(committed);
        doAnswer(invocation -> {
            committed.confirmDurableState(CommittedPluginTransaction.DurableState.COMMITTED);
            committed.confirmDurableState(CommittedPluginTransaction.DurableState.RETIRED);
            throw fatal;
        }).when(installer).completeTransaction(committed);

        assertThatThrownBy(() -> coordinator().installOrUpdate(
                staged, false, PluginPackageOrigin.localUpload()))
                .isSameAs(fatal);

        verify(installer, never()).rollbackTransaction(committed);
        verify(installer, never()).discardPrepared(prepared);
        verify(runtimeManager, never()).loadPlugin(any(Path.class));
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
        when(installer.commitTransaction(prepared)).thenAnswer(invocation -> {
            prepared.confirmCommitState(PreparedPluginTransaction.CommitState.COMMITTED);
            return committed;
        });
        when(runtimeManager.loadPlugin(target)).thenReturn(loaded);
        when(lifecycleService.phase(pluginId)).thenReturn(Optional.of(PluginRuntimePhase.STARTED));

        PluginActivationResult activation = coordinator().installOrUpdate(
                staged, false, PluginPackageOrigin.localUpload());

        assertThat(activation.activated()).isTrue();
        assertThat(activation.runtimePhase()).isEqualTo(PluginRuntimePhase.STARTED);
        verify(runtimeManager).loadPlugin(target);
        verify(installer).verifyCommittedTarget(committed);
        verify(lifecycleService).adoptLoadedPackage(loaded);
        verify(runtimeManager).startPlugin(pluginId);
        verify(lifecycleService).start(pluginId);
    }

    @Test
    @DisplayName("同版本已安装仍复核依赖并向 catalog 返回可补齐的拒绝回执")
    void duplicateInstallStillReportsDependencyRejection() {
        String pluginId = "duplicate-plugin";
        Path packageFile = Path.of("incoming", "duplicate-plugin.jar");
        Path installed = Path.of("plugins", "duplicate-plugin.jar");
        PluginPackageOrigin origin = PluginPackageOrigin.localUpload();
        PluginDescriptor descriptor = descriptor(pluginId);
        PluginInstallResult duplicate = new PluginInstallResult(
                PluginInstallOutcome.DUPLICATE, descriptor, installed, "1.0.0",
                List.of(pluginId + " 1.0.0 already installed"));
        PreparedPluginTransaction prepared = new PreparedPluginTransaction(
                "tx-duplicate", duplicate, null, null, null, List.of(installed));
        PluginDependencyProblem problem = new PluginDependencyProblem(
                "missing-dependency", "1.0", false, null, null,
                PluginDependencyProblem.Reason.MISSING, "required dependency is not installed");
        when(installer.prepareTransaction(packageFile, false, origin)).thenReturn(prepared);
        when(dependencyResolver.activationProblems(descriptor)).thenReturn(List.of(problem));

        PluginActivationResult activation = coordinator().installOrUpdate(packageFile, false, origin);

        assertThat(activation.installResult().outcome())
                .isEqualTo(PluginInstallOutcome.REJECTED_DEPENDENCY);
        assertThat(activation.dependencyProblems()).containsExactly(problem);
        verify(installer, never()).discardPrepared(prepared);
        verify(installer, never()).commitTransaction(prepared);
        verify(runtimeManager, never()).loadPlugin(any(Path.class));
    }

    @Test
    @DisplayName("prepare 阻塞期间全局写预约拒绝第二次安装且只产生一个权威事务")
    void globalMutationReservationPreventsSecondPrepare() throws Exception {
        Path firstPackage = Path.of("incoming", "first-invalid.jar");
        Path secondPackage = Path.of("incoming", "second-invalid.jar");
        PluginPackageOrigin origin = PluginPackageOrigin.localUpload();
        PreparedPluginTransaction rejected = rejectedTransaction("tx-first", firstPackage);
        CountDownLatch prepareEntered = new CountDownLatch(1);
        CountDownLatch releasePrepare = new CountDownLatch(1);
        doAnswer(invocation -> {
            prepareEntered.countDown();
            awaitLatch(releasePrepare, "first prepare release");
            return rejected;
        }).when(installer).prepareTransaction(firstPackage, false, origin);
        ExternalPluginLifecycleCoordinator coordinator = coordinator();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<PluginActivationResult> first = executor.submit(() ->
                    coordinator.installOrUpdate(firstPackage, false, origin));
            awaitLatch(prepareEntered, "first prepare entry");

            assertThatThrownBy(() -> coordinator.installOrUpdate(secondPackage, false, origin))
                    .isInstanceOfSatisfying(ClassifiedPluginLifecycleException.class, failure ->
                            assertThat(failure.code())
                                    .isEqualTo(PluginManagementErrorCode.OPERATION_IN_PROGRESS));

            releasePrepare.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS).installResult().outcome())
                    .isEqualTo(PluginInstallOutcome.REJECTED_MALFORMED);
            verify(installer, times(1)).prepareTransaction(firstPackage, false, origin);
            verify(installer, never()).prepareTransaction(secondPackage, false, origin);
            verify(installer, never()).commitTransaction(any(PreparedPluginTransaction.class));
        } finally {
            releasePrepare.countDown();
            shutdown(executor);
        }
    }

    @Test
    @DisplayName("prepare 提前返回拒绝结果后释放全局写预约供其它线程进入")
    void earlyPrepareReturnReleasesGlobalMutationReservation() throws Exception {
        Path packageFile = Path.of("incoming", "rejected.jar");
        PluginPackageOrigin origin = PluginPackageOrigin.localUpload();
        when(installer.prepareTransaction(packageFile, false, origin))
                .thenReturn(rejectedTransaction("tx-rejected", packageFile));
        ExternalPluginLifecycleCoordinator coordinator = coordinator();

        PluginActivationResult rejected = coordinator.installOrUpdate(packageFile, false, origin);

        assertThat(rejected.installResult().outcome()).isEqualTo(PluginInstallOutcome.REJECTED_MALFORMED);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> nextMutation = executor.submit(() -> coordinator.quiesce("next-plugin"));
            nextMutation.get(5, TimeUnit.SECONDS);
        } finally {
            shutdown(executor);
        }
        verify(lifecycleService).quiesce("next-plugin");
    }

    @Test
    @DisplayName("依赖校验位于包与替代锁域内且并发删除不能进入")
    void dependencyValidationRunsInsidePackageAndReplacementLocks() throws Exception {
        String packageId = "replacement-plugin";
        String replacedId = "legacy-plugin";
        Path packageFile = Path.of("incoming", "replacement-plugin.jar");
        Path target = Path.of("plugins", "replacement-plugin.jar");
        PluginPackageOrigin origin = PluginPackageOrigin.localUpload();
        PluginDescriptor descriptor = descriptor(packageId, List.of(replacedId));
        PluginInstallResult result = new PluginInstallResult(
                PluginInstallOutcome.INSTALLED, descriptor, target, null, List.of());
        PreparedPluginTransaction prepared = new PreparedPluginTransaction(
                "tx-replacement", result, Path.of("plugins", ".staging", "tx-replacement"),
                packageFile, target, List.of());
        PluginDependencyProblem problem = new PluginDependencyProblem(
                "missing-dependency", "1.0", false, null, null,
                PluginDependencyProblem.Reason.MISSING, "required dependency is not installed");
        CountDownLatch validationEntered = new CountDownLatch(1);
        CountDownLatch releaseValidation = new CountDownLatch(1);
        when(installer.prepareTransaction(packageFile, false, origin)).thenReturn(prepared);
        when(installer.discardPrepared(prepared)).thenAnswer(invocation -> {
            prepared.confirmCommitState(PreparedPluginTransaction.CommitState.DISCARDED);
            return true;
        });
        doAnswer(invocation -> {
            validationEntered.countDown();
            awaitLatch(releaseValidation, "dependency validation release");
            return List.of(problem);
        }).when(dependencyResolver).activationProblems(descriptor);
        ExternalPluginLifecycleCoordinator coordinator = coordinator();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<PluginActivationResult> installation = executor.submit(() ->
                    coordinator.installOrUpdate(packageFile, false, origin));
            awaitLatch(validationEntered, "dependency validation entry");

            assertThat(coordinator.operation(packageId)).hasValueSatisfying(snapshot -> {
                assertThat(snapshot.operation()).isEqualTo(ExternalPluginOperation.INSTALLING);
                assertThat(snapshot.transactionId()).isEqualTo("tx-replacement");
            });
            assertThat(coordinator.operation(replacedId)).hasValueSatisfying(snapshot -> {
                assertThat(snapshot.operation()).isEqualTo(ExternalPluginOperation.INSTALLING);
                assertThat(snapshot.transactionId()).isEqualTo("tx-replacement");
            });
            assertThatThrownBy(() -> coordinator.remove(replacedId))
                    .isInstanceOfSatisfying(ClassifiedPluginLifecycleException.class, failure ->
                            assertThat(failure.code())
                                    .isEqualTo(PluginManagementErrorCode.OPERATION_IN_PROGRESS));
            verify(installer, never()).removeInstalled(any(PluginRemovalAttempt.class));
            verify(runtimeManager, never()).activeDependents(replacedId);

            releaseValidation.countDown();
            PluginActivationResult activation = installation.get(5, TimeUnit.SECONDS);
            assertThat(activation.installResult().outcome())
                    .isEqualTo(PluginInstallOutcome.REJECTED_DEPENDENCY);
            verify(installer).discardPrepared(prepared);
            assertThat(coordinator.operation(packageId)).hasValueSatisfying(snapshot ->
                    assertThat(snapshot.operation()).isEqualTo(ExternalPluginOperation.IDLE));
            assertThat(coordinator.operation(replacedId)).hasValueSatisfying(snapshot ->
                    assertThat(snapshot.operation()).isEqualTo(ExternalPluginOperation.IDLE));
        } finally {
            releaseValidation.countDown();
            shutdown(executor);
        }
    }

    @Test
    @DisplayName("依赖校验抛 Error 时统一退役尚未提交的 PREPARED 事务")
    void dependencyResolverErrorDiscardsPreparedTransaction() {
        PreparedPluginTransaction prepared = acceptedTransaction(
                "tx-dependency-error", "dependency-error-plugin", PluginLifecyclePolicy.HOT_RELOAD);
        AssertionError failure = new AssertionError("dependency resolver failed");
        when(installer.prepareTransaction(
                prepared.stagedArtifact(), false, PluginPackageOrigin.localUpload())).thenReturn(prepared);
        when(dependencyResolver.activationProblems(prepared.result().descriptor())).thenThrow(failure);

        assertPreparedInstallFailureDiscards(prepared, failure);
    }

    @Test
    @DisplayName("prepare 普通 Error 收敛为生命周期失败且 fatal 保留对象身份")
    void prepareErrorsRespectCoordinatorFailureBoundary() {
        Path ordinaryPackage = Path.of("incoming", "prepare-ordinary-error.jar");
        AssertionError ordinary = new AssertionError("prepare ordinary error");
        doThrow(ordinary).when(installer).prepareTransaction(
                ordinaryPackage, false, PluginPackageOrigin.localUpload());

        assertThatThrownBy(() -> coordinator().installOrUpdate(
                ordinaryPackage, false, PluginPackageOrigin.localUpload()))
                .isInstanceOf(PluginLifecycleException.class)
                .hasCause(ordinary);

        Path fatalPackage = Path.of("incoming", "prepare-fatal-error.jar");
        VirtualMachineError fatal = new VirtualMachineError("prepare fatal error") { };
        doThrow(fatal).when(installer).prepareTransaction(
                fatalPackage, false, PluginPackageOrigin.localUpload());

        assertThatThrownBy(() -> coordinator().installOrUpdate(
                fatalPackage, false, PluginPackageOrigin.localUpload()))
                .isSameAs(fatal);
        verify(installer, never()).discardPrepared(any(PreparedPluginTransaction.class));
        verify(installer, never()).commitTransaction(any(PreparedPluginTransaction.class));
    }

    @Test
    @DisplayName("普通安装读取 package phase 抛 Error 时统一退役 PREPARED 事务")
    void packagePhaseErrorDiscardsPreparedTransaction() {
        PreparedPluginTransaction prepared = acceptedTransaction(
                "tx-phase-error", "phase-error-plugin", PluginLifecyclePolicy.HOT_RELOAD);
        AssertionError failure = new AssertionError("package phase inspection failed");
        when(installer.prepareTransaction(
                prepared.stagedArtifact(), false, PluginPackageOrigin.localUpload())).thenReturn(prepared);
        when(dependencyResolver.activationProblems(prepared.result().descriptor())).thenReturn(List.of());
        when(runtimeManager.packagePhases()).thenThrow(failure);

        assertPreparedInstallFailureDiscards(prepared, failure);

        verify(runtimeManager, never()).artifactPath(prepared.result().pluginId());
    }

    @Test
    @DisplayName("普通安装读取旧 artifact 抛 Error 时统一退役 PREPARED 事务")
    void artifactPathErrorDiscardsPreparedTransaction() {
        PreparedPluginTransaction prepared = acceptedTransaction(
                "tx-artifact-error", "artifact-error-plugin", PluginLifecyclePolicy.HOT_RELOAD);
        AssertionError failure = new AssertionError("artifact path inspection failed");
        when(installer.prepareTransaction(
                prepared.stagedArtifact(), false, PluginPackageOrigin.localUpload())).thenReturn(prepared);
        when(dependencyResolver.activationProblems(prepared.result().descriptor())).thenReturn(List.of());
        when(runtimeManager.packagePhases()).thenReturn(Map.of());
        when(runtimeManager.artifactPath(prepared.result().pluginId())).thenThrow(failure);

        assertPreparedInstallFailureDiscards(prepared, failure);
    }

    @Test
    @DisplayName("进程重启安装读取提交前 phase 抛 Error 时统一退役 PREPARED 事务")
    void processRestartPhaseErrorDiscardsPreparedTransaction() {
        PreparedPluginTransaction prepared = acceptedTransaction(
                "tx-process-phase-error", "process-phase-error-plugin",
                PluginLifecyclePolicy.PROCESS_RESTART);
        AssertionError failure = new AssertionError("current phase inspection failed");
        when(installer.prepareTransaction(
                prepared.stagedArtifact(), false, PluginPackageOrigin.localUpload())).thenReturn(prepared);
        when(dependencyResolver.activationProblems(prepared.result().descriptor())).thenReturn(List.of());
        when(lifecycleService.phase(prepared.result().pluginId())).thenThrow(failure);

        assertPreparedInstallFailureDiscards(prepared, failure);
    }

    @Test
    @DisplayName("普通安装已完成 durable 终态后刷新抛 Error 不回滚且仍返回激活成功")
    void hotReloadPostTerminalRefreshErrorDoesNotRollback() {
        PreparedPluginTransaction prepared = acceptedTransaction(
                "tx-hot-refresh-error", "hot-refresh-error-plugin", PluginLifecyclePolicy.HOT_RELOAD);
        CommittedPluginTransaction committed = new CommittedPluginTransaction(prepared, List.of());
        LoadedPluginPackage loaded = mock(LoadedPluginPackage.class);
        String pluginId = prepared.result().pluginId();
        when(loaded.packageId()).thenReturn(pluginId);
        when(installer.prepareTransaction(
                prepared.stagedArtifact(), false, PluginPackageOrigin.localUpload())).thenReturn(prepared);
        when(dependencyResolver.activationProblems(prepared.result().descriptor())).thenReturn(List.of());
        when(runtimeManager.packagePhases()).thenReturn(Map.of());
        when(runtimeManager.artifactPath(pluginId)).thenReturn(Optional.empty());
        when(installer.commitTransaction(prepared)).thenReturn(committed);
        when(runtimeManager.loadPlugin(prepared.target())).thenReturn(loaded);
        when(lifecycleService.phase(pluginId)).thenReturn(Optional.of(PluginRuntimePhase.STARTED));
        doThrow(new AssertionError("post-terminal refresh failed")).when(recoveryModeService).refresh();

        PluginActivationResult activation = coordinator().installOrUpdate(
                prepared.stagedArtifact(), false, PluginPackageOrigin.localUpload());

        assertThat(activation.installResult().accepted()).isTrue();
        assertThat(activation.activated()).isTrue();
        assertThat(activation.rolledBack()).isFalse();
        verify(installer).markActivated(committed);
        verify(installer).completeTransaction(committed);
        verify(installer, never()).rollbackTransaction(any(CommittedPluginTransaction.class));
        verify(installer, never()).discardPrepared(prepared);
    }

    @Test
    @DisplayName("进程重启安装已完成 durable 终态后刷新抛 Error 不回滚且仍返回提交成功")
    void processRestartPostTerminalRefreshErrorDoesNotRollback() {
        PreparedPluginTransaction prepared = acceptedTransaction(
                "tx-process-refresh-error", "process-refresh-error-plugin",
                PluginLifecyclePolicy.PROCESS_RESTART);
        CommittedPluginTransaction committed = new CommittedPluginTransaction(prepared, List.of());
        String pluginId = prepared.result().pluginId();
        when(installer.prepareTransaction(
                prepared.stagedArtifact(), false, PluginPackageOrigin.localUpload())).thenReturn(prepared);
        when(dependencyResolver.activationProblems(prepared.result().descriptor())).thenReturn(List.of());
        when(lifecycleService.phase(pluginId)).thenReturn(Optional.of(PluginRuntimePhase.STOPPED));
        when(installer.commitTransaction(prepared)).thenReturn(committed);
        doThrow(new AssertionError("post-terminal refresh failed")).when(recoveryModeService).refresh();

        PluginActivationResult activation = coordinator().installOrUpdate(
                prepared.stagedArtifact(), false, PluginPackageOrigin.localUpload());

        assertThat(activation.installResult().accepted()).isTrue();
        assertThat(activation.activated()).isFalse();
        assertThat(activation.rolledBack()).isFalse();
        assertThat(activation.runtimePhase()).isEqualTo(PluginRuntimePhase.STOPPED);
        verify(installer).markActivated(committed);
        verify(installer).completeTransaction(committed);
        verify(installer, never()).rollbackTransaction(any(CommittedPluginTransaction.class));
        verify(installer, never()).discardPrepared(prepared);
        verify(runtimeManager, never()).loadPlugin(prepared.target());
    }

    @Test
    @DisplayName("停机前复核失败只退役 PREPARED，不触碰仍在运行的旧代")
    void preflightVerificationFailureDoesNotUnloadOldRuntime() {
        PreparedPluginTransaction prepared = acceptedTransaction(
                "tx-preflight-failure", "preflight-plugin", PluginLifecyclePolicy.HOT_RELOAD);
        String pluginId = prepared.result().pluginId();
        Path previousArtifact = Path.of("plugins", "preflight-plugin-0.9.0.jar");
        AssertionError failure = new AssertionError("prepared bindings changed");
        when(installer.prepareTransaction(
                prepared.stagedArtifact(), false, PluginPackageOrigin.localUpload())).thenReturn(prepared);
        when(dependencyResolver.activationProblems(prepared.result().descriptor())).thenReturn(List.of());
        when(runtimeManager.packagePhases()).thenReturn(
                Map.of(pluginId, PluginRuntimePackagePhase.STARTED));
        when(runtimeManager.artifactPath(pluginId)).thenReturn(Optional.of(previousArtifact));
        when(lifecycleService.phase(pluginId)).thenReturn(Optional.of(PluginRuntimePhase.STARTED));
        doThrow(failure).when(installer).verifyCurrentArtifacts(prepared);
        when(installer.discardPrepared(prepared)).thenAnswer(invocation -> {
            prepared.confirmCommitState(PreparedPluginTransaction.CommitState.DISCARDED);
            return true;
        });

        PluginActivationResult result = coordinator().installOrUpdate(
                prepared.stagedArtifact(), false, PluginPackageOrigin.localUpload());

        assertThat(result.installResult().outcome()).isEqualTo(PluginInstallOutcome.FAILED);
        assertThat(result.rolledBack()).isTrue();
        assertThat(result.runtimePhase()).isEqualTo(PluginRuntimePhase.STARTED);
        verify(installer).discardPrepared(prepared);
        verify(installer, never()).commitTransaction(prepared);
        verify(installer, never()).rollbackTransaction(any(CommittedPluginTransaction.class));
        verify(runtimeManager, never()).unloadPlugin(pluginId);
        verify(runtimeManager, never()).loadPlugin(any(Path.class));
        verify(runtimeManager, never()).startPlugin(pluginId);
        verify(lifecycleService, never()).unload(pluginId);
        verify(lifecycleService, never()).load(pluginId);
        verify(lifecycleService, never()).start(pluginId);
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
        when(installer.commitTransaction(prepared)).thenAnswer(invocation -> {
            prepared.confirmCommitState(PreparedPluginTransaction.CommitState.COMMITTED);
            return committed;
        });
        doThrow(new AssertionError("load failed after commit")).when(runtimeManager).loadPlugin(target);
        when(installer.rollbackTransaction(same(committed))).thenAnswer(invocation -> {
            committed.confirmDurableState(CommittedPluginTransaction.DurableState.ROLLED_BACK);
            prepared.confirmCommitState(PreparedPluginTransaction.CommitState.ROLLED_BACK);
            return true;
        });
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
    @DisplayName("新 generation 无法确认物理清退时延后磁盘回滚并返回恢复阻断")
    void unconfirmedCurrentGenerationCleanupDefersRollbackUntilRestart() {
        String pluginId = "cleanup-blocked-plugin";
        Path staged = Path.of("plugins", ".staging", "tx-cleanup-blocked", "new.jar");
        Path target = Path.of("plugins", "cleanup-blocked-plugin.jar");
        PluginDescriptor descriptor = descriptor(pluginId);
        PluginInstallResult result = new PluginInstallResult(
                PluginInstallOutcome.INSTALLED, descriptor, target, null, List.of());
        PreparedPluginTransaction prepared = new PreparedPluginTransaction(
                "tx-cleanup-blocked", result, staged.getParent(), staged, target, List.of());
        CommittedPluginTransaction committed = new CommittedPluginTransaction(prepared, List.of());
        LoadedPluginPackage loaded = mock(LoadedPluginPackage.class);
        AssertionError activationFailure = new AssertionError("activation receipt failed");
        AssertionError unloadFailure = new AssertionError("current generation unload was not confirmed");
        when(loaded.packageId()).thenReturn(pluginId);
        when(installer.prepareTransaction(staged, false, PluginPackageOrigin.localUpload())).thenReturn(prepared);
        when(dependencyResolver.activationProblems(descriptor)).thenReturn(List.of());
        when(runtimeManager.packagePhases()).thenReturn(
                Map.of(),
                Map.of(pluginId, PluginRuntimePackagePhase.STARTED),
                Map.of(pluginId, PluginRuntimePackagePhase.STARTED),
                Map.of(pluginId, PluginRuntimePackagePhase.STARTED));
        when(runtimeManager.artifactPath(pluginId)).thenReturn(Optional.empty());
        when(installer.commitTransaction(prepared)).thenAnswer(invocation -> {
            prepared.confirmCommitState(PreparedPluginTransaction.CommitState.COMMITTED);
            return committed;
        });
        when(runtimeManager.loadPlugin(target)).thenReturn(loaded);
        when(lifecycleService.phase(pluginId)).thenReturn(
                Optional.of(PluginRuntimePhase.STARTED),
                Optional.of(PluginRuntimePhase.STOPPED),
                Optional.of(PluginRuntimePhase.STOPPED));
        when(lifecycleService.managedPluginIds()).thenReturn(java.util.Set.of(pluginId));
        when(runtimeManager.activeDependents(pluginId)).thenReturn(List.of());
        when(lifecycleService.generation(pluginId)).thenReturn(Optional.of(9L));
        doThrow(unloadFailure).when(runtimeManager).unloadPlugin(pluginId);
        doThrow(activationFailure).when(installer).markActivated(committed);

        ExternalPluginLifecycleCoordinator coordinator = coordinator();
        PluginActivationResult activation = coordinator.installOrUpdate(
                staged, false, PluginPackageOrigin.localUpload());

        assertThat(activation.installResult().outcome()).isEqualTo(PluginInstallOutcome.FAILED);
        assertThat(activation.activated()).isFalse();
        assertThat(activation.rolledBack()).isFalse();
        assertThat(activation.recoveryBlocked()).isTrue();
        verify(installer).deferRollbackUntilRestart(committed, activationFailure);
        verify(installer, never()).rollbackTransaction(committed);
        verify(lifecycleService).load(pluginId);
        assertThat(coordinator.operation(pluginId)).hasValueSatisfying(snapshot ->
                assertThat(snapshot.operation()).isEqualTo(ExternalPluginOperation.FAILED));
    }

    @Test
    @DisplayName("提交目标复验失败时在加载插件代码前回滚文件事务")
    void committedTargetVerificationFailsBeforeRuntimeLoad() {
        String pluginId = "verify-before-load";
        Path staged = Path.of("plugins", ".staging", "tx-verify", "new.jar");
        Path target = Path.of("plugins", "verify-before-load.jar");
        PluginDescriptor descriptor = descriptor(pluginId);
        PluginInstallResult result = new PluginInstallResult(
                PluginInstallOutcome.INSTALLED, descriptor, target, null, List.of());
        PreparedPluginTransaction prepared = new PreparedPluginTransaction(
                "tx-verify", result, staged.getParent(), staged, target, List.of());
        CommittedPluginTransaction committed = new CommittedPluginTransaction(prepared, List.of());
        when(installer.prepareTransaction(staged, false, PluginPackageOrigin.localUpload())).thenReturn(prepared);
        when(dependencyResolver.activationProblems(descriptor)).thenReturn(List.of());
        when(runtimeManager.packagePhases()).thenReturn(Map.of());
        when(runtimeManager.artifactPath(pluginId)).thenReturn(Optional.empty());
        when(installer.commitTransaction(prepared)).thenAnswer(invocation -> {
            prepared.confirmCommitState(PreparedPluginTransaction.CommitState.COMMITTED);
            return committed;
        });
        doThrow(new AssertionError("committed target changed"))
                .when(installer).verifyCommittedTarget(committed);
        when(installer.rollbackTransaction(committed)).thenAnswer(invocation -> {
            committed.confirmDurableState(CommittedPluginTransaction.DurableState.ROLLED_BACK);
            prepared.confirmCommitState(PreparedPluginTransaction.CommitState.ROLLED_BACK);
            return true;
        });

        PluginActivationResult activation = coordinator().installOrUpdate(
                staged, false, PluginPackageOrigin.localUpload());

        assertThat(activation.installResult().outcome()).isEqualTo(PluginInstallOutcome.FAILED);
        assertThat(activation.rolledBack()).isTrue();
        verify(installer).verifyCommittedTarget(committed);
        verify(installer).rollbackTransaction(committed);
        verify(runtimeManager, never()).loadPlugin(any(Path.class));
        verify(runtimeManager, never()).startPlugin(pluginId);
        verify(lifecycleService, never()).start(pluginId);
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
        when(installer.commitTransaction(prepared)).thenAnswer(invocation -> {
            prepared.confirmCommitState(PreparedPluginTransaction.CommitState.COMMITTED);
            return committed;
        });
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
    @DisplayName("激活事务已退役后完成调用抛 fatal 时保留新代且不再回滚")
    void fatalAfterRetiredActivationDoesNotRollbackCommittedGeneration() {
        String pluginId = "retired-fatal-plugin";
        Path staged = Path.of("plugins", ".staging", "tx-retired-fatal", "new.jar");
        Path target = Path.of("plugins", "retired-fatal-plugin.jar");
        PluginDescriptor descriptor = descriptor(pluginId);
        PluginInstallResult result = new PluginInstallResult(
                PluginInstallOutcome.INSTALLED, descriptor, target, null, List.of());
        PreparedPluginTransaction prepared = new PreparedPluginTransaction(
                "tx-retired-fatal", result, staged.getParent(), staged, target, List.of());
        CommittedPluginTransaction committed = new CommittedPluginTransaction(prepared, List.of());
        LoadedPluginPackage loaded = mock(LoadedPluginPackage.class);
        TestVirtualMachineError fatal = new TestVirtualMachineError("fatal after transaction retirement");
        when(loaded.packageId()).thenReturn(pluginId);
        when(installer.prepareTransaction(staged, false, PluginPackageOrigin.localUpload())).thenReturn(prepared);
        when(dependencyResolver.activationProblems(descriptor)).thenReturn(List.of());
        when(runtimeManager.packagePhases()).thenReturn(Map.of());
        when(runtimeManager.artifactPath(pluginId)).thenReturn(Optional.empty());
        when(installer.commitTransaction(prepared)).thenAnswer(invocation -> {
            prepared.confirmCommitState(PreparedPluginTransaction.CommitState.COMMITTED);
            return committed;
        });
        when(runtimeManager.loadPlugin(target)).thenReturn(loaded);
        when(lifecycleService.phase(pluginId)).thenReturn(Optional.of(PluginRuntimePhase.STARTED));
        doAnswer(invocation -> {
            committed.confirmDurableState(CommittedPluginTransaction.DurableState.ACTIVATED);
            return null;
        }).when(installer).markActivated(committed);
        doAnswer(invocation -> {
            committed.confirmDurableState(CommittedPluginTransaction.DurableState.COMMITTED);
            committed.confirmDurableState(CommittedPluginTransaction.DurableState.RETIRED);
            throw fatal;
        }).when(installer).completeTransaction(committed);

        assertThatThrownBy(() -> coordinator().installOrUpdate(
                staged, false, PluginPackageOrigin.localUpload()))
                .isSameAs(fatal);

        assertThat(committed.durableState())
                .isEqualTo(CommittedPluginTransaction.DurableState.RETIRED);
        verify(installer, never()).rollbackTransaction(committed);
        verify(installer, never()).discardPrepared(prepared);
        verify(runtimeManager, never()).unloadPlugin(pluginId);
    }

    @Test
    @DisplayName("已激活事务无法完成退役时返回恢复阻断且不回滚新代")
    void committedCleanupFailureReturnsRecoveryBlockedReceipt() {
        String pluginId = "retirement-blocked-plugin";
        Path staged = Path.of("plugins", ".staging", "tx-retirement-blocked", "new.jar");
        Path target = Path.of("plugins", "retirement-blocked-plugin.jar");
        PluginDescriptor descriptor = descriptor(pluginId);
        PluginInstallResult result = new PluginInstallResult(
                PluginInstallOutcome.INSTALLED, descriptor, target, null, List.of());
        PreparedPluginTransaction prepared = new PreparedPluginTransaction(
                "tx-retirement-blocked", result, staged.getParent(), staged, target, List.of());
        CommittedPluginTransaction committed = new CommittedPluginTransaction(prepared, List.of());
        LoadedPluginPackage loaded = mock(LoadedPluginPackage.class);
        when(loaded.packageId()).thenReturn(pluginId);
        when(installer.prepareTransaction(staged, false, PluginPackageOrigin.localUpload())).thenReturn(prepared);
        when(dependencyResolver.activationProblems(descriptor)).thenReturn(List.of());
        when(runtimeManager.packagePhases()).thenReturn(Map.of());
        when(runtimeManager.artifactPath(pluginId)).thenReturn(Optional.empty());
        when(installer.commitTransaction(prepared)).thenAnswer(invocation -> {
            prepared.confirmCommitState(PreparedPluginTransaction.CommitState.COMMITTED);
            return committed;
        });
        when(runtimeManager.loadPlugin(target)).thenReturn(loaded);
        when(lifecycleService.phase(pluginId)).thenReturn(Optional.of(PluginRuntimePhase.STARTED));
        doAnswer(invocation -> {
            committed.confirmDurableState(CommittedPluginTransaction.DurableState.ACTIVATED);
            return null;
        }).when(installer).markActivated(committed);
        doAnswer(invocation -> {
            committed.confirmDurableState(CommittedPluginTransaction.DurableState.COMMITTED);
            committed.markRecoveryBlocked();
            throw new AssertionError("transaction retirement blocked");
        }).when(installer).completeTransaction(committed);
        ExternalPluginLifecycleCoordinator coordinator = coordinator();

        PluginActivationResult activation = coordinator.installOrUpdate(
                staged, false, PluginPackageOrigin.localUpload());

        assertThat(activation.activated()).isTrue();
        assertThat(activation.rolledBack()).isFalse();
        assertThat(activation.recoveryBlocked()).isTrue();
        verify(installer, never()).rollbackTransaction(committed);
        assertThat(coordinator.operation(pluginId)).hasValueSatisfying(snapshot ->
                assertThat(snapshot.operation()).isEqualTo(ExternalPluginOperation.FAILED));
    }

    @Test
    @DisplayName("PREPARED 退役无法证明安全时标记恢复阻断且不触碰仍运行的旧代")
    void unsafePreparedReceiptBlocksRecoveryWithoutRuntimeCompensation() {
        PreparedPluginTransaction prepared = acceptedTransaction(
                "tx-prepared-unsafe", "prepared-unsafe-plugin", PluginLifecyclePolicy.HOT_RELOAD);
        String pluginId = prepared.result().pluginId();
        Path previousArtifact = Path.of("plugins", pluginId + "-old.jar");
        when(installer.prepareTransaction(
                prepared.stagedArtifact(), false, PluginPackageOrigin.localUpload())).thenReturn(prepared);
        when(dependencyResolver.activationProblems(prepared.result().descriptor())).thenReturn(List.of());
        when(runtimeManager.packagePhases()).thenReturn(
                Map.of(pluginId, PluginRuntimePackagePhase.STARTED));
        when(runtimeManager.artifactPath(pluginId)).thenReturn(Optional.of(previousArtifact));
        when(lifecycleService.phase(pluginId)).thenReturn(Optional.of(PluginRuntimePhase.STARTED));
        doThrow(new AssertionError("preflight verification failed"))
                .when(installer).verifyCurrentArtifacts(prepared);
        when(installer.discardPrepared(prepared)).thenAnswer(invocation -> {
            prepared.confirmCommitState(PreparedPluginTransaction.CommitState.UNSAFE);
            return false;
        });
        ExternalPluginLifecycleCoordinator coordinator = coordinator();

        PluginActivationResult activation = coordinator.installOrUpdate(
                prepared.stagedArtifact(), false, PluginPackageOrigin.localUpload());

        assertThat(activation.installResult().outcome()).isEqualTo(PluginInstallOutcome.FAILED);
        assertThat(activation.rolledBack()).isFalse();
        assertThat(activation.recoveryBlocked()).isTrue();
        verify(runtimeManager, never()).unloadPlugin(pluginId);
        verify(runtimeManager, never()).loadPlugin(any(Path.class));
        verify(runtimeManager, never()).startPlugin(pluginId);
        verify(lifecycleService, never()).unload(pluginId);
        verify(lifecycleService, never()).load(pluginId);
        verify(lifecycleService, never()).start(pluginId);
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

    @Test
    @DisplayName("artifact 已 durable 删除后 forget 抛 Error 不恢复旧运行时")
    void durableRemovalForgetErrorDoesNotRestoreOldRuntime() {
        String pluginId = "forget-error-plugin";
        Path previousArtifact = Path.of("plugins", "forget-error-plugin.jar");
        configureLoadedRemoval(pluginId, previousArtifact);
        when(installer.removeInstalled(any(PluginRemovalAttempt.class))).thenAnswer(invocation -> {
            PluginRemovalAttempt attempt = invocation.getArgument(0);
            attempt.confirm(PluginRemovalAttempt.Outcome.REMOVED);
            return true;
        });
        doThrow(new AssertionError("forget installation failed"))
                .when(lifecycleService).forgetInstallation(pluginId);
        ExternalPluginLifecycleCoordinator coordinator = coordinator();

        coordinator.remove(pluginId);

        verify(installer).removeInstalled(any(PluginRemovalAttempt.class));
        verify(recoveryModeService).refresh();
        verifyOldRuntimeWasNotRestored(pluginId);
        assertThat(coordinator.operation(pluginId)).hasValueSatisfying(snapshot -> {
            assertThat(snapshot.operation()).isEqualTo(ExternalPluginOperation.FAILED);
            assertThat(snapshot.diagnostic()).contains("forget installation failed");
        });
    }

    @Test
    @DisplayName("artifact 已 durable 删除后恢复模式刷新抛 Error 不恢复旧运行时")
    void durableRemovalRefreshErrorDoesNotRestoreOldRuntime() {
        String pluginId = "remove-refresh-error-plugin";
        Path previousArtifact = Path.of("plugins", "remove-refresh-error-plugin.jar");
        configureLoadedRemoval(pluginId, previousArtifact);
        when(installer.removeInstalled(any(PluginRemovalAttempt.class))).thenAnswer(invocation -> {
            PluginRemovalAttempt attempt = invocation.getArgument(0);
            attempt.confirm(PluginRemovalAttempt.Outcome.REMOVED);
            return true;
        });
        doThrow(new AssertionError("post-removal refresh failed")).when(recoveryModeService).refresh();
        ExternalPluginLifecycleCoordinator coordinator = coordinator();

        coordinator.remove(pluginId);

        verify(installer).removeInstalled(any(PluginRemovalAttempt.class));
        verify(lifecycleService).forgetInstallation(pluginId);
        verifyOldRuntimeWasNotRestored(pluginId);
        assertThat(coordinator.operation(pluginId)).hasValueSatisfying(snapshot -> {
            assertThat(snapshot.operation()).isEqualTo(ExternalPluginOperation.FAILED);
            assertThat(snapshot.diagnostic()).contains("post-removal refresh failed");
        });
    }

    @Test
    @DisplayName("删除回执已确认 REMOVED 后抛 fatal 时不恢复旧运行时")
    void fatalAfterDurableRemovalDoesNotRestoreOldRuntime() {
        String pluginId = "removed-fatal-plugin";
        Path previousArtifact = Path.of("plugins", "removed-fatal-plugin.jar");
        TestVirtualMachineError fatal = new TestVirtualMachineError("fatal after durable removal");
        configureLoadedRemoval(pluginId, previousArtifact);
        when(installer.removeInstalled(any(PluginRemovalAttempt.class))).thenAnswer(invocation -> {
            PluginRemovalAttempt attempt = invocation.getArgument(0);
            attempt.confirm(PluginRemovalAttempt.Outcome.REMOVED);
            throw fatal;
        });

        assertThatThrownBy(() -> coordinator().remove(pluginId)).isSameAs(fatal);

        verify(lifecycleService).forgetInstallation(pluginId);
        verify(recoveryModeService).refresh();
        verifyOldRuntimeWasNotRestored(pluginId);
    }

    @Test
    @DisplayName("删除回执无法确认安全终态时不尝试恢复旧运行时")
    void unsafeRemovalReceiptDoesNotRestoreOldRuntime() {
        String pluginId = "unsafe-removal-plugin";
        Path previousArtifact = Path.of("plugins", "unsafe-removal-plugin.jar");
        configureLoadedRemoval(pluginId, previousArtifact);
        when(installer.removeInstalled(any(PluginRemovalAttempt.class))).thenAnswer(invocation -> {
            PluginRemovalAttempt attempt = invocation.getArgument(0);
            attempt.confirm(PluginRemovalAttempt.Outcome.UNSAFE);
            throw new IllegalStateException("removal terminal state is unsafe");
        });
        ExternalPluginLifecycleCoordinator coordinator = coordinator();

        assertThatThrownBy(() -> coordinator.remove(pluginId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unsafe");

        verifyOldRuntimeWasNotRestored(pluginId);
        assertThat(coordinator.operation(pluginId)).hasValueSatisfying(snapshot ->
                assertThat(snapshot.operation()).isEqualTo(ExternalPluginOperation.FAILED));
    }

    private void configureLoadedRemoval(String pluginId, Path previousArtifact) {
        when(runtimeManager.packagePhases()).thenReturn(
                Map.of(pluginId, PluginRuntimePackagePhase.STARTED));
        when(runtimeManager.artifactPath(pluginId)).thenReturn(Optional.of(previousArtifact));
        when(runtimeManager.activeDependents(pluginId)).thenReturn(List.of());
        when(lifecycleService.phase(pluginId)).thenReturn(Optional.of(PluginRuntimePhase.STOPPED));
        when(lifecycleService.generation(pluginId)).thenReturn(Optional.of(7L));
        when(runtimeManager.unloadPlugin(pluginId)).thenReturn(new UnloadedPluginPackage(
                pluginId, previousArtifact, "1.0.0", 7L));
    }

    private void verifyOldRuntimeWasNotRestored(String pluginId) {
        verify(runtimeManager, never()).loadPlugin(any(Path.class));
        verify(runtimeManager, never()).startPlugin(pluginId);
        verify(lifecycleService, never()).load(pluginId);
        verify(lifecycleService, never()).start(pluginId);
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

    private static PluginDescriptor descriptor(String pluginId, List<String> replacements) {
        return new PluginDescriptor(pluginId, pluginId, "1.0.0",
                PluginApiRequirement.unspecified(), List.of(), "example.Plugin", null,
                "plugin.label", null, null, null, PluginKind.FEATURE, replacements,
                PluginLifecyclePolicy.HOT_RELOAD);
    }

    private void assertPreparedInstallFailureDiscards(
            PreparedPluginTransaction prepared, AssertionError failure) {
        when(installer.discardPrepared(prepared)).thenAnswer(invocation -> {
            prepared.confirmCommitState(PreparedPluginTransaction.CommitState.DISCARDED);
            return true;
        });
        ExternalPluginLifecycleCoordinator coordinator = coordinator();

        assertThatThrownBy(() -> coordinator.installOrUpdate(
                prepared.stagedArtifact(), false, PluginPackageOrigin.localUpload()))
                .isInstanceOf(PluginLifecycleException.class)
                .hasCause(failure);

        verify(installer).discardPrepared(prepared);
        verify(installer, never()).commitTransaction(any(PreparedPluginTransaction.class));
        verify(installer, never()).rollbackTransaction(any(CommittedPluginTransaction.class));
        assertThat(coordinator.operation(prepared.result().pluginId())).hasValueSatisfying(snapshot ->
                assertThat(snapshot.operation()).isEqualTo(ExternalPluginOperation.IDLE));
    }

    private static PreparedPluginTransaction acceptedTransaction(
            String transactionId, String pluginId, PluginLifecyclePolicy lifecyclePolicy) {
        Path staged = Path.of("plugins", ".staging", transactionId, "new.jar");
        Path target = Path.of("plugins", pluginId + ".jar");
        PluginInstallResult result = new PluginInstallResult(
                PluginInstallOutcome.INSTALLED, descriptor(pluginId, lifecyclePolicy),
                target, null, List.of());
        return new PreparedPluginTransaction(
                transactionId, result, staged.getParent(), staged, target, List.of());
    }

    private static PreparedPluginTransaction rejectedTransaction(String transactionId, Path stagedArtifact) {
        PluginInstallResult result = new PluginInstallResult(
                PluginInstallOutcome.REJECTED_MALFORMED, null, null, null, List.of("invalid package"));
        return new PreparedPluginTransaction(transactionId, result, stagedArtifact.getParent(),
                stagedArtifact, null, List.of());
    }

    private static void awaitLatch(CountDownLatch latch, String description) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for " + description);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for " + description, e);
        }
    }

    private static void shutdown(ExecutorService executor) {
        executor.shutdownNow();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class TestVirtualMachineError extends VirtualMachineError {
        private TestVirtualMachineError(String message) {
            super(message);
        }
    }
}
