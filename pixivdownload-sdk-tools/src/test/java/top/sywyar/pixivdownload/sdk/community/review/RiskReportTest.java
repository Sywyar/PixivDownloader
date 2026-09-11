package top.sywyar.pixivdownload.sdk.community.review;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginRiskDeclaration;
import top.sywyar.pixivdownload.sdk.community.format.CommunityJson;
import top.sywyar.pixivdownload.sdk.community.format.CommunityValues.Evidence;
import top.sywyar.pixivdownload.sdk.community.format.CommunityValues.Reference;
import top.sywyar.pixivdownload.sdk.community.format.ContractException;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

@DisplayName("风险报告的原始证据、确定归因与扫描完整性")
class RiskReportTest {
    private static final String HASH = "ab".repeat(32);
    private static final Evidence CALL = evidence("reports/call.json", "{\"owner\":\"java/nio/file/Files\",\"name\":\"delete\"}".getBytes(StandardCharsets.UTF_8));
    private static final Map<String, Evidence> EVIDENCE = Map.of(CALL.reference().path(), CALL);

    @Test
    @DisplayName("无确定观测正常接受，未完成报告保留故障且不变成空成功")
    void preservesCompletionAndFailure() {
        var complete = read(report(RiskReport.Status.COMPLETE, List.of(), List.of(), null));
        complete.validate(PluginRiskDeclaration.absent(), Map.of());
        assertThat(complete.status()).isEqualTo(RiskReport.Status.COMPLETE);
        var incomplete = read(report(RiskReport.Status.INCOMPLETE, List.of(), List.of(), "UNSUPPORTED_CLASS_VERSION"));
        incomplete.validate(PluginRiskDeclaration.absent(), Map.of());
        assertThat(incomplete.status()).isEqualTo(RiskReport.Status.INCOMPLETE);
        assertThat(incomplete.failureReason()).isEqualTo("UNSUPPORTED_CLASS_VERSION");
        assertThatThrownBy(() -> read(report(RiskReport.Status.INCOMPLETE, List.of(), List.of(), null))).isInstanceOf(ContractException.class);
        assertThatThrownBy(() -> read(report(RiskReport.Status.COMPLETE, List.of(), List.of(), "FAILED"))).isInstanceOf(ContractException.class);
    }

    @Test
    @DisplayName("插件确定调用必须与当前包的声明差异吻合，不能漏报或用旧声明嫁接")
    void checksDeclarationAgainstCertainCalls() {
        var observation = observation(RiskReport.Origin.PLUGIN);
        var finding = missing();
        var report = read(report(RiskReport.Status.COMPLETE, List.of(observation), List.of(finding), null));
        report.validate(PluginRiskDeclaration.absent(), EVIDENCE);
        report.validate(new PluginRiskDeclaration(true, List.of()), EVIDENCE);
        assertThatThrownBy(() -> report.validate(new PluginRiskDeclaration(true, List.of("FILE_DELETE")), EVIDENCE))
                .isInstanceOf(ContractException.class);
        var declared = read(report(RiskReport.Status.COMPLETE, List.of(observation), List.of(), null));
        declared.validate(new PluginRiskDeclaration(true, List.of("FILE_DELETE")), EVIDENCE);
        assertThatThrownBy(() -> declared.validate(PluginRiskDeclaration.absent(), EVIDENCE)).isInstanceOf(ContractException.class);
    }

    @Test
    @DisplayName("依赖、构建和宿主观测不能直接归入插件缺报，政策违规也不由声明豁免")
    void separatesAttributionAndPolicy() {
        for (var origin : List.of(RiskReport.Origin.DEPENDENCY, RiskReport.Origin.BUILD, RiskReport.Origin.HOST)) {
            var report = read(report(RiskReport.Status.COMPLETE, List.of(observation(origin)), List.of(), null));
            report.validate(PluginRiskDeclaration.absent(), EVIDENCE);
            var misattributed = read(report(RiskReport.Status.COMPLETE, List.of(observation(origin)), List.of(missing()), null));
            assertThatThrownBy(() -> misattributed.validate(PluginRiskDeclaration.absent(), EVIDENCE)).isInstanceOf(ContractException.class);
        }
        var violation = new RiskReport.Finding("policy", "private-access", "FILE_DELETE", RiskReport.Kind.POLICY_VIOLATION,
                List.of("call"), List.of(CALL.reference()));
        var declaredViolation = read(report(RiskReport.Status.COMPLETE, List.of(observation(RiskReport.Origin.PLUGIN)), List.of(violation), null));
        declaredViolation.validate(new PluginRiskDeclaration(true, List.of("FILE_DELETE")), EVIDENCE);
        assertThat(declaredViolation.findings()).containsExactly(violation);
    }

    @Test
    @DisplayName("报告拒绝缺失或篡改证据、重复业务 ID、虚构观测和不精确位置")
    void rejectsUnverifiableObservations() {
        var observation = observation(RiskReport.Origin.PLUGIN);
        var report = read(report(RiskReport.Status.COMPLETE, List.of(observation), List.of(missing()), null));
        assertThatThrownBy(() -> report.validate(PluginRiskDeclaration.absent(), Map.of())).isInstanceOf(ContractException.class);
        var duplicate = read(report(RiskReport.Status.COMPLETE, List.of(observation, observation), List.of(missing()), null));
        assertThatThrownBy(() -> duplicate.validate(PluginRiskDeclaration.absent(), EVIDENCE)).isInstanceOf(ContractException.class);
        var invented = new RiskReport.Finding("missing", "direct-delete", "FILE_DELETE", RiskReport.Kind.DECLARATION_MISSING,
                List.of("not-present"), List.of(CALL.reference()));
        assertThatThrownBy(() -> read(report(RiskReport.Status.COMPLETE, List.of(observation), List.of(invented), null))
                .validate(PluginRiskDeclaration.absent(), EVIDENCE)).isInstanceOf(ContractException.class);
        var noLocation = new RiskReport.Observation("call", "FILE_DELETE", RiskReport.Origin.PLUGIN,
                new RiskReport.Location("sample.class", null, null, null, null, null, null), List.of(CALL.reference()));
        assertThatThrownBy(() -> read(report(RiskReport.Status.COMPLETE, List.of(noLocation), List.of(missing()), null))
                .validate(PluginRiskDeclaration.absent(), EVIDENCE)).isInstanceOf(ContractException.class);
        assertThatThrownBy(() -> new Evidence(CALL.reference(), new byte[]{1, 2})).isInstanceOf(ContractException.class);
        byte[] copy = CALL.bytes();
        copy[0] = 0;
        assertThat(CALL.bytes()[0]).isEqualTo((byte) '{');
    }

    private static RiskReport report(RiskReport.Status status, List<RiskReport.Observation> observations,
                                     List<RiskReport.Finding> findings, String failureReason) {
        return new RiskReport(1, status, "test-scanner", HASH, "123", 1, "ab".repeat(20), "cd".repeat(20), HASH,
                observations, findings, failureReason);
    }

    private static RiskReport.Observation observation(RiskReport.Origin origin) {
        return new RiskReport.Observation("call", "FILE_DELETE", origin,
                new RiskReport.Location("sample.class", "example.Sample", "run", "()V", 12L, null, null), List.of(CALL.reference()));
    }

    private static RiskReport.Finding missing() {
        return new RiskReport.Finding("missing", "direct-delete", "FILE_DELETE", RiskReport.Kind.DECLARATION_MISSING,
                List.of("call"), List.of(CALL.reference()));
    }

    private static RiskReport read(RiskReport report) {
        byte[] bytes = CommunityJson.encode(report);
        return RiskReport.read(evidence("reports/risk.json", bytes), bytes.length);
    }

    private static Evidence evidence(String path, byte[] bytes) { return new Evidence(Reference.of(path, bytes), bytes); }
}
