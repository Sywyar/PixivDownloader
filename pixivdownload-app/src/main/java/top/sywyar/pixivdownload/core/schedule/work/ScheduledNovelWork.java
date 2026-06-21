package top.sywyar.pixivdownload.core.schedule.work;

import top.sywyar.pixivdownload.core.db.TagDto;

import java.util.List;
import java.util.Map;

/**
 * 小说的「一件待下载作品」中性载体（{@link ScheduledWork} 的 novel 变体，核心 owned、纯数据）。
 *
 * <p>计划任务的小说下载分两侧：调度壳（下载工作台）负责发现 / 抓详情 / 服务端筛选 / 系列富信息补全 /
 * sidecar 捕获 / 异常分类 / 运行队列；真正构造 {@code NovelDownloadRequest} 并落盘下载是小说插件的职责。
 * 两侧都依赖核心，故本载体落在 {@code core.schedule.work}：调度壳把已抓到的小说详情（及已补全、已按
 * 非空过滤的系列富信息）拷进本载体，经 {@link ScheduledWorkRunner#download} 交给小说执行器
 * （{@link ScheduledWorkRunner#kind()} == {@link ScheduledWorkKind#NOVEL}），从而避免调度壳反向 import 小说包、
 * 也避免小说包反向 import 下载包的 fetch 结果类型。
 *
 * <p>系列富信息三个字段（{@link #seriesDescription} / {@link #seriesCoverUrl} / {@link #seriesTags}）
 * 由调度壳<b>按「非空白 / 非空集合」过滤后</b>填入：为 {@code null} 即表示「不设置」，下载侧据此原样跳过。
 */
public record ScheduledNovelWork(
        long novelId,
        String title,
        String content,
        Long authorId,
        String authorName,
        int xRestrict,
        boolean ai,
        boolean original,
        String language,
        Integer wordCount,
        Integer textLength,
        Integer readingTimeSeconds,
        Integer pageCount,
        String description,
        List<TagDto> tags,
        Long seriesId,
        Long seriesOrder,
        String seriesTitle,
        Long uploadTimestamp,
        String coverUrl,
        Map<String, String> embeddedImages,
        String seriesDescription,
        String seriesCoverUrl,
        List<TagDto> seriesTags) implements ScheduledWork {

    @Override
    public String kind() {
        return ScheduledWorkKind.NOVEL;
    }

    @Override
    public long workId() {
        return novelId;
    }
}
