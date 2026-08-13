package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import top.sywyar.pixivdownload.plugin.api.PluginApiVersion;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.runtime.status.PluginRuntimeVerificationSnapshot;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginApiRequirement;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDependencyRef;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDescriptor;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginLifecyclePolicy;
import top.sywyar.pixivdownload.plugin.runtime.install.ExternalPluginInstaller;
import top.sywyar.pixivdownload.plugin.runtime.install.model.InstalledPlugin;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageOrigin;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageSource;
import top.sywyar.pixivdownload.plugin.runtime.install.provenance.PluginProvenanceRecord;
import top.sywyar.pixivdownload.plugin.runtime.install.provenance.PluginProvenanceStore;
import top.sywyar.pixivdownload.plugin.runtime.install.provenance.InstalledPluginInventorySnapshot;
import top.sywyar.pixivdownload.plugin.runtime.install.provenance.InstalledPluginSnapshot;
import top.sywyar.pixivdownload.plugin.runtime.install.provenance.ProvenanceSnapshotState;
import top.sywyar.pixivdownload.plugin.runtime.install.transaction.PluginRecoveryGateSnapshot;
import top.sywyar.pixivdownload.plugin.runtime.install.transaction.PluginTransactionRecoveryReport;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageIntegrity;
import top.sywyar.pixivdownload.plugin.runtime.status.PluginDiagnostic;
import top.sywyar.pixivdownload.plugin.runtime.status.PluginStatus;
import top.sywyar.pixivdownload.plugin.runtime.status.PluginStatusReport;
import top.sywyar.pixivdownload.plugin.runtime.status.RequiredPluginPolicy;
import top.sywyar.pixivdownload.plugin.runtime.status.RecoveryModeReason;
import top.sywyar.pixivdownload.plugin.signature.VerificationResult;
import top.sywyar.pixivdownload.plugin.signature.VerificationStatus;
import top.sywyar.pixivdownload.plugin.signature.SignatureMetadata;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginLifecycleException;
import top.sywyar.pixivdownload.plugin.lifecycle.ExternalPluginLifecycleCoordinator;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginLifecycleService;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginRuntimePhase;
import top.sywyar.pixivdownload.plugin.management.PluginManagementErrorCode;
import top.sywyar.pixivdownload.plugin.management.PluginManagementException;
import top.sywyar.pixivdownload.plugin.management.PluginManagementService;
import top.sywyar.pixivdownload.plugin.management.PluginStatusService;
import top.sywyar.pixivdownload.plugin.recovery.RecoveryModeService;
import top.sywyar.pixivdownload.plugin.verification.PluginVerificationProjector;

/**
 * {@link PluginManagementService} 单测：读模型合并（来源 / 受管 / 阶段 / 必选 / 可用动词）与运行期动词前置守卫
 * （必选不可停用、内置 / 未激活 / 未知 id 拒绝、非法流转转 409）。生命周期与状态报告以 Mockito 桩注入，必选策略用真值。
 */
@DisplayName("PluginManagementService 插件管理后端服务")
class PluginManagementServiceTest {

    private static final String BUILT_IN_ID = "core";      // 真实内置插件 id（BuiltInPlugins.isBuiltIn 为真）
    private static final String EXTERNAL_ID = "demo-ext";  // 非内置：视作外置
    private static final String REQUIRED_EXTERNAL_ID = "req-ext";
    private static final String MISSING_ID = "missing-one";

    private static PluginDescriptor descriptor(String id, PluginKind kind) {
        return new PluginDescriptor(id, id, "1.0.0", PluginApiRequirement.unspecified(),
                List.of(), id + ".Plugin", id, "nav.label", id + ".summary", "book", "amber", kind);
    }

    private static PluginManagementService service(PluginStatusService status,
                                                   PluginLifecycleService lifecycle,
                                                   RequiredPluginPolicy policy,
                                                   RecoveryModeService recovery) {
        when(status.recoveryGateSnapshot()).thenReturn(safeRecoveryGate());
        return new PluginManagementService(status, lifecycle, policy, recovery);
    }

    private static PluginManagementService service(PluginStatusService status,
                                                   PluginLifecycleService lifecycle,
                                                   RequiredPluginPolicy policy,
                                                   RecoveryModeService recovery,
                                                   PluginToggleProperties toggles) {
        when(status.recoveryGateSnapshot()).thenReturn(safeRecoveryGate());
        return new PluginManagementService(status, lifecycle, policy, recovery, toggles);
    }

    private static PluginRecoveryGateSnapshot safeRecoveryGate() {
        return PluginRecoveryGateSnapshot.safe(PluginTransactionRecoveryReport.success());
    }

    private static PluginRecoveryGateSnapshot blockedRecoveryGate() {
        return PluginRecoveryGateSnapshot.blocked(new PluginTransactionRecoveryReport(List.of(
                new PluginTransactionRecoveryReport.Failure(
                        "tx-broken",
                        Path.of("plugins", ".staging", "tx-broken"),
                        PluginTransactionRecoveryReport.FailureKind.RECOVERY_FAILED,
                        "transaction recovery failed"))));
    }

    @Test
    @DisplayName("list() 合并状态报告与运行期阶段：来源 / 受管 / 阶段 / 必选 / 恢复模式分类正确")
    void listMergesStatusAndRuntimePhase() {
        PluginStatusService status = mock(PluginStatusService.class);
        PluginLifecycleService lifecycle = mock(PluginLifecycleService.class);
        RecoveryModeService recovery = mock(RecoveryModeService.class);

        when(status.report()).thenReturn(new PluginStatusReport(List.of(
                new PluginDiagnostic(BUILT_IN_ID, PluginStatus.STARTED, descriptor(BUILT_IN_ID, PluginKind.FEATURE),
                        false, List.of()),
                new PluginDiagnostic(EXTERNAL_ID, PluginStatus.STARTED, descriptor(EXTERNAL_ID, PluginKind.FEATURE),
                        false, List.of()),
                new PluginDiagnostic(MISSING_ID, PluginStatus.MISSING_REQUIRED, null, true,
                        List.of("required but not installed")))));
        when(lifecycle.managedPluginIds()).thenReturn(Set.of(EXTERNAL_ID));
        when(lifecycle.phase(EXTERNAL_ID)).thenReturn(Optional.of(PluginRuntimePhase.STARTED));
        when(recovery.isActive()).thenReturn(true);
        when(recovery.reasons()).thenReturn(List.of(new RecoveryModeReason(
                MISSING_ID, PluginStatus.MISSING_REQUIRED, "plugin.recovery.missing",
                PluginApiRequirement.unspecified(), List.of("required but not installed"))));

        PluginManagementService.PluginManagementReport report =
                service(status, lifecycle, RequiredPluginPolicy.empty(), recovery).list();

        assertThat(report.recoveryMode()).isTrue();
        assertThat(report.recoveryReasons()).extracting(RecoveryModeReason::pluginId)
                .containsExactly(MISSING_ID);
        assertThat(report.plugins()).extracting(PluginManagementService.PluginManagementEntry::id)
                .containsExactly(BUILT_IN_ID, EXTERNAL_ID, MISSING_ID);

        PluginManagementService.PluginManagementEntry builtIn = entry(report, BUILT_IN_ID);
        assertThat(builtIn.source()).isEqualTo("built-in");
        assertThat(builtIn.managed()).isFalse();
        assertThat(builtIn.toggleable()).isFalse();
        assertThat(builtIn.runtimePhase()).isNull();
        assertThat(builtIn.availableActions()).isEmpty();

        PluginManagementService.PluginManagementEntry external = entry(report, EXTERNAL_ID);
        assertThat(external.source()).isEqualTo("external");
        assertThat(external.managed()).isTrue();
        assertThat(external.runtimePhase()).isEqualTo(PluginRuntimePhase.STARTED);
        assertThat(external.allowDisable()).isTrue();
        assertThat(external.lifecyclePolicy()).isEqualTo(PluginLifecyclePolicy.HOT_RELOAD);
        assertThat(external.configuredEnabled()).isTrue();
        assertThat(external.toggleable()).isTrue();
        assertThat(external.availableActions()).containsExactlyInAnyOrder(
                "quiesce", "stop", "unload", "remove", "restart", "reload");
        // 未声明 requires 的描述符 → specified=false / satisfied=true / required="(unspecified)"，无依赖 → 空列表。
        assertThat(external.apiRequirement().specified()).isFalse();
        assertThat(external.apiRequirement().satisfied()).isTrue();
        assertThat(external.apiRequirement().required()).isEqualTo("(unspecified)");
        assertThat(external.dependencies()).isEmpty();
        // 展示元数据投影：descriptionKey 来自描述符 description（纯 key），iconKey/colorToken 为描述符声明的受控 token。
        assertThat(external.descriptionKey()).isEqualTo(EXTERNAL_ID + ".summary");
        assertThat(external.iconKey()).isEqualTo("book");
        assertThat(external.colorToken()).isEqualTo("amber");

        PluginManagementService.PluginManagementEntry missing = entry(report, MISSING_ID);
        assertThat(missing.source()).isEqualTo("not-installed");
        assertThat(missing.requiredByPolicy()).isTrue();
        assertThat(missing.displayNameKey()).isNull();
        assertThat(missing.managed()).isFalse();
        // 未安装的必选项无描述符 → apiRequirement 为 null、dependencies 为空列表（不抛、不臆造）。
        assertThat(missing.apiRequirement()).isNull();
        assertThat(missing.dependencies()).isEmpty();
        // 无描述符 → descriptionKey 为 null（前端优雅回退）；iconKey/colorToken 回退到 plugin-api 默认占位 token。
        assertThat(missing.descriptionKey()).isNull();
        assertThat(missing.iconKey()).isEqualTo("puzzle");
        assertThat(missing.colorToken()).isEqualTo("neutral");
    }

    @Test
    @DisplayName("官方外置插件展示元数据：管理 DTO 对官方 descriptor 原样投影 canonical key/token")
    void listProjectsOfficialCanonicalDisplayMetadata() {
        PluginStatusService status = mock(PluginStatusService.class);
        PluginLifecycleService lifecycle = mock(PluginLifecycleService.class);
        RecoveryModeService recovery = mock(RecoveryModeService.class);
        List<PluginDescriptor> descriptors = List.of(
                official("download-workbench", "batch", "download", "pixiv"),
                official("gallery", "gallery", "gallery", "green"),
                official("novel", "novel", "book", "amber"),
                official("gui-theme", "gui-theme", "palette", "blue"),
                official("stats", "stats", "chart-line", "green"),
                official("notification", "notification", "bell", "teal"),
                official("push", "push", "bell", "blue"),
                official("mail", "mail", "mail", "green"),
                official("tts", "tts", "audio-lines", "amber"),
                official("ai", "ai", "sparkles", "teal"));
        when(status.report()).thenReturn(new PluginStatusReport(descriptors.stream()
                .map(d -> new PluginDiagnostic(d.id(), PluginStatus.INSTALLED, d, false, List.of()))
                .toList()));
        when(lifecycle.managedPluginIds()).thenReturn(Set.of());

        PluginManagementService.PluginManagementReport report =
                service(status, lifecycle, RequiredPluginPolicy.empty(), recovery).list();

        for (PluginDescriptor descriptor : descriptors) {
            PluginManagementService.PluginManagementEntry entry = entry(report, descriptor.id());
            assertThat(entry.displayNamespace()).isEqualTo(descriptor.displayNamespace());
            assertThat(entry.displayNameKey()).isEqualTo("plugin.name");
            assertThat(entry.descriptionKey()).isEqualTo("plugin.summary");
            assertThat(entry.iconKey()).isEqualTo(descriptor.iconKey());
            assertThat(entry.colorToken()).isEqualTo(descriptor.colorToken());
        }
    }

    @Test
    @DisplayName("list() 暴露描述符的 API 要求与插件依赖：requires 投影 specified/satisfied/required，dependencies 逐项映射")
    void listExposesApiRequirementAndDependencies() {
        PluginStatusService status = mock(PluginStatusService.class);
        PluginLifecycleService lifecycle = mock(PluginLifecycleService.class);
        RecoveryModeService recovery = mock(RecoveryModeService.class);

        // 满足当前核心 API 的 requires + 两条依赖（一必需、一可选不限版本）。
        PluginDescriptor satisfied = new PluginDescriptor(
                EXTERNAL_ID, EXTERNAL_ID, "1.2.0",
                PluginApiRequirement.of(PluginApiVersion.MAJOR, PluginApiVersion.MINOR),
                List.of(new PluginDependencyRef("download-workbench", "1.0", false),
                        new PluginDependencyRef("gallery", "*", true)),
                EXTERNAL_ID + ".Plugin", EXTERNAL_ID, "nav.label", null, "puzzle", "neutral", PluginKind.FEATURE);
        // 高于当前核心 API 的 requires：specified=true 但 satisfied=false。
        PluginDescriptor unsatisfied = new PluginDescriptor(
                REQUIRED_EXTERNAL_ID, REQUIRED_EXTERNAL_ID, "2.0.0",
                PluginApiRequirement.of(PluginApiVersion.MAJOR + 1, 0),
                List.of(), REQUIRED_EXTERNAL_ID + ".Plugin", REQUIRED_EXTERNAL_ID, "nav.label",
                null, "puzzle", "neutral", PluginKind.FEATURE);
        when(status.report()).thenReturn(new PluginStatusReport(List.of(
                new PluginDiagnostic(EXTERNAL_ID, PluginStatus.STARTED, satisfied, false, List.of()),
                new PluginDiagnostic(REQUIRED_EXTERNAL_ID, PluginStatus.INCOMPATIBLE, unsatisfied, false,
                        List.of("requires a newer core API")))));
        when(lifecycle.managedPluginIds()).thenReturn(Set.of(EXTERNAL_ID));

        PluginManagementService.PluginManagementReport report =
                service(status, lifecycle, RequiredPluginPolicy.empty(), recovery).list();

        PluginManagementService.PluginManagementEntry sat = entry(report, EXTERNAL_ID);
        assertThat(sat.apiRequirement().specified()).isTrue();
        assertThat(sat.apiRequirement().satisfied()).isTrue();
        assertThat(sat.apiRequirement().required()).isEqualTo(PluginApiVersion.MAJOR + "." + PluginApiVersion.MINOR);
        assertThat(sat.dependencies()).extracting(
                        PluginManagementService.PluginDependencyView::pluginId,
                        PluginManagementService.PluginDependencyView::versionSupport,
                        PluginManagementService.PluginDependencyView::optional)
                .containsExactly(
                        tuple("download-workbench", "1.0", false),
                        tuple("gallery", "*", true));

        PluginManagementService.PluginManagementEntry unsat = entry(report, REQUIRED_EXTERNAL_ID);
        assertThat(unsat.apiRequirement().specified()).isTrue();
        assertThat(unsat.apiRequirement().satisfied()).isFalse();
        assertThat(unsat.apiRequirement().required()).isEqualTo((PluginApiVersion.MAJOR + 1) + ".0");
        assertThat(unsat.dependencies()).isEmpty();
    }

    @Test
    @DisplayName("list() 隔离损坏 provenance：坏项标记 PROVENANCE_INVALID，好项仍保留有效投影")
    void malformedProvenanceIsIsolatedPerPlugin(@TempDir Path tempDir) throws Exception {
        String malformedId = "malformed-ext";
        String validId = "valid-ext";
        Path pluginsDir = tempDir.resolve("plugins");
        Files.createDirectories(pluginsDir);
        Path malformedArtifact = pluginsDir.resolve("malformed-ext-1.0.0.jar");
        Path validArtifact = pluginsDir.resolve("valid-ext-1.0.0.jar");
        Files.writeString(malformedArtifact, "malformed artifact", StandardCharsets.UTF_8);
        Files.writeString(validArtifact, "valid artifact", StandardCharsets.UTF_8);

        PluginProvenanceStore store = new PluginProvenanceStore(pluginsDir);
        Files.createDirectories(store.sidecarPath(malformedArtifact).getParent());
        Files.writeString(store.sidecarPath(malformedArtifact),
                "formatVersion=broken\n", StandardCharsets.UTF_8);
        store.write(validArtifact, PluginPackageOrigin.localUpload(), new VerificationResult(
                VerificationStatus.UNSIGNED_ALLOWED,
                validId,
                "1.0.0",
                null,
                null,
                null,
                null,
                Instant.parse("2026-07-22T00:00:00Z"),
                Files.size(validArtifact),
                PluginPackageIntegrity.sha256Hex(validArtifact),
                "UNSIGNED_ALLOWED"));

        PluginStatusService status = mock(PluginStatusService.class);
        PluginLifecycleService lifecycle = mock(PluginLifecycleService.class);
        RecoveryModeService recovery = mock(RecoveryModeService.class);
        when(status.report()).thenReturn(new PluginStatusReport(List.of(
                new PluginDiagnostic(malformedId, PluginStatus.STARTED,
                        descriptor(malformedId, PluginKind.FEATURE), false, List.of()),
                new PluginDiagnostic(validId, PluginStatus.STARTED,
                        descriptor(validId, PluginKind.FEATURE), false, List.of()))));
        when(status.recoveryGateSnapshot()).thenReturn(safeRecoveryGate());
        when(lifecycle.managedPluginIds()).thenReturn(Set.of(malformedId, validId));
        when(lifecycle.phase(malformedId)).thenReturn(Optional.of(PluginRuntimePhase.STARTED));
        when(lifecycle.phase(validId)).thenReturn(Optional.of(PluginRuntimePhase.STARTED));
        when(lifecycle.artifactPath(malformedId)).thenReturn(Optional.of(malformedArtifact));
        when(lifecycle.artifactPath(validId)).thenReturn(Optional.of(validArtifact));

        PluginProvenanceRecord validProvenance = store.read(validArtifact).orElseThrow();
        ExternalPluginInstaller installer = mock(ExternalPluginInstaller.class);
        when(installer.snapshotInstalledWithProvenance(512, 64L * 1024L * 1024L)).thenReturn(
                new InstalledPluginInventorySnapshot(List.of(
                        new InstalledPluginSnapshot(
                                new InstalledPlugin(descriptor(malformedId, PluginKind.FEATURE), malformedArtifact),
                                Files.size(malformedArtifact), PluginPackageIntegrity.sha256Hex(malformedArtifact),
                                ProvenanceSnapshotState.INVALID, null, 0L),
                        new InstalledPluginSnapshot(
                                new InstalledPlugin(descriptor(validId, PluginKind.FEATURE), validArtifact),
                                Files.size(validArtifact), PluginPackageIntegrity.sha256Hex(validArtifact),
                                ProvenanceSnapshotState.PRESENT,
                                validProvenance, Files.size(store.sidecarPath(validArtifact)))), false));
        ExternalPluginLifecycleCoordinator coordinator = mock(ExternalPluginLifecycleCoordinator.class);
        when(coordinator.lifecycleMutationEpoch()).thenReturn(0L);
        PluginManagementService.PluginManagementReport report = new PluginManagementService(
                status,
                lifecycle,
                RequiredPluginPolicy.empty(),
                recovery,
                coordinator,
                installer,
                new PluginToggleProperties())
                .list();

        assertThat(entry(report, malformedId).verification().status())
                .isEqualTo(PluginVerificationProjector.PROVENANCE_INVALID);
        assertThat(entry(report, validId).verification().status())
                .isEqualTo(PluginVerificationProjector.UNSIGNED_ALLOWED);
        assertThat(report.plugins()).extracting(PluginManagementService.PluginManagementEntry::id)
                .containsExactly(malformedId, validId);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("活动生产插件未出现在安装快照时来源证明 fail-closed")
    void activeProductionArtifactMissingFromInstalledSnapshotFailsClosed(boolean budgetExhausted) {
        PluginDescriptor descriptor = descriptor(EXTERNAL_ID, PluginKind.FEATURE);
        Path artifact = Path.of("plugins", "demo-ext-1.0.0.jar").toAbsolutePath().normalize();
        PluginStatusService status = mock(PluginStatusService.class);
        PluginLifecycleService lifecycle = mock(PluginLifecycleService.class);
        RecoveryModeService recovery = mock(RecoveryModeService.class);
        ExternalPluginLifecycleCoordinator coordinator = mock(ExternalPluginLifecycleCoordinator.class);
        ExternalPluginInstaller installer = mock(ExternalPluginInstaller.class);
        when(status.recoveryGateSnapshot()).thenReturn(safeRecoveryGate());
        when(status.report()).thenReturn(new PluginStatusReport(List.of(
                new PluginDiagnostic(EXTERNAL_ID, PluginStatus.STARTED, descriptor, false, List.of()))));
        when(lifecycle.managedPluginIds()).thenReturn(Set.of(EXTERNAL_ID));
        when(lifecycle.phase(EXTERNAL_ID)).thenReturn(Optional.of(PluginRuntimePhase.STARTED));
        when(lifecycle.artifactPath(EXTERNAL_ID)).thenReturn(Optional.of(artifact));
        when(lifecycle.isDevelopmentArtifact(EXTERNAL_ID)).thenReturn(false);
        when(coordinator.lifecycleMutationEpoch()).thenReturn(0L);
        when(installer.snapshotInstalledWithProvenance(512, 64L * 1024L * 1024L)).thenReturn(
                new InstalledPluginInventorySnapshot(List.of(), budgetExhausted));

        PluginManagementService.PluginManagementReport report = new PluginManagementService(
                status, lifecycle, RequiredPluginPolicy.empty(), recovery,
                coordinator, installer, new PluginToggleProperties()).list();

        assertThat(entry(report, EXTERNAL_ID).verification().status())
                .isEqualTo(PluginVerificationProjector.PROVENANCE_INVALID);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("显式开发 generation 无论安装目录是否残留同 id 包都保持本地未验证投影")
    void developmentArtifactOutsideInstalledSnapshotRemainsUnverifiedLocal(boolean staleInstalledArtifact) {
        PluginDescriptor descriptor = descriptor(EXTERNAL_ID, PluginKind.FEATURE);
        Path classes = Path.of("pixivdownload-plugin-demo-ext", "target", "classes")
                .toAbsolutePath().normalize();
        Path staleArtifact = Path.of("plugins", "demo-ext-0.9.0.jar").toAbsolutePath().normalize();
        PluginStatusService status = mock(PluginStatusService.class);
        PluginLifecycleService lifecycle = mock(PluginLifecycleService.class);
        RecoveryModeService recovery = mock(RecoveryModeService.class);
        ExternalPluginLifecycleCoordinator coordinator = mock(ExternalPluginLifecycleCoordinator.class);
        ExternalPluginInstaller installer = mock(ExternalPluginInstaller.class);
        when(status.recoveryGateSnapshot()).thenReturn(safeRecoveryGate());
        when(status.report()).thenReturn(new PluginStatusReport(List.of(
                new PluginDiagnostic(EXTERNAL_ID, PluginStatus.STARTED, descriptor, false, List.of()))));
        when(lifecycle.managedPluginIds()).thenReturn(Set.of(EXTERNAL_ID));
        when(lifecycle.phase(EXTERNAL_ID)).thenReturn(Optional.of(PluginRuntimePhase.STARTED));
        when(lifecycle.artifactPath(EXTERNAL_ID)).thenReturn(Optional.of(classes));
        when(lifecycle.isDevelopmentArtifact(EXTERNAL_ID)).thenReturn(true);
        when(coordinator.lifecycleMutationEpoch()).thenReturn(0L);
        when(installer.snapshotInstalledWithProvenance(512, 64L * 1024L * 1024L)).thenReturn(
                new InstalledPluginInventorySnapshot(staleInstalledArtifact
                        ? List.of(new InstalledPluginSnapshot(
                                new InstalledPlugin(descriptor, staleArtifact),
                                1L, "a".repeat(64),
                                ProvenanceSnapshotState.ABSENT,
                                null, 0L))
                        : List.of(), false));

        PluginManagementService.PluginManagementReport report = new PluginManagementService(
                status, lifecycle, RequiredPluginPolicy.empty(), recovery,
                coordinator, installer, new PluginToggleProperties()).list();

        assertThat(entry(report, EXTERNAL_ID).verification().status())
                .isEqualTo(PluginVerificationProjector.UNVERIFIED_LOCAL);
    }

    @Test
    @DisplayName("安装快照的 provenance 读取预算耗尽时失效关闭")
    void provenanceBudgetExhaustionFailsClosed() {
        PluginDescriptor descriptor = descriptor(EXTERNAL_ID, PluginKind.FEATURE);
        Path artifact = Path.of("plugins", "demo-ext-1.0.0.jar").toAbsolutePath().normalize();
        PluginStatusService status = mock(PluginStatusService.class);
        PluginLifecycleService lifecycle = mock(PluginLifecycleService.class);
        RecoveryModeService recovery = mock(RecoveryModeService.class);
        ExternalPluginLifecycleCoordinator coordinator = mock(ExternalPluginLifecycleCoordinator.class);
        ExternalPluginInstaller installer = mock(ExternalPluginInstaller.class);
        when(status.recoveryGateSnapshot()).thenReturn(safeRecoveryGate());
        when(status.report()).thenReturn(new PluginStatusReport(List.of(
                new PluginDiagnostic(EXTERNAL_ID, PluginStatus.STARTED, descriptor, false, List.of()))));
        when(lifecycle.managedPluginIds()).thenReturn(Set.of(EXTERNAL_ID));
        when(lifecycle.phase(EXTERNAL_ID)).thenReturn(Optional.of(PluginRuntimePhase.STARTED));
        when(lifecycle.artifactPath(EXTERNAL_ID)).thenReturn(Optional.of(artifact));
        when(lifecycle.isDevelopmentArtifact(EXTERNAL_ID)).thenReturn(false);
        when(coordinator.lifecycleMutationEpoch()).thenReturn(0L);
        when(installer.snapshotInstalledWithProvenance(512, 64L * 1024L * 1024L)).thenReturn(
                new InstalledPluginInventorySnapshot(List.of(
                        new InstalledPluginSnapshot(
                                new InstalledPlugin(descriptor, artifact),
                                1L, "a".repeat(64),
                                ProvenanceSnapshotState.BUDGET_EXHAUSTED,
                                null, 0L)), true));

        PluginManagementService.PluginManagementReport report = new PluginManagementService(
                status, lifecycle, RequiredPluginPolicy.empty(), recovery,
                coordinator, installer, new PluginToggleProperties()).list();

        assertThat(entry(report, EXTERNAL_ID).verification().status())
                .isEqualTo(PluginVerificationProjector.PROVENANCE_INVALID);
    }

    @Test
    @DisplayName("本次启动复验精确绑定当前字节时优先于写回失败遗留的旧 provenance")
    void currentRuntimeVerificationOverridesStalePersistedBinding() {
        String pluginId = "changed-current-ext";
        PluginDescriptor descriptor = descriptor(pluginId, PluginKind.FEATURE);
        Path artifact = Path.of("plugins", pluginId + "-1.0.0.jar").toAbsolutePath().normalize();
        PluginProvenanceRecord oldBinding = new PluginProvenanceRecord(
                top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageSource.LOCAL_UPLOAD,
                null, false, null, null, 1L, "a".repeat(64), null,
                VerificationStatus.UNSIGNED_ALLOWED, null, null, null,
                Instant.parse("2026-07-22T00:00:00Z"), null, null, "UNSIGNED_ALLOWED");
        VerificationResult currentResult = new VerificationResult(
                VerificationStatus.HASH_MISMATCH,
                pluginId,
                descriptor.version(),
                null,
                null,
                null,
                null,
                Instant.parse("2026-07-22T00:01:00Z"),
                2L,
                "b".repeat(64),
                "SHA256_MISMATCH");
        PluginRuntimeVerificationSnapshot runtimeVerification = new PluginRuntimeVerificationSnapshot(
                artifact, pluginId, descriptor.version(), currentResult.sizeBytes(), currentResult.sha256(),
                oldBinding, currentResult);
        PluginStatusService status = mock(PluginStatusService.class);
        PluginLifecycleService lifecycle = mock(PluginLifecycleService.class);
        RecoveryModeService recovery = mock(RecoveryModeService.class);
        ExternalPluginLifecycleCoordinator coordinator = mock(ExternalPluginLifecycleCoordinator.class);
        ExternalPluginInstaller installer = mock(ExternalPluginInstaller.class);
        when(status.recoveryGateSnapshot()).thenReturn(safeRecoveryGate());
        when(status.report()).thenReturn(new PluginStatusReport(List.of(
                new PluginDiagnostic(pluginId, PluginStatus.INSTALLED, descriptor, false, List.of()))));
        when(status.runtimeVerificationSnapshots()).thenReturn(List.of(runtimeVerification));
        when(lifecycle.managedPluginIds()).thenReturn(Set.of());
        when(lifecycle.phase(pluginId)).thenReturn(Optional.empty());
        when(lifecycle.artifactPath(pluginId)).thenReturn(Optional.empty());
        when(coordinator.lifecycleMutationEpoch()).thenReturn(0L);
        when(installer.snapshotInstalledWithProvenance(512, 64L * 1024L * 1024L)).thenReturn(
                new InstalledPluginInventorySnapshot(List.of(
                        new InstalledPluginSnapshot(
                                new InstalledPlugin(descriptor, artifact),
                                2L, "b".repeat(64),
                                ProvenanceSnapshotState.PRESENT,
                                oldBinding, 128L)), false));

        PluginManagementService.PluginManagementReport report = new PluginManagementService(
                status, lifecycle, RequiredPluginPolicy.empty(), recovery,
                coordinator, installer, new PluginToggleProperties()).list();

        assertThat(entry(report, pluginId).verification().status())
                .isEqualTo(PluginVerificationProjector.HASH_MISMATCH);
        assertThat(entry(report, pluginId).verification().diagnosticCode())
                .isEqualTo("SHA256_MISMATCH");
    }

    @Test
    @DisplayName("同字节 sidecar 信任语义变化或损坏时不得复用旧 runtime 绿灯")
    void currentProvenanceStateGatesExactRuntimeVerification() {
        String changedId = "changed-trust-ext";
        String invalidId = "invalid-trust-ext";
        PluginDescriptor changedDescriptor = descriptor(changedId, PluginKind.FEATURE);
        PluginDescriptor invalidDescriptor = descriptor(invalidId, PluginKind.FEATURE);
        Path changedArtifact = Path.of("plugins", changedId + "-1.0.0.jar").toAbsolutePath().normalize();
        Path invalidArtifact = Path.of("plugins", invalidId + "-1.0.0.jar").toAbsolutePath().normalize();
        long size = 7L;
        String sha256 = "c".repeat(64);
        SignatureMetadata signature = new SignatureMetadata(
                SignatureMetadata.FORMAT_VERSION, SignatureMetadata.ED25519, "old-key", "c2ln");
        PluginProvenanceRecord oldCatalogBinding = new PluginProvenanceRecord(
                PluginPackageSource.MARKET_CATALOG,
                "old-repository",
                false,
                size,
                sha256,
                size,
                sha256,
                signature,
                VerificationStatus.VERIFIED,
                signature.keyId(),
                "Old Publisher",
                "Old Trust",
                Instant.parse("2026-07-22T00:00:00Z"),
                null,
                null,
                "VERIFIED");
        PluginProvenanceRecord forgedCatalogBinding = new PluginProvenanceRecord(
                PluginPackageSource.MARKET_CATALOG,
                "forged-repository",
                true,
                size,
                sha256,
                size,
                sha256,
                signature,
                VerificationStatus.VERIFIED,
                signature.keyId(),
                "Forged Publisher",
                "Forged Trust",
                Instant.parse("2026-07-22T00:02:00Z"),
                null,
                null,
                "VERIFIED");
        VerificationResult oldVerifiedResult = new VerificationResult(
                VerificationStatus.VERIFIED,
                changedId,
                changedDescriptor.version(),
                signature.keyId(),
                "Old Publisher",
                "Old Trust",
                null,
                Instant.parse("2026-07-22T00:01:00Z"),
                size,
                sha256,
                "VERIFIED");
        VerificationResult invalidVerifiedResult = new VerificationResult(
                VerificationStatus.VERIFIED,
                invalidId,
                invalidDescriptor.version(),
                signature.keyId(),
                "Old Publisher",
                "Old Trust",
                null,
                Instant.parse("2026-07-22T00:01:00Z"),
                size,
                sha256,
                "VERIFIED");
        PluginStatusService status = mock(PluginStatusService.class);
        PluginLifecycleService lifecycle = mock(PluginLifecycleService.class);
        RecoveryModeService recovery = mock(RecoveryModeService.class);
        ExternalPluginLifecycleCoordinator coordinator = mock(ExternalPluginLifecycleCoordinator.class);
        ExternalPluginInstaller installer = mock(ExternalPluginInstaller.class);
        when(status.recoveryGateSnapshot()).thenReturn(safeRecoveryGate());
        when(status.report()).thenReturn(new PluginStatusReport(List.of(
                new PluginDiagnostic(changedId, PluginStatus.INSTALLED,
                        changedDescriptor, false, List.of()),
                new PluginDiagnostic(invalidId, PluginStatus.INSTALLED,
                        invalidDescriptor, false, List.of()))));
        when(status.runtimeVerificationSnapshots()).thenReturn(List.of(
                new PluginRuntimeVerificationSnapshot(
                        changedArtifact, changedId, changedDescriptor.version(), size, sha256,
                        oldCatalogBinding, oldVerifiedResult),
                new PluginRuntimeVerificationSnapshot(
                        invalidArtifact, invalidId, invalidDescriptor.version(), size, sha256,
                        oldCatalogBinding, invalidVerifiedResult)));
        when(lifecycle.managedPluginIds()).thenReturn(Set.of());
        when(lifecycle.phase(changedId)).thenReturn(Optional.empty());
        when(lifecycle.phase(invalidId)).thenReturn(Optional.empty());
        when(lifecycle.artifactPath(changedId)).thenReturn(Optional.empty());
        when(lifecycle.artifactPath(invalidId)).thenReturn(Optional.empty());
        when(coordinator.lifecycleMutationEpoch()).thenReturn(0L);
        when(installer.snapshotInstalledWithProvenance(512, 64L * 1024L * 1024L)).thenReturn(
                new InstalledPluginInventorySnapshot(List.of(
                        new InstalledPluginSnapshot(
                                new InstalledPlugin(changedDescriptor, changedArtifact),
                                size, sha256,
                                ProvenanceSnapshotState.PRESENT,
                                forgedCatalogBinding, 128L),
                        new InstalledPluginSnapshot(
                                new InstalledPlugin(invalidDescriptor, invalidArtifact),
                                size, sha256,
                                ProvenanceSnapshotState.INVALID,
                                null, 0L)), false));

        PluginManagementService.PluginManagementReport report = new PluginManagementService(
                status, lifecycle, RequiredPluginPolicy.empty(), recovery,
                coordinator, installer, new PluginToggleProperties()).list();

        assertThat(entry(report, changedId).verification().status())
                .isEqualTo(PluginVerificationProjector.PROVENANCE_INVALID);
        assertThat(entry(report, invalidId).verification().status())
                .isEqualTo(PluginVerificationProjector.PROVENANCE_INVALID);
    }

    @Test
    @DisplayName("同路径同版本 ABA 发生时丢弃旧 provenance 快照")
    void lifecycleMutationEpochRejectsSamePathSameVersionAba() {
        PluginDescriptor descriptor = descriptor(EXTERNAL_ID, PluginKind.FEATURE);
        Path artifact = Path.of("plugins", "demo-ext-1.0.0.jar").toAbsolutePath().normalize();
        PluginProvenanceRecord provenance = new PluginProvenanceRecord(
                top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageSource.LOCAL_UPLOAD,
                null, false, null, null, 1L, "a".repeat(64), null,
                VerificationStatus.UNSIGNED_ALLOWED, null, null, null,
                Instant.parse("2026-07-22T00:00:00Z"), null, null, "UNSIGNED_ALLOWED");
        PluginStatusService status = mock(PluginStatusService.class);
        PluginLifecycleService lifecycle = mock(PluginLifecycleService.class);
        RecoveryModeService recovery = mock(RecoveryModeService.class);
        ExternalPluginLifecycleCoordinator coordinator = mock(ExternalPluginLifecycleCoordinator.class);
        ExternalPluginInstaller installer = mock(ExternalPluginInstaller.class);
        when(status.recoveryGateSnapshot()).thenReturn(safeRecoveryGate());
        when(status.report()).thenReturn(new PluginStatusReport(List.of(
                new PluginDiagnostic(EXTERNAL_ID, PluginStatus.STARTED, descriptor, false, List.of()))));
        when(lifecycle.managedPluginIds()).thenReturn(Set.of(EXTERNAL_ID));
        when(lifecycle.phase(EXTERNAL_ID)).thenReturn(Optional.of(PluginRuntimePhase.STARTED));
        when(lifecycle.artifactPath(EXTERNAL_ID)).thenReturn(Optional.of(artifact));
        when(coordinator.lifecycleMutationEpoch()).thenReturn(0L, 2L, 3L);
        when(installer.snapshotInstalledWithProvenance(512, 64L * 1024L * 1024L)).thenReturn(
                new InstalledPluginInventorySnapshot(List.of(
                        new InstalledPluginSnapshot(
                                new InstalledPlugin(descriptor, artifact),
                                1L, "a".repeat(64),
                                ProvenanceSnapshotState.PRESENT,
                                provenance, 128L)), false));

        PluginManagementService.PluginManagementReport report = new PluginManagementService(
                status, lifecycle, RequiredPluginPolicy.empty(), recovery,
                coordinator, installer, new PluginToggleProperties()).list();

        assertThat(report.plugins()).isEmpty();
        assertThat(report.recoveryMode()).isFalse();
        assertThat(report.transactionRecovery().state()).isEqualTo("UNCHECKED");
        assertThat(report.transactionRecovery().safeToScan()).isFalse();
        verify(installer, times(1)).snapshotInstalledWithProvenance(512, 64L * 1024L * 1024L);
        verify(lifecycle, times(1)).phase(EXTERNAL_ID);
        verify(lifecycle, times(1)).generation(EXTERNAL_ID);
        verify(coordinator, times(1)).operation(EXTERNAL_ID);
    }

    @Test
    @DisplayName("管理读取中恢复门转为 BLOCKED 时丢弃异常快照并按新门重建")
    void recoveryGateTransitionDuringStatusReadRetriesWithoutServerError() {
        PluginRecoveryGateSnapshot safe = safeRecoveryGate();
        PluginRecoveryGateSnapshot blocked = blockedRecoveryGate();
        PluginStatusService status = mock(PluginStatusService.class);
        PluginLifecycleService lifecycle = mock(PluginLifecycleService.class);
        RecoveryModeService recovery = mock(RecoveryModeService.class);
        ExternalPluginLifecycleCoordinator coordinator = mock(ExternalPluginLifecycleCoordinator.class);
        ExternalPluginInstaller installer = mock(ExternalPluginInstaller.class);
        when(status.recoveryGateSnapshot()).thenReturn(safe, blocked);
        when(status.report())
                .thenThrow(new IllegalStateException("runtime scan blocked during status read"))
                .thenReturn(new PluginStatusReport(List.of(new PluginDiagnostic(
                        "plugin-runtime", PluginStatus.FAILED, null, true,
                        List.of("transaction recovery failed")))));
        when(coordinator.lifecycleMutationEpoch()).thenReturn(0L);
        when(recovery.isActive()).thenReturn(true);

        PluginManagementService.PluginManagementReport report = new PluginManagementService(
                status, lifecycle, RequiredPluginPolicy.empty(), recovery,
                coordinator, installer, new PluginToggleProperties()).list();

        assertThat(report.recoveryMode()).isTrue();
        assertThat(report.transactionRecovery().state()).isEqualTo("BLOCKED");
        assertThat(report.transactionRecovery().safeToScan()).isFalse();
        assertThat(report.plugins()).isEmpty();
        verify(status, times(2)).report();
        verify(installer, never()).snapshotInstalledWithProvenance(512, 64L * 1024L * 1024L);
    }

    @Test
    @DisplayName("生命周期 epoch 持续为奇数时直接返回无运行期读取的保守快照")
    void oddLifecycleMutationEpochSkipsAllMutableReads() {
        PluginDescriptor descriptor = descriptor(EXTERNAL_ID, PluginKind.FEATURE);
        PluginStatusService status = mock(PluginStatusService.class);
        PluginLifecycleService lifecycle = mock(PluginLifecycleService.class);
        RecoveryModeService recovery = mock(RecoveryModeService.class);
        ExternalPluginLifecycleCoordinator coordinator = mock(ExternalPluginLifecycleCoordinator.class);
        ExternalPluginInstaller installer = mock(ExternalPluginInstaller.class);
        when(status.recoveryGateSnapshot()).thenReturn(safeRecoveryGate());
        when(status.report()).thenReturn(new PluginStatusReport(List.of(
                new PluginDiagnostic(EXTERNAL_ID, PluginStatus.STARTED, descriptor, false, List.of()))));
        when(coordinator.lifecycleMutationEpoch()).thenReturn(1L);

        PluginManagementService.PluginManagementReport report = new PluginManagementService(
                status, lifecycle, RequiredPluginPolicy.empty(), recovery,
                coordinator, installer, new PluginToggleProperties()).list();

        assertThat(report.transactionRecovery().state()).isEqualTo("UNCHECKED");
        assertThat(report.transactionRecovery().safeToScan()).isFalse();
        assertThat(report.plugins()).isEmpty();
        verify(status, never()).report();
        verify(installer, never()).snapshotInstalledWithProvenance(512, 64L * 1024L * 1024L);
        verify(lifecycle, never()).managedPluginIds();
        verify(lifecycle, never()).phase(EXTERNAL_ID);
        verify(lifecycle, never()).generation(EXTERNAL_ID);
        verify(coordinator, never()).operation(EXTERNAL_ID);
    }

    @Test
    @DisplayName("来源证明必须绑定当前 artifact 字节且活动代必须有运行时路径")
    void provenanceRequiresCurrentBytesAndActiveRuntimePath() {
        String changedId = "changed-bytes-ext";
        String missingPathId = "missing-runtime-path-ext";
        PluginDescriptor changedDescriptor = descriptor(changedId, PluginKind.FEATURE);
        PluginDescriptor missingPathDescriptor = descriptor(missingPathId, PluginKind.FEATURE);
        Path changedPath = Path.of("plugins", changedId + "-1.0.0.jar").toAbsolutePath().normalize();
        Path missingPath = Path.of("plugins", missingPathId + "-1.0.0.jar").toAbsolutePath().normalize();
        PluginProvenanceRecord oldBinding = new PluginProvenanceRecord(
                top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageSource.LOCAL_UPLOAD,
                null, false, null, null, 1L, "a".repeat(64), null,
                VerificationStatus.UNSIGNED_ALLOWED, null, null, null,
                Instant.parse("2026-07-22T00:00:00Z"), null, null, "UNSIGNED_ALLOWED");
        VerificationResult staleResult = new VerificationResult(
                VerificationStatus.HASH_MISMATCH,
                changedId,
                changedDescriptor.version(),
                null,
                null,
                null,
                null,
                Instant.parse("2026-07-22T00:01:00Z"),
                1L,
                "c".repeat(64),
                "SHA256_MISMATCH");
        PluginRuntimeVerificationSnapshot staleRuntimeVerification =
                new PluginRuntimeVerificationSnapshot(
                        changedPath, changedId, changedDescriptor.version(),
                        staleResult.sizeBytes(), staleResult.sha256(), oldBinding, staleResult);
        PluginStatusService status = mock(PluginStatusService.class);
        PluginLifecycleService lifecycle = mock(PluginLifecycleService.class);
        RecoveryModeService recovery = mock(RecoveryModeService.class);
        ExternalPluginLifecycleCoordinator coordinator = mock(ExternalPluginLifecycleCoordinator.class);
        ExternalPluginInstaller installer = mock(ExternalPluginInstaller.class);
        when(status.recoveryGateSnapshot()).thenReturn(safeRecoveryGate());
        when(status.report()).thenReturn(new PluginStatusReport(List.of(
                new PluginDiagnostic(changedId, PluginStatus.STARTED, changedDescriptor, false, List.of()),
                new PluginDiagnostic(missingPathId, PluginStatus.STARTED,
                        missingPathDescriptor, false, List.of()))));
        when(status.runtimeVerificationSnapshots()).thenReturn(List.of(staleRuntimeVerification));
        when(lifecycle.managedPluginIds()).thenReturn(Set.of(changedId, missingPathId));
        when(lifecycle.phase(changedId)).thenReturn(Optional.of(PluginRuntimePhase.STARTED));
        when(lifecycle.phase(missingPathId)).thenReturn(Optional.of(PluginRuntimePhase.STARTED));
        when(lifecycle.artifactPath(changedId)).thenReturn(Optional.of(changedPath));
        when(lifecycle.artifactPath(missingPathId)).thenReturn(Optional.empty());
        when(coordinator.lifecycleMutationEpoch()).thenReturn(0L);
        when(installer.snapshotInstalledWithProvenance(512, 64L * 1024L * 1024L)).thenReturn(
                new InstalledPluginInventorySnapshot(List.of(
                        new InstalledPluginSnapshot(
                                new InstalledPlugin(changedDescriptor, changedPath),
                                1L, "b".repeat(64),
                                ProvenanceSnapshotState.PRESENT,
                                oldBinding, 128L),
                        new InstalledPluginSnapshot(
                                new InstalledPlugin(missingPathDescriptor, missingPath),
                                1L, "a".repeat(64),
                                ProvenanceSnapshotState.PRESENT,
                                oldBinding, 128L)), false));

        PluginManagementService.PluginManagementReport report = new PluginManagementService(
                status, lifecycle, RequiredPluginPolicy.empty(), recovery,
                coordinator, installer, new PluginToggleProperties()).list();

        assertThat(entry(report, changedId).verification().status())
                .isEqualTo(PluginVerificationProjector.PROVENANCE_INVALID);
        assertThat(entry(report, missingPathId).verification().status())
                .isEqualTo(PluginVerificationProjector.PROVENANCE_INVALID);
    }

    @Test
    @DisplayName("必选外置插件 STARTED：给出 restart/reload，不给停用类（quiesce/stop/unload）也不给 start/load")
    void requiredExternalOffersOnlyRestoreActions() {
        PluginStatusService status = mock(PluginStatusService.class);
        PluginLifecycleService lifecycle = mock(PluginLifecycleService.class);
        RecoveryModeService recovery = mock(RecoveryModeService.class);
        when(status.report()).thenReturn(new PluginStatusReport(List.of(
                new PluginDiagnostic(REQUIRED_EXTERNAL_ID, PluginStatus.STARTED,
                        descriptor(REQUIRED_EXTERNAL_ID, PluginKind.FEATURE), true, List.of()))));
        when(lifecycle.managedPluginIds()).thenReturn(Set.of(REQUIRED_EXTERNAL_ID));
        when(lifecycle.phase(REQUIRED_EXTERNAL_ID)).thenReturn(Optional.of(PluginRuntimePhase.STARTED));

        PluginManagementService.PluginManagementReport report =
                service(status, lifecycle, requiredPolicy(), recovery).list();

        PluginManagementService.PluginManagementEntry entry = entry(report, REQUIRED_EXTERNAL_ID);
        assertThat(entry.allowDisable()).isFalse();
        assertThat(entry.toggleable()).isFalse();
        assertThat(entry.availableActions()).containsExactly("restart", "reload");
    }

    @Test
    @DisplayName("非热重载策略只暴露期望启用态：不受运行期管理且不提供热管理动词")
    void restartPolicyIsNotHotManaged() {
        PluginStatusService status = mock(PluginStatusService.class);
        PluginLifecycleService lifecycle = mock(PluginLifecycleService.class);
        RecoveryModeService recovery = mock(RecoveryModeService.class);
        PluginToggleProperties toggles = new PluginToggleProperties();
        toggles.setEnabled(EXTERNAL_ID, false);
        PluginDescriptor descriptor = new PluginDescriptor(
                EXTERNAL_ID, EXTERNAL_ID, "1.0.0", PluginApiRequirement.unspecified(), List.of(),
                EXTERNAL_ID + ".Plugin", EXTERNAL_ID, "nav.label", null,
                "puzzle", "neutral", PluginKind.FEATURE, List.of(), PluginLifecyclePolicy.PROCESS_RESTART);
        when(status.report()).thenReturn(new PluginStatusReport(List.of(
                new PluginDiagnostic(EXTERNAL_ID, PluginStatus.STARTED, descriptor, false, List.of()))));
        when(lifecycle.managedPluginIds()).thenReturn(Set.of(EXTERNAL_ID));
        when(lifecycle.phase(EXTERNAL_ID)).thenReturn(Optional.of(PluginRuntimePhase.STARTED));

        PluginManagementService.PluginManagementEntry entry = entry(
                service(status, lifecycle, RequiredPluginPolicy.empty(), recovery, toggles).list(),
                EXTERNAL_ID);

        assertThat(entry.lifecyclePolicy()).isEqualTo(PluginLifecyclePolicy.PROCESS_RESTART);
        assertThat(entry.configuredEnabled()).isFalse();
        assertThat(entry.toggleable()).isTrue();
        assertThat(entry.managed()).isFalse();
        assertThat(entry.availableActions()).isEmpty();
    }

    @Test
    @DisplayName("perform 停用类动词委托 PluginLifecycleService：stop 受管外置插件调用 stop() 并返回执行后阶段")
    void performStopDelegates() {
        PluginStatusService status = mock(PluginStatusService.class);
        PluginLifecycleService lifecycle = mock(PluginLifecycleService.class);
        when(lifecycle.managedPluginIds()).thenReturn(Set.of(EXTERNAL_ID));
        when(lifecycle.phase(EXTERNAL_ID)).thenReturn(Optional.of(PluginRuntimePhase.STOPPED));

        PluginManagementService.PluginActionResult result =
                service(status, lifecycle, RequiredPluginPolicy.empty(), mock(RecoveryModeService.class))
                        .perform(EXTERNAL_ID, PluginManagementService.LifecycleAction.STOP);

        verify(lifecycle).stop(EXTERNAL_ID);
        assertThat(result.id()).isEqualTo(EXTERNAL_ID);
        assertThat(result.action()).isEqualTo("stop");
        assertThat(result.phase()).isEqualTo(PluginRuntimePhase.STOPPED);
    }

    @Test
    @DisplayName("perform 对必选插件的停用类动词拒绝（409 required）且绝不委托 PluginLifecycleService")
    void performRefusesDisablingRequired() {
        PluginStatusService status = mock(PluginStatusService.class);
        PluginLifecycleService lifecycle = mock(PluginLifecycleService.class);
        when(lifecycle.managedPluginIds()).thenReturn(Set.of(REQUIRED_EXTERNAL_ID));

        assertManagementError(() -> service(status, lifecycle, requiredPolicy(), mock(RecoveryModeService.class))
                        .perform(REQUIRED_EXTERNAL_ID, PluginManagementService.LifecycleAction.STOP),
                PluginManagementErrorCode.REQUIRED_PLUGIN);
        verify(lifecycle, never()).stop(REQUIRED_EXTERNAL_ID);
    }

    @Test
    @DisplayName("perform 对必选插件的非停用类动词（reload）放行委托")
    void performAllowsRestoreOnRequired() {
        PluginStatusService status = mock(PluginStatusService.class);
        PluginLifecycleService lifecycle = mock(PluginLifecycleService.class);
        when(lifecycle.managedPluginIds()).thenReturn(Set.of(REQUIRED_EXTERNAL_ID));
        when(lifecycle.phase(REQUIRED_EXTERNAL_ID)).thenReturn(Optional.of(PluginRuntimePhase.STARTED));

        service(status, lifecycle, requiredPolicy(), mock(RecoveryModeService.class))
                .perform(REQUIRED_EXTERNAL_ID, PluginManagementService.LifecycleAction.RELOAD);

        verify(lifecycle).reload(REQUIRED_EXTERNAL_ID);
    }

    @Test
    @DisplayName("perform 启用类动词遇必需依赖缺失时拒绝且不委托生命周期")
    void performRefusesStartWhenRequiredDependencyMissing() {
        PluginStatusService status = mock(PluginStatusService.class);
        PluginLifecycleService lifecycle = mock(PluginLifecycleService.class);
        PluginDescriptor target = new PluginDescriptor(
                EXTERNAL_ID, EXTERNAL_ID, "1.0.0", PluginApiRequirement.unspecified(),
                List.of(new PluginDependencyRef("dep-ext", "1.0", false)),
                EXTERNAL_ID + ".Plugin", EXTERNAL_ID, "nav.label", null,
                "puzzle", "neutral", PluginKind.FEATURE);
        when(lifecycle.managedPluginIds()).thenReturn(Set.of(EXTERNAL_ID));
        when(lifecycle.phase(EXTERNAL_ID)).thenReturn(Optional.of(PluginRuntimePhase.STOPPED));
        when(status.report()).thenReturn(new PluginStatusReport(List.of(
                new PluginDiagnostic(EXTERNAL_ID, PluginStatus.STARTED, target, false, List.of()))));

        assertManagementError(() -> service(status, lifecycle, RequiredPluginPolicy.empty(),
                        mock(RecoveryModeService.class))
                        .perform(EXTERNAL_ID, PluginManagementService.LifecycleAction.START),
                PluginManagementErrorCode.DEPENDENCY_UNSATISFIED);
        verify(lifecycle, never()).start(EXTERNAL_ID);
    }

    @Test
    @DisplayName("perform 对内置插件拒绝（409 built-in）：内置插件不可运行期热启停")
    void performRefusesBuiltIn() {
        PluginStatusService status = mock(PluginStatusService.class);
        PluginLifecycleService lifecycle = mock(PluginLifecycleService.class);
        when(lifecycle.managedPluginIds()).thenReturn(Set.of());

        assertManagementError(() -> service(status, lifecycle, RequiredPluginPolicy.empty(),
                        mock(RecoveryModeService.class))
                        .perform(BUILT_IN_ID, PluginManagementService.LifecycleAction.STOP),
                PluginManagementErrorCode.BUILT_IN_PLUGIN);
    }

    @Test
    @DisplayName("perform 按描述符策略拒绝非热重载插件，不按插件 id 特判")
    void performRefusesRestartPolicyPlugin() {
        PluginStatusService status = mock(PluginStatusService.class);
        PluginLifecycleService lifecycle = mock(PluginLifecycleService.class);
        PluginDescriptor descriptor = new PluginDescriptor(
                EXTERNAL_ID, EXTERNAL_ID, "1.0.0", PluginApiRequirement.unspecified(), List.of(),
                EXTERNAL_ID + ".Plugin", EXTERNAL_ID, "nav.label", null,
                "puzzle", "neutral", PluginKind.FEATURE, List.of(), PluginLifecyclePolicy.BACKEND_RESTART);
        when(status.report()).thenReturn(new PluginStatusReport(List.of(
                new PluginDiagnostic(EXTERNAL_ID, PluginStatus.STARTED, descriptor, false, List.of()))));
        when(lifecycle.managedPluginIds()).thenReturn(Set.of(EXTERNAL_ID));

        assertManagementError(() -> service(status, lifecycle, RequiredPluginPolicy.empty(),
                        mock(RecoveryModeService.class))
                        .perform(EXTERNAL_ID, PluginManagementService.LifecycleAction.STOP),
                PluginManagementErrorCode.RESTART_REQUIRED_PLUGIN);
        verify(lifecycle, never()).stop(EXTERNAL_ID);
    }

    @Test
    @DisplayName("perform 对已安装但未激活的外置插件拒绝（409 inactive）")
    void performRefusesInactiveExternal() {
        PluginStatusService status = mock(PluginStatusService.class);
        PluginLifecycleService lifecycle = mock(PluginLifecycleService.class);
        when(lifecycle.managedPluginIds()).thenReturn(Set.of());
        when(status.report()).thenReturn(new PluginStatusReport(List.of(
                new PluginDiagnostic(EXTERNAL_ID, PluginStatus.DISABLED, descriptor(EXTERNAL_ID, PluginKind.FEATURE),
                        false, List.of()))));

        assertManagementError(() -> service(status, lifecycle, RequiredPluginPolicy.empty(),
                        mock(RecoveryModeService.class))
                        .perform(EXTERNAL_ID, PluginManagementService.LifecycleAction.START),
                PluginManagementErrorCode.INACTIVE_PLUGIN);
    }

    @Test
    @DisplayName("perform 对未知 id 拒绝（404 unknown）")
    void performRefusesUnknown() {
        PluginStatusService status = mock(PluginStatusService.class);
        PluginLifecycleService lifecycle = mock(PluginLifecycleService.class);
        when(lifecycle.managedPluginIds()).thenReturn(Set.of());
        when(status.report()).thenReturn(PluginStatusReport.empty());

        assertManagementError(() -> service(status, lifecycle, RequiredPluginPolicy.empty(),
                        mock(RecoveryModeService.class))
                        .perform("no-such-plugin", PluginManagementService.LifecycleAction.START),
                PluginManagementErrorCode.UNKNOWN_PLUGIN);
    }

    @Test
    @DisplayName("perform 把 PluginLifecycleService 的非法流转转为 409 transition")
    void performMapsIllegalTransition() {
        PluginStatusService status = mock(PluginStatusService.class);
        PluginLifecycleService lifecycle = mock(PluginLifecycleService.class);
        when(lifecycle.managedPluginIds()).thenReturn(Set.of(EXTERNAL_ID));
        org.mockito.Mockito.doThrow(new PluginLifecycleException("illegal transition"))
                .when(lifecycle).quiesce(EXTERNAL_ID);

        assertManagementError(() -> service(status, lifecycle, RequiredPluginPolicy.empty(),
                        mock(RecoveryModeService.class))
                        .perform(EXTERNAL_ID, PluginManagementService.LifecycleAction.QUIESCE),
                PluginManagementErrorCode.ILLEGAL_TRANSITION);
    }

    private static RequiredPluginPolicy requiredPolicy() {
        return RequiredPluginPolicy.of(List.of(new RequiredPluginPolicy.RequiredPlugin(
                REQUIRED_EXTERNAL_ID, PluginApiRequirement.unspecified(), false, "plugin.recovery.blocked")));
    }

    private static PluginManagementService.PluginManagementEntry entry(
            PluginManagementService.PluginManagementReport report, String id) {
        return report.plugins().stream().filter(e -> e.id().equals(id)).findFirst().orElseThrow();
    }

    private static PluginDescriptor official(String id, String namespace, String icon, String color) {
        return new PluginDescriptor(id, id, "1.0.0", PluginApiRequirement.unspecified(),
                List.of(), id + ".Plugin", namespace, "plugin.name", "plugin.summary", icon, color,
                PluginKind.FEATURE);
    }

    /**
     * 断言抛出 {@link PluginManagementException} 且其稳定机器码为 {@code code}；HTTP 状态与 i18n key 必须由该稳定码
     * 派生（守护「code 是事实源、status/messageKey 与之一致」，不让二者悄悄漂移）。
     */
    private static void assertManagementError(Runnable action, PluginManagementErrorCode code) {
        assertThatThrownBy(action::run)
                .isInstanceOf(PluginManagementException.class)
                .satisfies(thrown -> {
                    PluginManagementException ex = (PluginManagementException) thrown;
                    assertThat(ex.code()).isEqualTo(code);
                    assertThat(ex.status()).isEqualTo(code.status());
                    assertThat(ex.messageKey()).isEqualTo(code.messageKey());
                });
    }
}
