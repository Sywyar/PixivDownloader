package top.sywyar.pixivdownload.download;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import top.sywyar.pixivdownload.core.download.queue.QueueOperations;
import top.sywyar.pixivdownload.download.schedule.work.ScheduledIllustWorkRunner;

/**
 * 下载工作台插件的 Bean 装配收敛点。当前承载下载工作台作为<b>计划任务来源 / 作品类型执行器贡献方</b>提供给计划任务
 * 宿主插件的插画执行器：它经 {@code @PluginManagedBean} 排除出根包扫描，由这里以 {@code @Bean} 显式提供
 * （对标其它插件的收敛形态）。
 * <p>
 * 下载执行机器（{@code ArtworkDownloadExecutor} 等）住 download 包但属核心、仍走根包扫描，不在此装配。
 * <p>
 * <b>计划任务调度安全壳归计划任务宿主插件（{@code schedule}）：</b>执行器 / 服务 / tick runner / 控制器 / 运行
 * 状态 / 运行队列 / 过度访问告警等引擎 Bean 与 {@code /api/schedule/**} 路由由
 * {@code ScheduleHostPluginConfiguration} / {@code ScheduleHostPlugin} 装配与声明，不在本配置。下载工作台只贡献
 * 计划任务来源（{@link DownloadWorkbenchPlugin#scheduledSources()}）与下面的插画作品类型执行器，二者经核心契约
 * {@code core.schedule.work.ScheduledWorkRunner} + 注册中心、来源注册中心被宿主调度壳解析后派发。
 * <p>
 * 下载工作台是<b>必选插件</b>（{@link DownloadWorkbenchPlugin#required()} 返回 {@code true}），不可经
 * {@code plugins.download-workbench.enabled} 禁用，故插画执行器恒装配（不标 {@code @ConditionalOnPluginEnabled}）。
 */
@Configuration
public class DownloadWorkbenchPluginConfiguration {

    @Bean
    public DownloadWorkbenchPlugin downloadWorkbenchPlugin() {
        return new DownloadWorkbenchPlugin();
    }

    /** 插画作品类型的计划任务下载执行器（薄包核心窄接缝 {@code ArtworkDownloader}），经注册中心按 kind 解析。 */
    @Bean
    public ScheduledIllustWorkRunner scheduledIllustWorkRunner(ArtworkDownloader artworkDownloader) {
        return new ScheduledIllustWorkRunner(artworkDownloader);
    }

    /** 插画作品类型的跨类型队列宿主操作适配器（取消 / 清空），经核心队列宿主注册中心按 queueType 解析。 */
    @Bean
    public QueueOperations illustQueueOperations(ArtworkDownloadExecutor artworkDownloadExecutor) {
        return new IllustQueueOperations(artworkDownloadExecutor);
    }
}
