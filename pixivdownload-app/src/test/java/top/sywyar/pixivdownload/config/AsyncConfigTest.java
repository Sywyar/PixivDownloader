package top.sywyar.pixivdownload.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import static org.assertj.core.api.Assertions.assertThat;

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

}
