package top.sywyar.pixivdownload.schedule;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ScheduleRunState 瞬时运行态")
class ScheduleRunStateTest {

    @Test
    @DisplayName("同一任务只能被一个 claim 占用，过期 claim 不能误清状态")
    void shouldKeepStateOwnedByCurrentClaimOnly() {
        ScheduleRunState state = new ScheduleRunState();

        ScheduleRunState.Claim claim = state.tryMarkQueued(1L);

        assertThat(claim).isNotNull();
        assertThat(state.get(1L)).isEqualTo(ScheduleRunState.QUEUED);
        assertThat(state.tryMarkRunning(1L)).isNull();

        assertThat(state.markRunning(claim)).isTrue();
        assertThat(state.get(1L)).isEqualTo(ScheduleRunState.RUNNING);

        ScheduleRunState.Claim stale = new ScheduleRunState.Claim(1L, claim.claimId() + 1);
        state.clear(stale);
        assertThat(state.get(1L)).isEqualTo(ScheduleRunState.RUNNING);

        state.clear(claim);
        assertThat(state.get(1L)).isNull();
    }

    @Test
    @DisplayName("requestCancel 仅对存在 claim 的任务生效；clear 后取消标记随 Entry 一起消失")
    void shouldRequestCancelOnlyWhileClaimed() {
        ScheduleRunState state = new ScheduleRunState();

        // 任务空闲：requestCancel 是 no-op（DB 的 PAUSED + findDue 状态门已足够挡住调度）。
        assertThat(state.requestCancel(7L)).isFalse();
        assertThat(state.isCancelRequested(7L)).isFalse();

        ScheduleRunState.Claim claim = state.tryMarkQueued(7L);
        assertThat(state.isCancelRequested(7L)).isFalse();

        // 任务运行中：requestCancel 把当前 Claim 标为待取消，executor 在下个派发前抛 Pause 异常。
        assertThat(state.requestCancel(7L)).isTrue();
        assertThat(state.isCancelRequested(7L)).isTrue();

        // markRunning 不应丢失取消标记（QUEUED → RUNNING 跨态保留）。
        assertThat(state.markRunning(claim)).isTrue();
        assertThat(state.isCancelRequested(7L)).isTrue();

        // 本轮结束 / 进程清理：Entry 被移除，取消标记随之消失，下一轮新 Claim 默认未取消。
        state.clear(claim);
        assertThat(state.isCancelRequested(7L)).isFalse();
        ScheduleRunState.Claim next = state.tryMarkQueued(7L);
        assertThat(next).isNotNull();
        assertThat(state.isCancelRequested(7L)).isFalse();
    }
}
