package top.sywyar.pixivdownload.plugin.runtime.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("插件状态报告：按 id 查询的健壮性")
class PluginStatusReportTest {

    @Test
    @DisplayName("byId(null) 返回空、不抛 NPE")
    void byIdNullIsEmptyNotThrowing() {
        PluginStatusReport report = new PluginStatusReport(List.of(
                new PluginDiagnostic("alpha", PluginStatus.STARTED, null, false, List.of())));

        assertThatCode(() -> report.byId(null)).doesNotThrowAnyException();
        assertThat(report.byId(null)).isEmpty();
    }

    @Test
    @DisplayName("byId 命中返回对应诊断；未命中返回空")
    void byIdHitAndMiss() {
        PluginStatusReport report = new PluginStatusReport(List.of(
                new PluginDiagnostic("alpha", PluginStatus.STARTED, null, false, List.of()),
                new PluginDiagnostic("beta", PluginStatus.DISABLED, null, false, List.of())));

        assertThat(report.byId("alpha")).get().extracting(PluginDiagnostic::status)
                .isEqualTo(PluginStatus.STARTED);
        assertThat(report.byId("beta")).get().extracting(PluginDiagnostic::status)
                .isEqualTo(PluginStatus.DISABLED);
        assertThat(report.byId("missing")).isEmpty();
    }

    @Test
    @DisplayName("空报告对任何 id（含 null）查询都返回空")
    void emptyReportByIdAlwaysEmpty() {
        assertThat(PluginStatusReport.empty().byId("anything")).isEmpty();
        assertThat(PluginStatusReport.empty().byId(null)).isEmpty();
    }

    @Test
    @DisplayName("诊断不可能持有 null id，故 byId(null) 永不误命中、恒返回空")
    void byIdNullNeverCollidesBecauseDiagnosticIdIsNonNull() {
        // PluginDiagnostic 构造期即拒绝 null id：「id 为 null 的诊断」无法存在，
        // 故 byId(null) 不存在「与 null id 诊断相等而误命中」的可能。
        assertThatThrownBy(() -> new PluginDiagnostic(
                null, PluginStatus.STARTED, null, false, List.of()))
                .isInstanceOf(NullPointerException.class);

        // 即便报告非空，byId(null) 也因 null 短路直接返回空。
        PluginStatusReport report = new PluginStatusReport(List.of(
                new PluginDiagnostic("alpha", PluginStatus.STARTED, null, false, List.of()),
                new PluginDiagnostic("beta", PluginStatus.DISABLED, null, false, List.of())));
        assertThat(report.byId(null)).isEmpty();
    }
}
