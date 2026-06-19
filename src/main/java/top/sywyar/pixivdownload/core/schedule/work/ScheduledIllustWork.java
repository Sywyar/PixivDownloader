package top.sywyar.pixivdownload.core.schedule.work;

import top.sywyar.pixivdownload.core.db.TagDto;

import java.util.List;

/**
 * 插画 / 漫画 / 动图的「一件待下载作品」中性载体（{@link ScheduledWork} 的 illust 变体，核心 owned、纯数据）。
 *
 * <p>调度壳在调度主线程串行完成抓元数据 / 服务端筛选 / 系列富信息补全 / 图片 URL 解析 / 动图解析后，把全部
 * 已解析结果拷进本载体；插画执行器（住调度壳、{@link ScheduledWorkRunner#kind()} == {@link ScheduledWorkKind#ILLUST}）
 * 只做纯映射——把本载体字段逐一搬进下载请求并阻塞下载，不再发起任何抓取。各字段含义与下载请求的同名字段一一对应：
 * 系列三字段（{@link #seriesTitle} / {@link #seriesDescription} / {@link #seriesCoverUrl}）由调度壳按「仅
 * seriesId 有效且非空白才填」过滤后写入，为 {@code null} 即「不设置」；动图三字段（{@link #ugoira} /
 * {@link #ugoiraZipUrl} / {@link #ugoiraDelays}）仅动图作品有效。
 */
public record ScheduledIllustWork(
        long artworkId,
        String title,
        Long authorId,
        String authorName,
        int xRestrict,
        boolean ai,
        String description,
        List<TagDto> tags,
        Long seriesId,
        Long seriesOrder,
        String seriesTitle,
        Integer illustType,
        String seriesDescription,
        String seriesCoverUrl,
        boolean ugoira,
        String ugoiraZipUrl,
        List<Integer> ugoiraDelays,
        List<String> imageUrls,
        String referer) implements ScheduledWork {

    @Override
    public String kind() {
        return ScheduledWorkKind.ILLUST;
    }

    @Override
    public long workId() {
        return artworkId;
    }
}
