package top.sywyar.pixivdownload.plugin.api.gui;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 一个可选插件 publication 的自动化任务同代只读快照。
 *
 * <p>快照不携带可信 owner；宿主从当前 publication 盖章、校验并复制。缺席或撤回不删除任务、
 * checkpoint、凭据或其它持久化事实，也不代表任何自动化写操作成功。
 */
public record DesktopAutomationSnapshot(
        List<DesktopAutomationTaskContribution> tasks,
        DesktopControlCenterAvailability availability,
        Instant observedAt
) {
    /**
     * 防御性复制一个 publication 的同代自动化快照。
     *
     * @param tasks 自动化任务列表
     * @param availability 调度器整体可用性
     * @param observedAt 快照观测时间
     */
    public DesktopAutomationSnapshot {
        tasks = List.copyOf(tasks == null ? List.of() : tasks);
        availability = Objects.requireNonNull(availability, "availability");
        observedAt = Objects.requireNonNull(observedAt, "observedAt");
    }
}
