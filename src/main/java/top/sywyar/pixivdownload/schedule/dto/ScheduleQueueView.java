package top.sywyar.pixivdownload.schedule.dto;

import java.util.List;

/**
 * 计划任务<b>最近一轮运行队列</b>的对外视图（管理员专用）。
 *
 * <p>来自内存中的 {@link top.sywyar.pixivdownload.schedule.ScheduleRunQueue}，不落库。任务从未运行
 * （或进程重启后）时 {@code startedTime=null}、{@code items} 为空——此时前端保留本地缓存继续展示，
 * 直到任务再次运行刷新。{@code truncated} 表示本轮发现的作品数超过登记上限、列表只含前若干条。
 */
public record ScheduleQueueView(
        long taskId,
        Long startedTime,
        boolean truncated,
        int total,
        List<Item> items
) {
    /**
     * 队列条目。{@code status} ∈ pending / downloaded / skipped-downloaded / skipped-filter / failed；
     * {@code title} / {@code xRestrict} / {@code ai} 在抓到元数据前（如「已存在跳过」的作品）可能为 {@code null}。
     *
     * <p>{@code translatePhase} 等三项为「下载即自动翻译」的实时状态叠加（仅小说、且该项已提交翻译时非空，
     * 读取时从服务端翻译队列实时取，故 {@code translateElapsedSeconds} 随轮询递增）：{@code translatePhase}
     * 为原始枚举名（前端本地化），{@code translateSeriesPending} 为同系列待译前序数。
     */
    public record Item(
            String id,
            String title,
            String kind,
            Integer xRestrict,
            Boolean ai,
            String status,
            String message,
            String translatePhase,
            Long translateElapsedSeconds,
            Integer translateSeriesPending
    ) {
    }
}
