package top.sywyar.pixivdownload.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import top.sywyar.pixivdownload.core.download.InteractiveDownloadExecutionLane;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("异步基础设施配置")
class AsyncConfigTest {

    @Test
    @DisplayName("父调度器取消延迟任务时应立即移除队列句柄")
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

}
