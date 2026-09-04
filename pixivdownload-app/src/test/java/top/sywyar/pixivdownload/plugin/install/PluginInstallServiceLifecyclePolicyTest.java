package top.sywyar.pixivdownload.plugin.install;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.lifecycle.ExternalPluginLifecycleCoordinator;
import top.sywyar.pixivdownload.plugin.lifecycle.ExternalPluginOperation;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginRuntimePhase;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.VersionRequirement;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDescriptor;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginLifecyclePolicy;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginInstallOutcome;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginInstallResult;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageOrigin;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageSource;
import top.sywyar.pixivdownload.plugin.runtime.install.trust.PluginTrustRequirement;
import top.sywyar.pixivdownload.plugin.signature.SignatureMetadata;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
        PluginDescriptor descriptor = descriptor("gui-swing", PluginLifecyclePolicy.PROCESS_RESTART);
        PluginActivationResult activation = activation(descriptor, false, null);
        Path packageFile = Path.of("gui-swing.jar");
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

    @Test
    @DisplayName("正式运行时未签名包由安装器生成精确制品信任确认要求")
    void formalRuntimeRequestsExactArtifactTrustForUnsignedUpload() {
        PluginDescriptor descriptor = descriptor("unsigned-plugin", PluginLifecyclePolicy.HOT_RELOAD);
        PluginTrustRequirement requirement = new PluginTrustRequirement(
                descriptor.id(), descriptor.version(), PluginPackageSource.LOCAL_UPLOAD,
                null, false, false, null, null, "0".repeat(64), descriptor.executionMode());
        PluginInstallResult result = new PluginInstallResult(
                PluginInstallOutcome.TRUST_CONFIRMATION_REQUIRED,
                descriptor,
                null,
                null,
                List.of(),
                requirement);
        PluginPackageOrigin origin = PluginPackageOrigin.localUnsignedUpload(null);
        when(coordinator.installOrUpdate(any(Path.class), eq(false), eq(origin)))
                .thenReturn(new PluginActivationResult(
                        null, result, false, false, null, ExternalPluginOperation.IDLE, null));
        when(dependencyResolver.installedProblems(descriptor)).thenReturn(List.of());

        PluginInstallReport report = service(false).install(packageUpload(), null, false);

        assertThat(report.outcome()).isEqualTo(PluginInstallOutcome.TRUST_CONFIRMATION_REQUIRED);
        assertThat(report.trustRequirement()).isEqualTo(requirement);
        verify(coordinator).installOrUpdate(any(Path.class), eq(false), eq(origin));
    }

    @Test
    @DisplayName("开发模式允许未签名本地安装并保留未验证来源")
    void developmentModeAllowsUnsignedLocalUpload() {
        PluginDescriptor descriptor = descriptor("dev-plugin", PluginLifecyclePolicy.HOT_RELOAD);
        PluginPackageOrigin origin = PluginPackageOrigin.localUpload();
        when(coordinator.installOrUpdate(any(Path.class), eq(false), eq(origin)))
                .thenReturn(activation(descriptor, true, PluginRuntimePhase.STARTED));
        when(dependencyResolver.installedProblems(descriptor)).thenReturn(List.of());

        PluginInstallReport report = service(true).install(packageUpload(), null, false);

        assertThat(report.outcome()).isEqualTo(PluginInstallOutcome.INSTALLED);
        verify(coordinator).installOrUpdate(any(Path.class), eq(false), eq(origin));
    }

    @Test
    @DisplayName("正式运行时解析有界 detached 签名并随本地来源交给安装器验签")
    void formalRuntimeForwardsDetachedSignatureForVerification() {
        PluginDescriptor descriptor = descriptor("signed-plugin", PluginLifecyclePolicy.HOT_RELOAD);
        SignatureMetadata metadata = new SignatureMetadata(
                SignatureMetadata.FORMAT_VERSION, SignatureMetadata.ED25519, "official-key", "c2ln");
        PluginPackageOrigin origin = PluginPackageOrigin.localUpload(metadata);
        when(coordinator.installOrUpdate(any(Path.class), eq(false), eq(origin)))
                .thenReturn(activation(descriptor, true, PluginRuntimePhase.STARTED));
        when(dependencyResolver.installedProblems(descriptor)).thenReturn(List.of());
        MockMultipartFile signature = new MockMultipartFile(
                "signature", "signed-plugin.sig", "application/json",
                ("{\"formatVersion\":1,\"algorithm\":\"Ed25519\","
                        + "\"keyId\":\"official-key\",\"value\":\"c2ln\"}")
                        .getBytes(StandardCharsets.UTF_8));

        PluginInstallReport report = service(false).install(packageUpload(), signature, false);

        assertThat(report.outcome()).isEqualTo(PluginInstallOutcome.INSTALLED);
        verify(coordinator).installOrUpdate(any(Path.class), eq(false), eq(origin));
    }

    @Test
    @DisplayName("畸形、重复字段或超限 detached 签名在插件包暂存前拒绝")
    void invalidDetachedSignatureIsRejectedBeforeStaging() {
        MockMultipartFile malformed = new MockMultipartFile(
                "signature", "plugin.sig", "application/json", "{".getBytes(StandardCharsets.UTF_8));
        MockMultipartFile duplicateKey = new MockMultipartFile(
                "signature", "plugin.sig", "application/json",
                ("{\"formatVersion\":1,\"algorithm\":\"Ed25519\","
                        + "\"keyId\":\"first\",\"keyId\":\"second\",\"value\":\"c2ln\"}")
                        .getBytes(StandardCharsets.UTF_8));
        MockMultipartFile trailingToken = new MockMultipartFile(
                "signature", "plugin.sig", "application/json",
                ("{\"formatVersion\":1,\"algorithm\":\"Ed25519\","
                        + "\"keyId\":\"official-key\",\"value\":\"c2ln\"} true")
                        .getBytes(StandardCharsets.UTF_8));
        MockMultipartFile oversized = new MockMultipartFile(
                "signature", "plugin.sig", "application/json",
                new byte[PluginInstallService.MAX_SIGNATURE_BYTES + 1]);

        assertThat(service(false).install(packageUpload(), malformed, false).outcome())
                .isEqualTo(PluginInstallOutcome.REJECTED_INTEGRITY);
        assertThat(service(false).install(packageUpload(), duplicateKey, false).outcome())
                .isEqualTo(PluginInstallOutcome.REJECTED_INTEGRITY);
        assertThat(service(false).install(packageUpload(), trailingToken, false).outcome())
                .isEqualTo(PluginInstallOutcome.REJECTED_INTEGRITY);
        assertThat(service(false).install(packageUpload(), oversized, false).outcome())
                .isEqualTo(PluginInstallOutcome.REJECTED_INTEGRITY);
        verify(coordinator, never()).installOrUpdate(any(), eq(false), any());
    }

    private PluginInstallService service() {
        return new PluginInstallService(coordinator, dependencyResolver);
    }

    private PluginInstallService service(boolean developmentModeEnabled) {
        return new PluginInstallService(coordinator, dependencyResolver, developmentModeEnabled);
    }

    private static MockMultipartFile packageUpload() {
        return new MockMultipartFile(
                "file", "plugin.zip", "application/zip", new byte[]{1, 2, 3});
    }

    private static PluginActivationResult activation(
            PluginDescriptor descriptor, boolean activated, PluginRuntimePhase phase) {
        PluginInstallResult result = new PluginInstallResult(
                PluginInstallOutcome.INSTALLED, descriptor, Path.of(descriptor.id() + ".jar"), null, List.of());
        return new PluginActivationResult("tx", result, activated, false, null,
                ExternalPluginOperation.INSTALLING, phase);
    }

    private static PluginDescriptor descriptor(String pluginId, PluginLifecyclePolicy lifecyclePolicy) {
        return new PluginDescriptor(pluginId, pluginId, "1.0.0", VersionRequirement.unspecified(), List.of(),
                "example.Plugin", null, "plugin.label", null, null, null, PluginKind.FEATURE,
                List.of(), lifecyclePolicy);
    }
}
