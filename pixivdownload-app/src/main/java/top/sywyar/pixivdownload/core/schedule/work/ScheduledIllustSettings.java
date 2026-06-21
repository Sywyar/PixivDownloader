package top.sywyar.pixivdownload.core.schedule.work;

/**
 * 插画计划任务下载设置（{@link ScheduledWorkSettings} 的 illust 变体，核心 owned、纯数据）。
 * 由调度壳从任务快照的 {@code download} 段映射而来，经 {@link ScheduledWorkRunner#download} 交给插画执行器
 * 组装下载请求。
 *
 * @param fileNameTemplate 文件名模板
 * @param bookmark         下载后是否收藏
 * @param collectionId     下载后归入的收藏集 ID（可空）
 * @param imageDelayMs     图片间隔（毫秒，多图相邻图片之间 sleep）；{@code null} / ≤0 不延迟
 */
public record ScheduledIllustSettings(
        String fileNameTemplate,
        boolean bookmark,
        Long collectionId,
        Integer imageDelayMs) implements ScheduledWorkSettings {

    @Override
    public String kind() {
        return ScheduledWorkKind.ILLUST;
    }
}
