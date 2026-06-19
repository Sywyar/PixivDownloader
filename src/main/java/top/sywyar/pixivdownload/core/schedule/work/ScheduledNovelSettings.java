package top.sywyar.pixivdownload.core.schedule.work;

/**
 * 小说计划任务下载设置（{@link ScheduledWorkSettings} 的 novel 变体，核心 owned、纯数据）。由调度壳从任务快照的
 * {@code download} 段映射而来，经 {@link ScheduledWorkRunner#download} 交给小说执行器组装
 * {@code NovelDownloadRequest.Other}。
 *
 * <p>自动翻译相关四个字段（{@link #autoTranslateLanguage} / {@link #autoTranslateSegmentSize} /
 * {@link #autoTranslateMerge} / {@link #autoTranslateMergeFormat}）<b>仅当 {@link #autoTranslate} 为真时
 * 才被下载侧应用</b>。
 */
public record ScheduledNovelSettings(
        String fileNameTemplate,
        boolean bookmark,
        Long collectionId,
        String format,
        boolean autoTranslate,
        String autoTranslateLanguage,
        Integer autoTranslateSegmentSize,
        boolean autoTranslateMerge,
        String autoTranslateMergeFormat) implements ScheduledWorkSettings {

    @Override
    public String kind() {
        return ScheduledWorkKind.NOVEL;
    }
}
