package top.sywyar.pixivdownload.core.schedule.capability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

@DisplayName("计划租约快照与关闭交错")
class ScheduleLeaseStateTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("接纳期间旧根关闭不损坏新租约、取消和排空")
    void acquireWhileObservedRootCloses(boolean branchToken) {
        ScheduleLeaseState state = new ScheduleLeaseState();
        ScheduleLeaseRoot closing = spy(new ScheduleLeaseRoot());
        var closingToken = register(state, closing, branchToken);
        ScheduleLeaseRoot retained = new ScheduleLeaseRoot();
        var retainedToken = register(state, retained, false);
        closeAfterObservation(closing);

        ScheduleLeaseRoot added = new ScheduleLeaseRoot();
        var addedToken = register(state, added, false);

        assertThat(closing.isClosed()).isTrue();
        assertThat(state.activeLeaseCount()).isEqualTo(2);
        state.release(closingToken);
        state.retire();
        assertThat(state.isAccepting()).isFalse();
        assertThat(retained.cancellation().isCancellationRequested()).isTrue();
        assertThat(added.cancellation().isCancellationRequested()).isTrue();
        assertThat(state.awaitDrained(System.nanoTime())).isFalse();

        retained.closePlanningOrPrepared();
        state.release(retainedToken);
        assertThat(state.activeLeaseCount()).isEqualTo(1);
        added.closePlanningOrPrepared();
        state.release(addedToken);
        assertThat(state.awaitDrained(System.nanoTime())).isTrue();
        assertThat(state.isDrained()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("撤回压缩期间旧根关闭仍取消其余租约并拒绝新接纳")
    void retireWhileObservedRootCloses(boolean branchToken) {
        ScheduleLeaseState state = new ScheduleLeaseState();
        ScheduleLeaseRoot closed = new ScheduleLeaseRoot();
        var closedToken = register(state, closed, false);
        ScheduleLeaseRoot closing = spy(new ScheduleLeaseRoot());
        var closingToken = register(state, closing, branchToken);
        ScheduleLeaseRoot retained = new ScheduleLeaseRoot();
        var retainedToken = register(state, retained, false);
        closed.closePlanningOrPrepared();
        closeAfterObservation(closing);

        state.retire();

        assertThat(closing.isClosed()).isTrue();
        assertThat(state.isAccepting()).isFalse();
        assertThat(state.activeLeaseCount()).isEqualTo(1);
        assertThat(retained.cancellation().isCancellationRequested()).isTrue();
        assertThat(state.tryAcquire(ScheduleLeaseState.LeaseToken.root(new ScheduleLeaseRoot())))
                .isFalse();
        state.release(closedToken);
        state.release(closingToken);
        assertThat(state.awaitDrained(System.nanoTime())).isFalse();
        retained.closePlanningOrPrepared();
        state.release(retainedToken);
        assertThat(state.awaitDrained(System.nanoTime())).isTrue();
        assertThat(state.activeLeaseCount()).isZero();
    }

    private static ScheduleLeaseState.LeaseToken register(
            ScheduleLeaseState state, ScheduleLeaseRoot root, boolean branchToken) {
        if (branchToken) {
            assertThat(root.activatePlanning()).isTrue();
            ScheduleLeaseBranch branch = new ScheduleLeaseBranch(root);
            var token = ScheduleLeaseState.LeaseToken.branch(root, branch);
            assertThat(state.tryAcquire(token)).isTrue();
            assertThat(branch.activate()).isTrue();
            return token;
        }
        var token = ScheduleLeaseState.LeaseToken.root(root);
        assertThat(state.tryAcquire(token)).isTrue();
        assertThat(root.activatePlanning()).isTrue();
        return token;
    }

    private static void closeAfterObservation(ScheduleLeaseRoot root) {
        // 关闭先改变根状态，再等待 owner 锁释放 token；固定在存活性读取之后发生。
        doAnswer(invocation -> {
            boolean live = (boolean) invocation.callRealMethod();
            root.closePlanningOrPrepared();
            return live;
        }).when(root).isLive();
    }
}
