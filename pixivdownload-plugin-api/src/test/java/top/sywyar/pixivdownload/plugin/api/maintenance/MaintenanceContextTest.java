package top.sywyar.pixivdownload.plugin.api.maintenance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MaintenanceContext 契约测试")
class MaintenanceContextTest {

    @Test
    @DisplayName("维护任务可经上下文向宿主上报进度")
    void reportsProgressThroughHostCallback() {
        AtomicReference<String> progress = new AtomicReference<>();
        MaintenanceContext context = new MaintenanceContext(
                "manual", 123L, (done, total) -> progress.set(done + "/" + total));

        context.updateProgress(3, 8);

        assertThat(progress).hasValue("3/8");
    }

    @Test
    @DisplayName("二参数兼容构造使用空进度回调")
    void twoArgumentConstructorUsesNoOpReporter() {
        MaintenanceContext context = new MaintenanceContext("schedule", 456L);

        context.updateProgress(1, 2);

        assertThat(context.triggeredBy()).isEqualTo("schedule");
        assertThat(context.startedAt()).isEqualTo(456L);
        assertThat(context.progressReporter()).isSameAs(MaintenanceProgressReporter.noop());
    }
}
