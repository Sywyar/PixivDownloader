package top.sywyar.pixivdownload.plugin.recovery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDependencyRef;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDescriptor;
import top.sywyar.pixivdownload.plugin.runtime.status.PluginDiagnostic;
import top.sywyar.pixivdownload.plugin.runtime.status.PluginStatus;
import top.sywyar.pixivdownload.plugin.runtime.status.PluginStatusReport;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("恢复检查对象来自插件事实，不从错误文案猜测")
class RecoveryPluginFocusTest {
    @Test
    @DisplayName("必装项不可用时包含必装候选与明确故障，不误选主动停用的可选插件")
    void requiredAndFailures() {
        var focus = RecoveryPluginFocus.from(true, false, report(
                plugin("missing", PluginStatus.MISSING_REQUIRED, true),
                plugin("running-required", PluginStatus.STARTED, true),
                plugin("disabled-required", PluginStatus.DISABLED, true),
                plugin("optional-disabled", PluginStatus.DISABLED, false),
                plugin("broken", PluginStatus.FAILED, false),
                plugin("incompatible", PluginStatus.INCOMPATIBLE, false)), Set.of("startup-failed"));
        assertThat(focus.plugins()).containsOnlyKeys("missing", "running-required", "disabled-required",
                "broken", "incompatible", "startup-failed");
        assertThat(focus.plugins()).containsEntry("broken", "failed").containsEntry("missing", "missing");
        assertThat(focus.categories()).isEmpty();
    }

    @Test
    @DisplayName("跟随不可用依赖链和版本冲突，跳过可选依赖并终止环")
    void dependencyGraph() {
        var root = described("root", PluginStatus.MISSING_REQUIRED, "1.0", "middle,optional?@1.0,old@2.0");
        var middle = described("middle", PluginStatus.DISABLED, "1.0", "root,absent");
        var old = described("old", PluginStatus.STARTED, "1.0", "");
        var focus = RecoveryPluginFocus.from(true, false, report(root, middle, old), Set.of());
        assertThat(focus.plugins()).containsOnlyKeys("root", "middle", "absent", "old");
        assertThat(focus.plugins()).containsEntry("old", "dependency").containsEntry("absent", "dependency");
    }

    @Test
    @DisplayName("没有 GUI 按分类给候选，扫描受阻时不将目录故障归咎于所有 GUI")
    void guiAndBlockedInventory() {
        assertThat(RecoveryPluginFocus.from(true, true, PluginStatusReport.empty(), Set.of()).categories())
                .containsExactly("ui");
        var blocked = RecoveryPluginFocus.from(false, true,
                report(plugin("missing", PluginStatus.MISSING_REQUIRED, true)), Set.of("failed"));
        assertThat(blocked.plugins()).isEmpty();
        assertThat(blocked.categories()).isEmpty();
    }

    @Test
    @DisplayName("健康必装项和主动停止的可选项本身不产生恢复检查对象")
    void healthyAndIntentionalStop() {
        assertThat(RecoveryPluginFocus.from(true, false, report(
                plugin("required", PluginStatus.STARTED, true),
                plugin("stopped", PluginStatus.STOPPED, false)), Set.of()).plugins()).isEmpty();
    }

    private static PluginStatusReport report(PluginDiagnostic... plugins) {
        return new PluginStatusReport(List.of(plugins));
    }

    private static PluginDiagnostic plugin(String id, PluginStatus status, boolean required) {
        return new PluginDiagnostic(id, status, null, required, List.of());
    }

    private static PluginDiagnostic described(String id, PluginStatus status, String version, String dependencies) {
        var descriptor = mock(PluginDescriptor.class);
        when(descriptor.version()).thenReturn(version);
        when(descriptor.dependencies()).thenReturn(PluginDependencyRef.parseList(dependencies));
        return new PluginDiagnostic(id, status, descriptor, false, List.of());
    }
}
