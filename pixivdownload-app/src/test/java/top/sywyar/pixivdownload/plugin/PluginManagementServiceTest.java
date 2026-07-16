package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.PluginApiVersion;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginApiRequirement;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDependencyRef;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDescriptor;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginLifecyclePolicy;
import top.sywyar.pixivdownload.plugin.runtime.status.PluginDiagnostic;
import top.sywyar.pixivdownload.plugin.runtime.status.PluginStatus;
import top.sywyar.pixivdownload.plugin.runtime.status.PluginStatusReport;
import top.sywyar.pixivdownload.plugin.runtime.status.RequiredPluginPolicy;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginLifecycleException;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginLifecycleService;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginRuntimePhase;
import top.sywyar.pixivdownload.plugin.management.PluginManagementErrorCode;
import top.sywyar.pixivdownload.plugin.management.PluginManagementException;
import top.sywyar.pixivdownload.plugin.management.PluginManagementService;
import top.sywyar.pixivdownload.plugin.management.PluginStatusService;
import top.sywyar.pixivdownload.plugin.recovery.RecoveryModeService;

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
        return new PluginManagementService(status, lifecycle, policy, recovery);
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

        PluginManagementService.PluginManagementReport report =
                service(status, lifecycle, RequiredPluginPolicy.empty(), recovery).list();

        assertThat(report.recoveryMode()).isTrue();
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
                new PluginManagementService(status, lifecycle, RequiredPluginPolicy.empty(), recovery, toggles).list(),
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
