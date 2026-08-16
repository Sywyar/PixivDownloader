package top.sywyar.pixivdownload.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import top.sywyar.pixivdownload.core.appconfig.DownloadConfig;
import top.sywyar.pixivdownload.core.download.InteractiveDownloadExecutionLane;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("异步基础设施配置")
class AsyncConfigTest {

    @Test
    @DisplayName("宿主调度器取消延迟任务时应立即移除队列句柄")
    void schedulerRemovesCancelledTasks() {
        ThreadPoolTaskScheduler scheduler = (ThreadPoolTaskScheduler) new AsyncConfig().taskScheduler();
        scheduler.initialize();
        try {
            assertThat(scheduler.getScheduledThreadPoolExecutor().getRemoveOnCancelPolicy()).isTrue();
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    @DisplayName("交互下载通道保持任务对象并委托既有下载执行器")
    void interactiveDownloadLaneDelegatesExactTask() {
        AtomicReference<Runnable> submitted = new AtomicReference<>();
        InteractiveDownloadExecutionLane lane =
                new AsyncConfig().interactiveDownloadExecutionLane(submitted::set);
        Runnable task = () -> {
        };

        lane.execute(task);

        assertThat(submitted).hasValue(task);
    }

    @Test
    @DisplayName("交互下载通道同步传播宿主执行器拒绝")
    void interactiveDownloadLanePropagatesRejection() {
        RejectedExecutionException rejection = new RejectedExecutionException("full");
        InteractiveDownloadExecutionLane lane =
                new AsyncConfig().interactiveDownloadExecutionLane(task -> {
                    throw rejection;
                });

        assertThatThrownBy(() -> lane.execute(() -> {
        })).isSameAs(rejection);
    }

    @Test
    @DisplayName("下载执行器应拒绝超过固定排队上限的任务")
    void downloadExecutorRejectsBeyondQueueCapacity() throws Exception {
        DownloadConfig config = new DownloadConfig();
        config.setMaxConcurrent(1);
        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) new AsyncConfig().downloadTaskExecutor(config);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            executor.execute(() -> {
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            for (int i = 0; i < 100; i++) {
                executor.execute(() -> {
                });
            }

            assertThatThrownBy(() -> executor.execute(() -> {
            })).isInstanceOf(TaskRejectedException.class);
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }

}
