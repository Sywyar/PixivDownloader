package top.sywyar.pixivdownload.novel.download;

import lombok.RequiredArgsConstructor;
import top.sywyar.pixivdownload.core.schedule.work.ScheduledNovelSettings;
import top.sywyar.pixivdownload.core.schedule.work.ScheduledNovelWork;
import top.sywyar.pixivdownload.core.schedule.work.ScheduledWork;
import top.sywyar.pixivdownload.core.schedule.work.ScheduledWorkKind;
import top.sywyar.pixivdownload.core.schedule.work.ScheduledWorkRunner;
import top.sywyar.pixivdownload.core.schedule.work.ScheduledWorkSettings;
import top.sywyar.pixivdownload.core.schedule.work.ScheduledWorkTranslateStatus;
import top.sywyar.pixivdownload.novel.export.NovelMergeService;
import top.sywyar.pixivdownload.novel.request.NovelDownloadRequest;
import top.sywyar.pixivdownload.novel.translation.NovelAutoTranslateService;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;

import java.io.IOException;

/**
 * 小说作品类型的计划任务下载执行器（{@link ScheduledWorkRunner#kind()} == {@link ScheduledWorkKind#NOVEL}）：
 * 把计划任务里「小说下载」一侧的全部 novel 包逻辑收口于此，使调度壳（{@code schedule} 包）不再 import 任何
 * novel 类型。承载三件事：
 * <ul>
 *   <li>{@link #download} —— 由中性载体 {@link ScheduledNovelWork} + {@link ScheduledNovelSettings} 组装
 *       {@link NovelDownloadRequest} 并经 {@link NovelDownloader#downloadBlocking} 同步下载；</li>
 *   <li>{@link #mergeSeries} —— 触发系列合订（{@link NovelMergeService#merge}）；</li>
 *   <li>{@link #translateStatus} —— 查询「下载即自动翻译」实时状态供队列视图叠加。</li>
 * </ul>
 * 发现 / 抓详情 / 服务端筛选 / 系列富信息补全 / sidecar 捕获 / 异常分类 / 限流 / 熔断 / 代理 / 运行队列
 * 仍由调度壳承载——本类只做「拿到中性数据后构造请求并下载」。
 *
 * <p><b>随小说插件生命周期归属</b>：本类标 {@code @PluginManagedBean}、排除出根包扫描，由
 * {@code NovelPluginConfiguration} 以 {@code @Bean} 显式装配，经
 * {@link top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityRegistry} 随 owner bundle 原子注册。小说插件被禁 / 卸载时
 * 本执行器随之缺席，调度壳解析不到 {@code novel} 执行器即把小说计划任务标记为不可用并干净挂起（不偷跑、不启动失败）。
 */
@PluginManagedBean
@RequiredArgsConstructor
public class ScheduledNovelDownloadDelegate implements ScheduledWorkRunner {

    private final NovelDownloader novelDownloader;
    private final NovelMergeService novelMergeService;
    private final NovelAutoTranslateService novelAutoTranslateService;

    @Override
    public String kind() {
        return ScheduledWorkKind.NOVEL;
    }

    @Override
    public boolean download(ScheduledWork work, ScheduledWorkSettings settings, String cookie) {
        ScheduledNovelWork w = (ScheduledNovelWork) work;
        ScheduledNovelSettings s = (ScheduledNovelSettings) settings;
        NovelDownloadRequest req = new NovelDownloadRequest();
        req.setNovelId(w.novelId());
        req.setTitle(w.title());
        req.setCookie(cookie);
        req.setContent(w.content());
        NovelDownloadRequest.Other o = new NovelDownloadRequest.Other();
        o.setAuthorId(w.authorId());
        o.setAuthorName(w.authorName());
        o.setXRestrict(w.xRestrict());
        o.setAi(w.ai());
        o.setOriginal(w.original());
        o.setLanguage(w.language());
        o.setWordCount(w.wordCount());
        o.setTextLength(w.textLength());
        o.setReadingTimeSeconds(w.readingTimeSeconds());
        o.setPageCount(w.pageCount());
        o.setDescription(w.description());
        o.setTags(w.tags());
        o.setSeriesId(w.seriesId());
        o.setSeriesOrder(w.seriesOrder());
        o.setSeriesTitle(w.seriesTitle());
        o.setUploadTimestamp(w.uploadTimestamp());
        o.setCoverUrl(w.coverUrl());
        o.setEmbeddedImages(w.embeddedImages());
        o.setFileNameTemplate(s.fileNameTemplate());
        o.setBookmark(s.bookmark());
        o.setCollectionId(s.collectionId());
        o.setFormat(s.format());
        // 下载即自动翻译（admin 身份运行，恒可触发）：翻译走服务端独立队列、不阻塞调度 tick；
        // 译文合订交由每本译完后的合订（沿用下载设置的「生成合订本」），与 web 链路一致。
        if (s.autoTranslate()) {
            o.setAutoTranslate(true);
            o.setAutoTranslateLanguage(s.autoTranslateLanguage());
            o.setAutoTranslateSegmentSize(s.autoTranslateSegmentSize());
            o.setAutoTranslateMerge(s.autoTranslateMerge());
            o.setAutoTranslateMergeFormat(s.autoTranslateMergeFormat());
        }
        // 系列富信息（简介 + 封面 + 系列标签）：已由调度壳按非空过滤，null 即不设置。
        if (w.seriesDescription() != null) {
            o.setSeriesDescription(w.seriesDescription());
        }
        if (w.seriesCoverUrl() != null) {
            o.setSeriesCoverUrl(w.seriesCoverUrl());
        }
        if (w.seriesTags() != null) {
            o.setSeriesTags(w.seriesTags());
        }
        req.setOther(o);
        return novelDownloader.downloadBlocking(req, null);
    }

    @Override
    public void mergeSeries(long seriesId, String mergeFormat) throws IOException {
        novelMergeService.merge(seriesId, NovelDownloadService.NovelFormat.parse(mergeFormat));
    }

    @Override
    public ScheduledWorkTranslateStatus translateStatus(long novelId) {
        NovelAutoTranslateService.StatusView tv = novelAutoTranslateService.getStatus(novelId);
        if (tv == null) {
            return null;
        }
        return new ScheduledWorkTranslateStatus(tv.phase(), tv.elapsedSeconds(), tv.seriesPending());
    }
}
