package top.sywyar.pixivdownload.plugin.runtime.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginApiRequirement;
import top.sywyar.pixivdownload.plugin.runtime.status.RequiredPluginPolicy.RequiredPlugin;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("恢复模式评估器：必选插件未满足时判定进入恢复模式")
class RecoveryModeEvaluatorTest {

    private static final String DW = "download-workbench";
    private final RecoveryModeEvaluator evaluator = new RecoveryModeEvaluator();

    private static RequiredPluginPolicy policyRequiring(String id) {
        return RequiredPluginPolicy.of(List.of(new RequiredPlugin(
                id, PluginApiRequirement.of(1, 0), false, "plugin.recovery.missing." + id)));
    }

    private static PluginStatusReport reportWith(String id, PluginStatus status, String... messages) {
        return new PluginStatusReport(List.of(
                new PluginDiagnostic(id, status, null, true, List.of(messages))));
    }

    @Test
    @DisplayName("必选插件根本不在报告中：进入恢复模式，原因状态为 MISSING_REQUIRED")
    void missingFromReportEntersRecovery() {
        PluginStatusReport report = new PluginStatusReport(List.of(
                new PluginDiagnostic("core", PluginStatus.STARTED, null, false, List.of())));

        RecoveryModeDecision decision = evaluator.evaluate(report, policyRequiring(DW));

        assertThat(decision.active()).isTrue();
        assertThat(decision.reasons()).hasSize(1);
        RecoveryModeReason reason = decision.firstReason().orElseThrow();
        assertThat(reason.pluginId()).isEqualTo(DW);
        assertThat(reason.status()).isEqualTo(PluginStatus.MISSING_REQUIRED);
        assertThat(reason.messageKey()).isEqualTo("plugin.recovery.missing." + DW);
    }

    @Test
    @DisplayName("必选插件状态为 MISSING_REQUIRED：进入恢复模式")
    void missingRequiredEntersRecovery() {
        RecoveryModeDecision decision =
                evaluator.evaluate(reportWith(DW, PluginStatus.MISSING_REQUIRED), policyRequiring(DW));
        assertThat(decision.active()).isTrue();
        assertThat(decision.firstReason().orElseThrow().status()).isEqualTo(PluginStatus.MISSING_REQUIRED);
    }

    @Test
    @DisplayName("必选插件被禁用：进入恢复模式")
    void disabledEntersRecovery() {
        RecoveryModeDecision decision =
                evaluator.evaluate(reportWith(DW, PluginStatus.DISABLED), policyRequiring(DW));
        assertThat(decision.active()).isTrue();
        assertThat(decision.firstReason().orElseThrow().status()).isEqualTo(PluginStatus.DISABLED);
    }

    @Test
    @DisplayName("必选插件启动失败：进入恢复模式，原因携带诊断说明")
    void failedEntersRecovery() {
        RecoveryModeDecision decision =
                evaluator.evaluate(reportWith(DW, PluginStatus.FAILED, "boom"), policyRequiring(DW));
        assertThat(decision.active()).isTrue();
        RecoveryModeReason reason = decision.firstReason().orElseThrow();
        assertThat(reason.status()).isEqualTo(PluginStatus.FAILED);
        assertThat(reason.messages()).containsExactly("boom");
    }

    @Test
    @DisplayName("必选插件版本不兼容：进入恢复模式，原因携带所需版本范围")
    void incompatibleEntersRecoveryWithRequiredVersion() {
        RecoveryModeDecision decision =
                evaluator.evaluate(reportWith(DW, PluginStatus.INCOMPATIBLE_REQUIRED, "needs 2.0"),
                        policyRequiring(DW));
        assertThat(decision.active()).isTrue();
        RecoveryModeReason reason = decision.firstReason().orElseThrow();
        assertThat(reason.status()).isEqualTo(PluginStatus.INCOMPATIBLE_REQUIRED);
        assertThat(reason.requiredVersion().display()).isEqualTo("1.0");
    }

    @Test
    @DisplayName("必选插件已 STARTED：正常运行，不进入恢复模式")
    void startedIsOperational() {
        RecoveryModeDecision decision =
                evaluator.evaluate(reportWith(DW, PluginStatus.STARTED), policyRequiring(DW));
        assertThat(decision.active()).isFalse();
        assertThat(decision.reasons()).isEmpty();
    }

    @Test
    @DisplayName("空必选策略：无论报告如何都正常运行")
    void emptyPolicyIsOperational() {
        RecoveryModeDecision decision = evaluator.evaluate(
                reportWith(DW, PluginStatus.MISSING_REQUIRED), RequiredPluginPolicy.empty());
        assertThat(decision.active()).isFalse();
    }

    @Test
    @DisplayName("多个必选插件部分未满足：只为未满足者给出原因")
    void multipleRequiredOnlyUnsatisfiedReported() {
        RequiredPluginPolicy policy = RequiredPluginPolicy.of(List.of(
                new RequiredPlugin("download-workbench", PluginApiRequirement.of(1, 0), false, "k1"),
                new RequiredPlugin("schedule", PluginApiRequirement.of(1, 0), false, "k2")));
        PluginStatusReport report = new PluginStatusReport(List.of(
                new PluginDiagnostic("download-workbench", PluginStatus.STARTED, null, true, List.of()),
                new PluginDiagnostic("schedule", PluginStatus.DISABLED, null, true, List.of())));

        RecoveryModeDecision decision = evaluator.evaluate(report, policy);

        assertThat(decision.active()).isTrue();
        assertThat(decision.reasons()).extracting(RecoveryModeReason::pluginId).containsExactly("schedule");
    }

    @Test
    @DisplayName("report 或 policy 为 null：正常运行（不抛出）")
    void nullInputsAreOperational() {
        assertThat(evaluator.evaluate(null, policyRequiring(DW)).active()).isFalse();
        assertThat(evaluator.evaluate(reportWith(DW, PluginStatus.MISSING_REQUIRED), null).active()).isFalse();
    }
}
