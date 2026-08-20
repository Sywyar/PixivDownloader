package top.sywyar.pixivdownload.plugin.api.gui;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 一个可选插件 publication 的首页卡片与运行任务同代只读快照。
 *
 * <p>快照不携带可信 owner；宿主从当前 publication 盖章、校验并复制。缺席或撤回不会删除任何
 * 持久化事实，也不得被解释为业务空结果之外的写入结论。
 */
public record DesktopDashboardSnapshot(
        List<DesktopDashboardCardContribution> cards,
        List<DesktopRunningTaskContribution> runningTasks,
        Instant observedAt
) {
    /**
     * 防御性复制一个 publication 的同代首页快照。
     *
     * @param cards 独立指标卡列表
     * @param runningTasks 运行任务列表
     * @param observedAt 快照观测时间
     */
    public DesktopDashboardSnapshot {
        cards = List.copyOf(cards == null ? List.of() : cards);
        runningTasks = List.copyOf(runningTasks == null ? List.of() : runningTasks);
        observedAt = Objects.requireNonNull(observedAt, "observedAt");
    }
}
