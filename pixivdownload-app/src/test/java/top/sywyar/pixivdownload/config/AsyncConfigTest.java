package top.sywyar.pixivdownload.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import top.sywyar.pixivdownload.core.appconfig.DownloadConfig;

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

    @Test
    @DisplayName("小说下载与翻译池在启动时采用各自配置且运行中重绑不虚改容量")
    void novelPoolsCaptureTheirStartupConcurrency() {
        DownloadConfig downloadConfig = new DownloadConfig();
        assertThat(downloadConfig.getNovelMaxConcurrent()).isEqualTo(10);
        assertThat(downloadConfig.getNovelTranslateMaxConcurrent()).isEqualTo(10);
        downloadConfig.setNovelMaxConcurrent(3);
        downloadConfig.setNovelTranslateMaxConcurrent(4);

        AsyncConfig asyncConfig = new AsyncConfig();
        ThreadPoolTaskExecutor downloadPool = asThreadPool(
                asyncConfig.novelDownloadTaskExecutor(downloadConfig));
        ThreadPoolTaskExecutor translatePool = asThreadPool(
                asyncConfig.novelTranslateTaskExecutor(downloadConfig));
        try {
            assertThat(downloadPool.getCorePoolSize()).isEqualTo(3);
            assertThat(downloadPool.getMaxPoolSize()).isEqualTo(3);
            assertThat(translatePool.getCorePoolSize()).isEqualTo(4);
            assertThat(translatePool.getMaxPoolSize()).isEqualTo(4);

            downloadConfig.setNovelMaxConcurrent(1);
            downloadConfig.setNovelTranslateMaxConcurrent(1);
            assertThat(downloadPool.getMaxPoolSize()).isEqualTo(3);
            assertThat(translatePool.getMaxPoolSize()).isEqualTo(4);
        } finally {
            downloadPool.shutdown();
            translatePool.shutdown();
        }
    }

    private static ThreadPoolTaskExecutor asThreadPool(TaskExecutor executor) {
        assertThat(executor).isInstanceOf(ThreadPoolTaskExecutor.class);
        return (ThreadPoolTaskExecutor) executor;
    }
}
