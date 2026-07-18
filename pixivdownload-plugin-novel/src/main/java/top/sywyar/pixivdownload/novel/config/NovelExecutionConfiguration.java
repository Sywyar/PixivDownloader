package top.sywyar.pixivdownload.novel.config;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import top.sywyar.pixivdownload.novel.NovelPlugin;
import top.sywyar.pixivdownload.plugin.ConditionalOnPluginEnabled;

/** novel 子上下文拥有的执行设置与重任务线程池。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnPluginEnabled(NovelPlugin.ID)
public class NovelExecutionConfiguration {

    @Bean
    public NovelExecutionSettings novelExecutionSettings(Environment environment) {
        return Binder.get(environment)
                .bind(NovelExecutionSettings.PREFIX, Bindable.of(NovelExecutionSettings.class))
                .orElseGet(NovelExecutionSettings::new);
    }

    @Bean("novelDownloadTaskExecutor")
    public ThreadPoolTaskExecutor novelDownloadTaskExecutor(NovelExecutionSettings settings) {
        return fixedPool(settings.getNovelMaxConcurrent(), "pixiv-novel-");
    }

    @Bean("novelTranslateTaskExecutor")
    public ThreadPoolTaskExecutor novelTranslateTaskExecutor(NovelExecutionSettings settings) {
        return fixedPool(settings.getNovelTranslateMaxConcurrent(), "pixiv-novel-tr-");
    }

    private static ThreadPoolTaskExecutor fixedPool(int concurrency, String threadNamePrefix) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(concurrency);
        executor.setMaxPoolSize(concurrency);
        executor.setThreadNamePrefix(threadNamePrefix);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        return executor;
    }
}
