package top.sywyar.pixivdownload.plugin.api.gui;

import java.time.Instant;
import java.util.Objects;

/**
 * 可选插件 publication 发布的一条只读运行任务事实。
 *
 * <p>可信 owner 由宿主盖章；能力缺席、quiesce、替换或撤回时该任务自然缺席。该值只用于
 * best-effort 展示，不是可执行任务句柄，不允许写入、取消或承担安全判断。
 */
public record DesktopRunningTaskContribution(
        String taskId,
        int order,
        DesktopUiText title,
        DesktopUiText supportingText,
        Status status,
        Double progress,
        DesktopControlCenterAvailability availability,
        Instant observedAt
) {
    /** 运行任务的稳定机器状态。 */
    public enum Status {
        /** 任务已进入队列。 */
        QUEUED,
        /** 任务正在执行。 */
        RUNNING,
        /** 任务正在准备执行。 */
        PREPARING,
        /** 任务正在完成收尾。 */
        FINALIZING,
        /** 事实源无法映射为已知状态。 */
        UNKNOWN
    }

    /**
     * 校验并规范化一条运行任务纯值。
     *
     * @param taskId owner 内稳定任务 id
     * @param order owner 内排序值
     * @param title 任务标题
     * @param supportingText 辅助说明
     * @param status 稳定机器状态
     * @param progress 可选进度，范围为 0 到 1
     * @param availability 可用性
     * @param observedAt 事实观测时间
     */
    public DesktopRunningTaskContribution {
        if (taskId == null || !taskId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new IllegalArgumentException("taskId must be a stable id");
        }
        title = Objects.requireNonNull(title, "title");
        supportingText = Objects.requireNonNull(supportingText, "supportingText");
        status = Objects.requireNonNull(status, "status");
        if (progress != null && (!Double.isFinite(progress) || progress < 0d || progress > 1d)) {
            throw new IllegalArgumentException("progress must be between 0 and 1");
        }
        availability = Objects.requireNonNull(availability, "availability");
        observedAt = Objects.requireNonNull(observedAt, "observedAt");
    }
}
