package top.sywyar.pixivdownload.download.config;

import org.springframework.boot.task.ThreadPoolTaskExecutorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import top.sywyar.pixivdownload.quota.MultiModeConfig;

@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {

    @Bean
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(5);
        scheduler.setThreadNamePrefix("app-scheduler-");
        return scheduler;
    }

    /**
     * 通用默认线程池。
     * <p>
     * 一旦本类定义了任何 {@link TaskExecutor}（{@code Executor}）类型的 bean，Spring Boot 的
     * {@code TaskExecutionAutoConfiguration#applicationTaskExecutor}（{@code @ConditionalOnMissingBean(Executor.class)}）
     * 就不会再自动创建。而 Spring MVC 异步支持（SseEmitter 流式进度推送）和无限定符的 {@code @Async}
     * 都依赖名为 {@code applicationTaskExecutor} 的默认执行器，因此这里显式重建它并标记 {@link Primary}：
     * <ul>
     *   <li>Spring MVC 按 bean 名 {@code applicationTaskExecutor} 取它作为异步请求执行器；</li>
     *   <li>无限定符 {@code @Async}（移动记录 / JSON 迁移 / 配额清理 / 邀请）按 {@code @Primary} 解析到它，
     *       而不是退化成每次新建的 {@code SimpleAsyncTaskExecutor}。</li>
     * </ul>
     * 下面三个带名字的专用重任务池通过显式 {@code @Async("...")} 限定符使用，不受 {@code @Primary} 影响。
     */
    @Bean("applicationTaskExecutor")
    @Primary
    public ThreadPoolTaskExecutor applicationTaskExecutor(ThreadPoolTaskExecutorBuilder builder) {
        return builder.threadNamePrefix("task-").build();
    }

    /**
     * 图片 / 作品下载专用线程池，并发上限由 {@code download.max-concurrent} 控制。
     * 与小说下载、归档打包等其它重任务各自隔离，互不抢线程；轻量 {@code @Async}
     * （移动记录 / JSON 迁移 / 配额清理 / 邀请）仍走默认 {@code applicationTaskExecutor}。
     * 线程数在启动时确定，修改配置后需重启。
     */
    @Bean("downloadTaskExecutor")
    public TaskExecutor downloadTaskExecutor(DownloadConfig downloadConfig) {
        return fixedPool(downloadConfig.getMaxConcurrent(), "pixiv-download-");
    }

    /** 小说下载专用线程池，并发上限由 {@code download.novel-max-concurrent} 控制。 */
    @Bean("novelDownloadTaskExecutor")
    public TaskExecutor novelDownloadTaskExecutor(DownloadConfig downloadConfig) {
        return fixedPool(downloadConfig.getNovelMaxConcurrent(), "pixiv-novel-");
    }

    /** 配额归档打包专用线程池，并发上限由 {@code multi-mode.quota.archive-max-concurrent} 控制。 */
    @Bean("archiveTaskExecutor")
    public TaskExecutor archiveTaskExecutor(MultiModeConfig multiModeConfig) {
        return fixedPool(multiModeConfig.getQuota().getArchiveMaxConcurrent(), "pixiv-archive-");
    }

    /**
     * 固定大小线程池：corePoolSize == maxPoolSize + 无界队列，稳定保持 {@code concurrency}
     * 个工作线程，超出的任务排队，降低同时打外部服务触发限流的概率。
     */
    private static TaskExecutor fixedPool(int concurrency, String threadNamePrefix) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(concurrency);
        executor.setMaxPoolSize(concurrency);
        executor.setThreadNamePrefix(threadNamePrefix);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.initialize();
        return executor;
    }
}
