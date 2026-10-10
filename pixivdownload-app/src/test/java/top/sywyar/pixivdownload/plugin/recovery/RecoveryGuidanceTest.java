package top.sywyar.pixivdownload.plugin.recovery;

import org.junit.jupiter.api.DisplayName;
import top.sywyar.pixivdownload.gui.DesktopUiFailure;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDescriptor;
import top.sywyar.pixivdownload.plugin.runtime.install.transaction.PluginRecoveryGateSnapshot;
import top.sywyar.pixivdownload.plugin.runtime.install.transaction.PluginTransactionRecoveryReport;
import top.sywyar.pixivdownload.plugin.runtime.install.transaction.PluginTransactionRecoveryReport.Failure;
import top.sywyar.pixivdownload.plugin.runtime.install.transaction.PluginTransactionRecoveryReport.FailureKind;
import top.sywyar.pixivdownload.plugin.runtime.status.PluginDiagnostic;
import top.sywyar.pixivdownload.plugin.runtime.status.PluginStatus;
import top.sywyar.pixivdownload.plugin.runtime.status.PluginStatusReport;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DisplayName("恢复建议按结构化事实分类")
class RecoveryGuidanceTest {
    private static final DesktopUiFailure NO_GUI = new DesktopUiFailure(DesktopUiFailure.Reason.ALL_FAILED, null);
    private static final PluginRecoveryGateSnapshot SAFE =
            PluginRecoveryGateSnapshot.safe(PluginTransactionRecoveryReport.success());

    @ParameterizedTest
    @CsvSource({
            "DIRECTORY_IN_USE, directory-in-use",
            "STAGING_ROOT_UNSAFE, unsafe-path",
            "STAGING_ENUMERATION_FAILED, directory-unreadable",
            "INVALID_TRANSACTION_ENTRY, unsafe-path",
            "MISSING_MANIFEST, manifest",
            "INVALID_MANIFEST, manifest",
            "IDENTITY_CONFLICT, identity-conflict",
            "UNSAFE_PATH, unsafe-path",
            "RECOVERY_FAILED, transaction"
    })
    @DisplayName("所有事务失败分类都有建议，任意异常文本不能改变分类")
    void transactionCategories(FailureKind kind, String advice) {
        var guidance = RecoveryGuidance.from(blocked(kind), NO_GUI, report(PluginStatus.MISSING_REQUIRED), List.of());
        assertThat(guidance.adviceKeys()).containsExactly("recovery.advice." + advice);
        assertThat(guidance.errors()).hasSize(1);
    }

    @ParameterizedTest
    @CsvSource({
            "INSTALLED, not-started", "RESOLVED, not-started", "LOADED, not-started",
            "STARTED, unknown", "STOPPED, not-started", "DISABLED, disabled",
            "FAILED, failed", "CRASHED, crashed", "INCOMPATIBLE, incompatible",
            "MISSING_REQUIRED, missing", "INCOMPATIBLE_REQUIRED, incompatible"
    })
    @DisplayName("必需插件的每种生命周期状态给出对应处理建议")
    void pluginCategories(PluginStatus status, String advice) {
        assertThat(RecoveryGuidance.from(SAFE, null, report(status), List.of()).adviceKeys())
                .containsExactly("recovery.advice." + advice);
    }

    @Test
    @DisplayName("依赖不可用不等于依赖方缺失，坏包不误判为未安装")
    void dependenciesAndKnownBrokenPackages() {
        var dependent = new PluginDiagnostic("dependent", PluginStatus.MISSING_REQUIRED,
                mock(PluginDescriptor.class), true, List.of("dependency unavailable"));
        assertThat(RecoveryGuidance.from(SAFE, null,
                new PluginStatusReport(List.of(dependent)), List.of()).adviceKeys())
                .containsExactly("recovery.advice.dependency");
        var missing = diagnostic("broken", PluginStatus.MISSING_REQUIRED, true);
        var broken = diagnostic("broken", PluginStatus.FAILED, false);
        assertThat(RecoveryGuidance.from(SAFE, null,
                new PluginStatusReport(List.of(missing, broken)), List.of()).adviceKeys())
                .containsExactly("recovery.advice.failed");
    }

    @Test
    @DisplayName("多种故障同时保留且同类去重，目录安全问题不被锁竞争掩盖")
    void simultaneousProblems() {
        var report = new PluginStatusReport(List.of(
                diagnostic("disabled", PluginStatus.DISABLED, true),
                diagnostic("crashed", PluginStatus.CRASHED, true),
                diagnostic("broken", PluginStatus.FAILED, false),
                diagnostic("another-broken", PluginStatus.FAILED, false)));
        assertThat(RecoveryGuidance.from(SAFE, NO_GUI, report, List.of()).adviceKeys())
                .containsExactly("recovery.advice.crashed", "recovery.advice.failed",
                        "recovery.advice.disabled", "recovery.advice.no-gui");
        assertThat(RecoveryGuidance.from(blocked(FailureKind.DIRECTORY_IN_USE, FailureKind.UNSAFE_PATH),
                NO_GUI, report, List.of()).adviceKeys())
                .containsExactly("recovery.advice.directory-in-use", "recovery.advice.unsafe-path");
    }

    @Test
    @DisplayName("可选插件主动停用不生成修复建议，明确故障优先进入有界摘要")
    void intentionalStopAndErrorPriority() {
        var diagnostics = new java.util.ArrayList<PluginDiagnostic>();
        for (int i = 0; i < 12; i++) diagnostics.add(diagnostic("disabled-" + i, PluginStatus.DISABLED, false));
        diagnostics.add(diagnostic("stopped", PluginStatus.STOPPED, false));
        var optional = new PluginStatusReport(diagnostics);
        assertThat(RecoveryGuidance.from(SAFE, null, optional, List.of()).adviceKeys())
                .containsExactly("recovery.advice.unknown");
        assertThat(RecoveryGuidance.from(SAFE, null, optional, List.of()).errors()).isEmpty();
        diagnostics.add(diagnostic("broken", PluginStatus.FAILED, false));
        assertThat(RecoveryGuidance.from(SAFE, NO_GUI, new PluginStatusReport(diagnostics), List.of()).errors().get(0))
                .startsWith("broken [FAILED]");
    }

    @Test
    @DisplayName("没有诊断和未完成清点都有保守兜底，不猜测锁或损坏插件")
    void unknownAndUnchecked() {
        var empty = PluginStatusReport.empty();
        assertThat(RecoveryGuidance.from(SAFE, null, empty, List.of("directory lock timeout")).adviceKeys())
                .containsExactly("recovery.advice.unknown");
        assertThat(RecoveryGuidance.from(SAFE, NO_GUI, empty, List.of()).adviceKeys())
                .containsExactly("recovery.advice.no-gui");
        assertThat(RecoveryGuidance.from(PluginRecoveryGateSnapshot.unchecked(), NO_GUI, empty, List.of()).adviceKeys())
                .containsExactly("recovery.advice.transaction");
    }

    @Test
    @DisplayName("长异常摘要有界且去重，移除控制字符并保留 Unicode 字符")
    void boundsUntrustedDetails() {
        var errors = RecoveryGuidance.from(SAFE, NO_GUI, PluginStatusReport.empty(),
                java.util.stream.IntStream.range(0, 30).mapToObj(i -> i + "\u0000" + "😀".repeat(500)).toList()).errors();
        assertThat(errors).hasSize(8).allSatisfy(error -> {
            assertThat(error.length()).isLessThanOrEqualTo(601);
            assertThat(error).doesNotContain("\u0000");
            assertThat(Character.isHighSurrogate(error.charAt(error.length() - 2))).isFalse();
        });
        assertThat(RecoveryGuidance.from(SAFE, null, PluginStatusReport.empty(), List.of("same", "same")).errors())
                .containsExactly("same");
    }

    @ParameterizedTest
    @CsvSource({"NO_PROVIDER, no-provider", "SELECTION_FAILED, gui-selection", "ALL_FAILED, no-gui"})
    @DisplayName("无异常时也有具体 GUI 摘要，目录锁阻断时不误报插件缺失")
    void desktopReasonsHaveSummary(DesktopUiFailure.Reason reason, String key) {
        var failure = new DesktopUiFailure(reason, null);
        var guidance = RecoveryGuidance.from(SAFE, failure, PluginStatusReport.empty(), List.of());
        assertThat(guidance.adviceKeys()).containsExactly("recovery.advice." + key);
        assertThat(guidance.localizedErrors(value -> value)).containsExactly("recovery.summary." + key);
        assertThat(RecoveryGuidance.from(blocked(FailureKind.DIRECTORY_IN_USE), failure,
                PluginStatusReport.empty(), List.of()).desktopSummaryKey()).isNull();
        var many = RecoveryGuidance.from(SAFE, failure, PluginStatusReport.empty(),
                java.util.stream.IntStream.range(0, 12).mapToObj(i -> "error-" + i).toList());
        assertThat(many.localizedErrors(value -> value)).hasSize(8);
    }

    private static PluginDiagnostic diagnostic(String id, PluginStatus status, boolean required) {
        return new PluginDiagnostic(id, status, null, required, List.of());
    }

    private static PluginStatusReport report(PluginStatus status) {
        return new PluginStatusReport(List.of(diagnostic("fixture", status, true)));
    }

    private static PluginRecoveryGateSnapshot blocked(FailureKind... kinds) {
        return PluginRecoveryGateSnapshot.blocked(new PluginTransactionRecoveryReport(
                java.util.Arrays.stream(kinds).map(kind -> new Failure(
                        "fixture", Path.of("plugins"), kind, "arbitrary text: directory lock")).toList()));
    }
}
