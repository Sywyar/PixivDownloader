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
}
