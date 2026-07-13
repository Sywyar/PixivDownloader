package top.sywyar.pixivdownload.schedule.execution;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledCancellation;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionException;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledFailure;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("计划作品进程级并发限制器")
class ScheduleWorkConcurrencyLimiterTest {

    private static final ScheduledCancellation NOT_CANCELLED = () -> false;

    @Test
    @DisplayName("同一作品类型达到上限后背压并在许可释放后继续")
    void blocksSameWorkTypeUntilPermitIsReleased() throws Exception {
        ScheduleWorkConcurrencyLimiter limiter = new ScheduleWorkConcurrencyLimiter();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (ScheduleWorkConcurrencyLimiter.Permit first =
                     limiter.acquire("illust", 1, NOT_CANCELLED)) {
            Future<ScheduleWorkConcurrencyLimiter.Permit> second = worker.submit(
                    () -> limiter.acquire("illust", 1, NOT_CANCELLED));

            assertThatThrownBy(() -> second.get(150, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            first.close();
            try (ScheduleWorkConcurrencyLimiter.Permit acquired =
                         second.get(1, TimeUnit.SECONDS)) {
                assertThat(acquired).isNotNull();
            }
        } finally {
            worker.shutdownNow();
        }
    }

    @Test
    @DisplayName("等待容量时取消信号可在短轮询内终止")
    void cancellationStopsBlockedAcquirePromptly() throws Exception {
        ScheduleWorkConcurrencyLimiter limiter = new ScheduleWorkConcurrencyLimiter();
        AtomicBoolean cancelled = new AtomicBoolean();
        CountDownLatch started = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (ScheduleWorkConcurrencyLimiter.Permit ignored =
                     limiter.acquire("illust", 1, NOT_CANCELLED)) {
            Future<ScheduleWorkConcurrencyLimiter.Permit> waiting = worker.submit(() -> {
                started.countDown();
                return limiter.acquire("illust", 1, cancelled::get);
            });
            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> waiting.get(100, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            long cancelledAt = System.nanoTime();
            cancelled.set(true);

            assertThatThrownBy(() -> waiting.get(1, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(ScheduledExecutionException.class)
                    .satisfies(failure -> assertThat(((ScheduledExecutionException) failure.getCause()).category())
                            .isEqualTo(ScheduledFailure.Category.CANCELLED));
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - cancelledAt)).isLessThan(500L);
        } finally {
            worker.shutdownNow();
        }
    }

    @Test
    @DisplayName("许可重复关闭只释放一次容量")
    void permitCloseIsIdempotent() throws Exception {
        ScheduleWorkConcurrencyLimiter limiter = new ScheduleWorkConcurrencyLimiter();
        ScheduleWorkConcurrencyLimiter.Permit first = limiter.acquire("novel", 1, NOT_CANCELLED);
        ExecutorService worker = Executors.newFixedThreadPool(2);
        try (ScheduleWorkConcurrencyLimiter.Permit second =
                     closeConcurrently(worker, first, limiter)) {
            Future<ScheduleWorkConcurrencyLimiter.Permit> third = worker.submit(
                    () -> limiter.acquire("novel", 1, NOT_CANCELLED));
            assertThatThrownBy(() -> third.get(150, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            second.close();
            third.get(1, TimeUnit.SECONDS).close();
        } finally {
            worker.shutdownNow();
        }
    }

    private static ScheduleWorkConcurrencyLimiter.Permit closeConcurrently(
            ExecutorService worker,
            ScheduleWorkConcurrencyLimiter.Permit permit,
            ScheduleWorkConcurrencyLimiter limiter) throws Exception {
        Future<?> firstClose = worker.submit(permit::close);
        Future<?> secondClose = worker.submit(permit::close);
        firstClose.get(1, TimeUnit.SECONDS);
        secondClose.get(1, TimeUnit.SECONDS);
        return limiter.acquire("novel", 1, NOT_CANCELLED);
    }

    @Test
    @DisplayName("动态降低上限后等待存量降到新上限以下才发放许可")
    void lowerLimitStopsNewWorkUntilExistingWorkDrains() throws Exception {
        ScheduleWorkConcurrencyLimiter limiter = new ScheduleWorkConcurrencyLimiter();
        ScheduleWorkConcurrencyLimiter.Permit first = limiter.acquire("illust", 2, NOT_CANCELLED);
        ScheduleWorkConcurrencyLimiter.Permit second = limiter.acquire("illust", 2, NOT_CANCELLED);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<ScheduleWorkConcurrencyLimiter.Permit> waiting = worker.submit(
                    () -> limiter.acquire("illust", 1, NOT_CANCELLED));
            assertThatThrownBy(() -> waiting.get(150, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            first.close();
            assertThatThrownBy(() -> waiting.get(150, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            second.close();
            waiting.get(1, TimeUnit.SECONDS).close();
        } finally {
            first.close();
            second.close();
            worker.shutdownNow();
        }
    }

    @Test
    @DisplayName("旧上限持续提交也不能抬高等待请求带来的新上限")
    void staleHigherLimitCannotOverrideLowerWaitingRequest() throws Exception {
        ScheduleWorkConcurrencyLimiter limiter = new ScheduleWorkConcurrencyLimiter();
        ScheduleWorkConcurrencyLimiter.Permit first = limiter.acquire("illust", 3, NOT_CANCELLED);
        ScheduleWorkConcurrencyLimiter.Permit second = limiter.acquire("illust", 3, NOT_CANCELLED);
        ScheduleWorkConcurrencyLimiter.Permit third = limiter.acquire("illust", 3, NOT_CANCELLED);
        ExecutorService worker = Executors.newFixedThreadPool(2);
        Future<ScheduleWorkConcurrencyLimiter.Permit> lower = null;
        Future<ScheduleWorkConcurrencyLimiter.Permit> staleHigher = null;
        try {
            lower = worker.submit(() -> limiter.acquire("illust", 2, NOT_CANCELLED));
            assertBlocked(lower);
            staleHigher = worker.submit(() -> limiter.acquire("illust", 3, NOT_CANCELLED));
            assertBlocked(staleHigher);

            first.close();
            assertBlocked(lower);
            assertBlocked(staleHigher);

            second.close();
            third.close();
            lower.get(1, TimeUnit.SECONDS).close();
            staleHigher.get(1, TimeUnit.SECONDS).close();
        } finally {
            first.close();
            second.close();
            third.close();
            closeCompletedPermit(lower);
            closeCompletedPermit(staleHigher);
            worker.shutdownNow();
        }
    }

    @Test
    @DisplayName("不同作品类型拥有彼此独立的容量")
    void workTypesUseIndependentCapacity() throws Exception {
        ScheduleWorkConcurrencyLimiter limiter = new ScheduleWorkConcurrencyLimiter();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (ScheduleWorkConcurrencyLimiter.Permit ignored =
                     limiter.acquire("illust", 1, NOT_CANCELLED)) {
            Future<ScheduleWorkConcurrencyLimiter.Permit> novel = worker.submit(
                    () -> limiter.acquire("novel", 1, NOT_CANCELLED));
            novel.get(1, TimeUnit.SECONDS).close();
        } finally {
            worker.shutdownNow();
        }
    }

    private static void assertBlocked(Future<ScheduleWorkConcurrencyLimiter.Permit> future) {
        assertThatThrownBy(() -> future.get(150, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);
    }

    private static void closeCompletedPermit(Future<ScheduleWorkConcurrencyLimiter.Permit> future) {
        if (future == null) {
            return;
        }
        if (!future.isDone()) {
            future.cancel(true);
            return;
        }
        try {
            future.get().close();
        } catch (Exception ignored) {
            // 失败路径只负责回收已取得的许可；原始断言仍是测试结果。
        }
    }
}
