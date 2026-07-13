package top.sywyar.pixivdownload.schedule;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.core.schedule.ScheduledTaskStore;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityRegistry;
import top.sywyar.pixivdownload.core.schedule.work.ScheduledWorkKind;
import top.sywyar.pixivdownload.core.schedule.work.ScheduledWorkRunner;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ScheduleRunner 宿主 owner 租约")
class ScheduleRunnerLeaseTest {

    @Test
    @DisplayName("宿主 publication 尚未发布或已撤回时 tick 不读取到期任务")
    void tickDoesNotReadTasksWithoutHostPublication() {
        ScheduledTaskStore store = mock(ScheduledTaskStore.class);
        ScheduleRunner runner = new ScheduleRunner(
                store,
                mock(ScheduleExecutor.class),
                new ScheduleConfig(),
                new ScheduleRunState(),
                new ScheduleCapabilityRegistry());

        runner.tick();

        verify(store, never()).findDue(anyLong());
    }

    @Test
    @DisplayName("tick 从读取任务到退出全程持有宿主 owner lease")
    void tickKeepsHostLeaseUntilInvocationReturns() throws Exception {
        ScheduledTaskStore store = mock(ScheduledTaskStore.class);
        CountDownLatch lookupStarted = new CountDownLatch(1);
        CountDownLatch allowLookup = new CountDownLatch(1);
        when(store.findDue(anyLong())).thenAnswer(invocation -> {
            lookupStarted.countDown();
            assertThat(allowLookup.await(5, TimeUnit.SECONDS)).isTrue();
            return List.of();
        });
        ScheduledWorkRunner workRunner = mock(ScheduledWorkRunner.class);
        when(workRunner.kind()).thenReturn(ScheduledWorkKind.ILLUST);
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        var publication = ScheduleCapabilityTestFixture.publishDownloadWorkbench(
                registry, List.of(workRunner));
        ScheduleRunner runner = new ScheduleRunner(
                store,
                mock(ScheduleExecutor.class),
                new ScheduleConfig(),
                new ScheduleRunState(),
                registry);
        Thread tick = new Thread(runner::tick, "schedule-runner-lease-test");
        tick.start();
        try {
            assertThat(lookupStarted.await(5, TimeUnit.SECONDS)).isTrue();
            var drain = registry.withdraw(publication).orElseThrow();
            assertThat(drain.activeLeaseCount()).isEqualTo(1);
            assertThat(drain.awaitDrained(
                    System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(50))).isFalse();

            allowLookup.countDown();
            tick.join(5_000L);

            assertThat(tick.isAlive()).isFalse();
            assertThat(drain.awaitDrained(
                    System.nanoTime() + TimeUnit.SECONDS.toNanos(1))).isTrue();
        } finally {
            allowLookup.countDown();
            tick.join(5_000L);
        }
    }
}
