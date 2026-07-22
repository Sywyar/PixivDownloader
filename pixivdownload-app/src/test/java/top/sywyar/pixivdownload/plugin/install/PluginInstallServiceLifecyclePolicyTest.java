package top.sywyar.pixivdownload.plugin.install;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.lifecycle.ExternalPluginLifecycleCoordinator;
import top.sywyar.pixivdownload.plugin.lifecycle.ExternalPluginOperation;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginRuntimePhase;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginApiRequirement;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDescriptor;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginLifecyclePolicy;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginInstallOutcome;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginInstallResult;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageOrigin;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("插件安装报告生命周期策略语义")
class PluginInstallServiceLifecyclePolicyTest {

    @Mock
    ExternalPluginLifecycleCoordinator coordinator;
    @Mock
    PluginDependencyResolver dependencyResolver;

    @Test
    @DisplayName("后端重启策略已即时激活时不误报等待重启生效")
    void backendRestartActivatedInstallIsImmediatelyEffective() {
        PluginDescriptor descriptor = descriptor("backend-plugin", PluginLifecyclePolicy.BACKEND_RESTART);
        PluginActivationResult activation = activation(descriptor, true, PluginRuntimePhase.STARTED);
        Path packageFile = Path.of("backend-plugin.jar");
        PluginPackageOrigin origin = PluginPackageOrigin.localUpload();
        when(coordinator.installOrUpdate(packageFile, false, origin)).thenReturn(activation);
        when(dependencyResolver.installedProblems(descriptor)).thenReturn(List.of());

        PluginInstallReport report = service().installTrustedFile(packageFile, false, origin);

        assertThat(report.activated()).isTrue();
        assertThat(report.effectiveAfterRestart()).isFalse();
    }

    @Test
    @DisplayName("进程重启策略未即时激活时报告等待重启生效")
    void processRestartDeferredInstallRequiresRestart() {
        PluginDescriptor descriptor = descriptor("gui-theme", PluginLifecyclePolicy.PROCESS_RESTART);
        PluginActivationResult activation = activation(descriptor, false, null);
        Path packageFile = Path.of("gui-theme.jar");
        PluginPackageOrigin origin = PluginPackageOrigin.localUpload();
        when(coordinator.installOrUpdate(packageFile, false, origin)).thenReturn(activation);
        when(dependencyResolver.installedProblems(descriptor)).thenReturn(List.of());

        PluginInstallReport report = service().installTrustedFile(packageFile, false, origin);

        assertThat(report.activated()).isFalse();
        assertThat(report.effectiveAfterRestart()).isTrue();
    }

    @Test
    @DisplayName("事务恢复被阻断时安装报告保留独立机器态")
    void recoveryBlockedStateIsPreservedInReport() {
        PluginDescriptor descriptor = descriptor("blocked-plugin", PluginLifecyclePolicy.HOT_RELOAD);
        PluginInstallResult result = new PluginInstallResult(
                PluginInstallOutcome.FAILED, descriptor, Path.of("blocked-plugin.jar"), null, List.of());
        PluginActivationResult activation = new PluginActivationResult(
                "tx-blocked", result, false, false, null,
                ExternalPluginOperation.FAILED, PluginRuntimePhase.STOPPED, true);
        Path packageFile = Path.of("blocked-plugin.jar");
        PluginPackageOrigin origin = PluginPackageOrigin.localUpload();
        when(coordinator.installOrUpdate(packageFile, false, origin)).thenReturn(activation);
        when(dependencyResolver.installedProblems(descriptor)).thenReturn(List.of());

        PluginInstallReport report = service().installTrustedFile(packageFile, false, origin);

        assertThat(report.recoveryBlocked()).isTrue();
        assertThat(report.effectiveAfterRestart()).isFalse();
    }

    private PluginInstallService service() {
        return new PluginInstallService(coordinator, dependencyResolver);
    }

    private static PluginActivationResult activation(
            PluginDescriptor descriptor, boolean activated, PluginRuntimePhase phase) {
        PluginInstallResult result = new PluginInstallResult(
                PluginInstallOutcome.INSTALLED, descriptor, Path.of(descriptor.id() + ".jar"), null, List.of());
        return new PluginActivationResult("tx", result, activated, false, null,
                ExternalPluginOperation.INSTALLING, phase);
    }

    private static PluginDescriptor descriptor(String pluginId, PluginLifecyclePolicy lifecyclePolicy) {
        return new PluginDescriptor(pluginId, pluginId, "1.0.0", PluginApiRequirement.unspecified(), List.of(),
                "example.Plugin", null, "plugin.label", null, null, null, PluginKind.FEATURE,
                List.of(), lifecyclePolicy);
    }
}
