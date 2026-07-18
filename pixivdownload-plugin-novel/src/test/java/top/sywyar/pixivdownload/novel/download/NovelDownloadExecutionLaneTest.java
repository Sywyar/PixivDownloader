package top.sywyar.pixivdownload.novel.download;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import top.sywyar.pixivdownload.config.OutboundProxyEndpoint;
import top.sywyar.pixivdownload.config.OutboundProxyOverride;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("小说下载统一执行通道")
class NovelDownloadExecutionLaneTest {

    private ThreadPoolTaskExecutor taskExecutor;

    @AfterEach
    void shutdownExecutor() {
        OutboundProxyOverride.clear();
        if (taskExecutor != null) {
            taskExecutor.shutdown();
        }
    }

    @Test
    @DisplayName("异步与同步调用应争用同一个硬并发槽位")
    void shouldShareCapacityBetweenAsyncAndBlockingCalls() throws Exception {
        NovelDownloadExecutionLane lane = lane(1);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        lane.execute(() -> {
            firstStarted.countDown();
            await(releaseFirst);
        });
        assertThat(firstStarted.await(2, TimeUnit.SECONDS)).isTrue();

        Thread caller = new Thread(() -> {
            try {
                lane.executeAndWait(() -> {
                    secondStarted.countDown();
                    return true;
                });
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        caller.start();

        assertThat(secondStarted.await(150, TimeUnit.MILLISECONDS)).isFalse();
        releaseFirst.countDown();
        caller.join(2_000L);

        assertThat(caller.isAlive()).isFalse();
        assertThat(failure.get()).isNull();
        assertThat(secondStarted.getCount()).isZero();
    }

    @Test
    @DisplayName("通道工作线程内的同步嵌套调用应直接执行而不死锁")
    void shouldExecuteNestedBlockingCallInline() throws Exception {
        NovelDownloadExecutionLane lane = lane(1);
        AtomicBoolean nestedRan = new AtomicBoolean();

        boolean result = lane.executeAndWait(() -> lane.executeAndWait(() -> {
            nestedRan.set(true);
            return true;
        }));

        assertThat(result).isTrue();
        assertThat(nestedRan).isTrue();
    }

    @Test
    @DisplayName("异步与同步任务都应把调用线程代理传播到通道工作线程")
    void shouldPropagateProxyToAsyncAndBlockingTasks() throws Exception {
        NovelDownloadExecutionLane lane = lane(1);
        AtomicReference<OutboundProxyEndpoint> asyncProxy = new AtomicReference<>();
        CountDownLatch asyncCompleted = new CountDownLatch(1);
        OutboundProxyOverride.set("proxy.example.com:8080");

        lane.execute(() -> {
            asyncProxy.set(OutboundProxyOverride.current());
            asyncCompleted.countDown();
        });
        OutboundProxyEndpoint blockingProxy = lane.executeAndWait(OutboundProxyOverride::current);

        assertThat(asyncCompleted.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(asyncProxy.get()).isEqualTo(new OutboundProxyEndpoint("proxy.example.com", 8080));
        assertThat(blockingProxy).isEqualTo(new OutboundProxyEndpoint("proxy.example.com", 8080));
    }

    @Test
    @DisplayName("显式直连应传播到通道工作线程")
    void shouldPropagateExplicitDirectRoute() throws Exception {
        NovelDownloadExecutionLane lane = lane(1);
        OutboundProxyOverride.setDirect();

        boolean directActive = lane.executeAndWait(() ->
                OutboundProxyOverride.isActive() && OutboundProxyOverride.current() == null);

        assertThat(directActive).isTrue();
    }

    @Test
    @DisplayName("任务异常后应清理代理且下一任务不得串用")
    void shouldClearProxyAfterFailureBeforeNextTask() throws Exception {
        NovelDownloadExecutionLane lane = lane(1);
        OutboundProxyOverride.set("proxy.example.com:8080");

        assertThatThrownBy(() -> lane.executeAndWait(() -> {
            assertThat(OutboundProxyOverride.current())
                    .isEqualTo(new OutboundProxyEndpoint("proxy.example.com", 8080));
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        OutboundProxyOverride.clear();
        assertThat(lane.executeAndWait(OutboundProxyOverride::isActive)).isFalse();
    }

    @Test
    @DisplayName("无覆盖任务应主动清除复用线程上的遗留代理")
    void shouldClearStaleWorkerOverrideForUnscopedTask() throws Exception {
        NovelDownloadExecutionLane lane = lane(1);
        CountDownLatch staleOverrideInstalled = new CountDownLatch(1);
        taskExecutor.execute(() -> {
            OutboundProxyOverride.set("stale.example.com:9090");
            staleOverrideInstalled.countDown();
        });
        assertThat(staleOverrideInstalled.await(2, TimeUnit.SECONDS)).isTrue();

        boolean activeInsideTask = lane.executeAndWait(OutboundProxyOverride::isActive);

        assertThat(activeInsideTask).isFalse();
    }

    private NovelDownloadExecutionLane lane(int capacity) {
        taskExecutor = new ThreadPoolTaskExecutor();
        taskExecutor.setCorePoolSize(capacity);
        taskExecutor.setMaxPoolSize(capacity);
        taskExecutor.setThreadNamePrefix("novel-lane-test-");
        taskExecutor.initialize();
        return new NovelDownloadExecutionLane(taskExecutor, capacity);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
