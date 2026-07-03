package top.sywyar.pixivdownload.download.schedule.work;

import lombok.RequiredArgsConstructor;
import top.sywyar.pixivdownload.core.schedule.work.ScheduledIllustSettings;
import top.sywyar.pixivdownload.core.schedule.work.ScheduledIllustWork;
import top.sywyar.pixivdownload.core.schedule.work.ScheduledWork;
import top.sywyar.pixivdownload.core.schedule.work.ScheduledWorkKind;
import top.sywyar.pixivdownload.core.schedule.work.ScheduledWorkRunner;
import top.sywyar.pixivdownload.core.schedule.work.ScheduledWorkSettings;
import top.sywyar.pixivdownload.download.ArtworkDownloader;
import top.sywyar.pixivdownload.download.request.DownloadRequest;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;

/**
 * 插画 / 漫画 / 动图的计划任务下载执行器（{@link ScheduledWorkRunner#kind()} == {@link ScheduledWorkKind#ILLUST}）：
 * 把核心中性载体 {@link ScheduledIllustWork} + {@link ScheduledIllustSettings} 纯映射成
 * {@code DownloadRequest.Other} 并经核心窄接缝 {@link ArtworkDownloader#downloadImagesBlocking} 同步下载。
 *
 * <p>这是调度壳侧把现有 {@code ArtworkDownloader} 路径包成执行器的薄适配器：发现 / 抓元数据 / 服务端筛选 /
 * 系列富信息补全 / 图片 URL 解析 / 动图解析全部已在调度主线程串行完成并写入载体，本执行器只做字段搬运 + 阻塞下载，
 * 不发起任何抓取。sidecar 捕获仍由调度壳在下载成功后旁路完成，不在此承载。随下载工作台插件生命周期归属
 * （{@code @PluginManagedBean}，由 {@code DownloadWorkbenchPluginConfiguration} 显式装配），经
 * {@link top.sywyar.pixivdownload.core.schedule.work.ScheduledWorkRunnerRegistry} 注册。
 */
@PluginManagedBean
@RequiredArgsConstructor
public class ScheduledIllustWorkRunner implements ScheduledWorkRunner {

    private final ArtworkDownloader artworkDownloader;

    @Override
    public String kind() {
        return ScheduledWorkKind.ILLUST;
    }

    @Override
    public boolean download(ScheduledWork work, ScheduledWorkSettings settings, String cookie) {
        ScheduledIllustWork w = (ScheduledIllustWork) work;
        ScheduledIllustSettings s = (ScheduledIllustSettings) settings;
        DownloadRequest.Other other = new DownloadRequest.Other();
        other.setAuthorId(w.authorId());
        other.setAuthorName(w.authorName());
        other.setXRestrict(w.xRestrict());
        other.setAi(w.ai());
        other.setDescription(w.description());
        other.setTags(w.tags());
        other.setSeriesId(w.seriesId());
        other.setSeriesOrder(w.seriesOrder());
        other.setIllustType(w.illustType());
        other.setFileNameTemplate(s.fileNameTemplate());
        other.setBookmark(s.bookmark());
        other.setCollectionId(s.collectionId());
        // 图片间隔仅对多图插画有意义（下载器在相邻图片间 sleep）。
        other.setDelayMs(s.imageDelayMs() == null ? 0 : Math.max(0, s.imageDelayMs()));
        // 系列富信息（标题 + 简介 + 封面）：已由调度壳按非空过滤，null 即不设置（与 Other 默认一致）。
        if (w.seriesTitle() != null) {
            other.setSeriesTitle(w.seriesTitle());
        }
        if (w.seriesDescription() != null) {
            other.setSeriesDescription(w.seriesDescription());
        }
        if (w.seriesCoverUrl() != null) {
            other.setSeriesCoverUrl(w.seriesCoverUrl());
        }
        // 动图：解析所得的 ZIP URL + 帧延迟（仅动图作品）。
        if (w.ugoira()) {
            other.setUgoira(true);
            other.setUgoiraZipUrl(w.ugoiraZipUrl());
            other.setUgoiraDelays(w.ugoiraDelays());
        }
        return artworkDownloader.downloadImagesBlocking(
                w.artworkId(), w.title(), w.imageUrls(), w.referer(), other, cookie, null);
    }
}
