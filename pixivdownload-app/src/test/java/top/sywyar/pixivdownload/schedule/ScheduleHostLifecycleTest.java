package top.sywyar.pixivdownload.schedule;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("宿主计划生命周期")
class ScheduleHostLifecycleTest {
    @Test
    @DisplayName("关闭封闭准入并取消运行，所有租约释放后才完成关闭")
    void closeWaitsForRealDrainAndRejectsNewWork() throws Exception {
        var state = new ScheduleRunState();
        var host = new ScheduleHostLifecycle(state);
        assertThat(host.prepare()).isNull();
        host.start();
        var first = host.prepare();
        var second = host.prepare();
        var claim = state.tryMarkQueued(9L);
        assertThat(host.activate(first)).isTrue();
        var stopped = ScheduleTestHost.stopAsync(host);
        try {
            assertThat(host.prepare()).isNull();
            assertThat(host.activate(first)).isFalse();
            assertThat(first.cancellation().isCancellationRequested()).isTrue();
            assertThat(state.get(9L)).isEqualTo(ScheduleRunState.CANCEL_REQUESTED);
            first.close();
            first.close();
            assertThat(stopped.isDone()).isFalse();
        } finally {
            first.close();
            second.close();
            state.clear(claim);
        }
        stopped.get(5, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("不同宿主和已释放的租约均不能进入运行")
    void foreignAndClosedLeasesAreRejected() {
        var first = ScheduleTestHost.started(new ScheduleRunState());
        var second = ScheduleTestHost.started(new ScheduleRunState());
        var lease = first.prepare();
        assertThat(second.activate(lease)).isFalse();
        lease.close();
        assertThat(first.activate(lease)).isFalse();
        first.stop();
        second.stop();
    }
}
