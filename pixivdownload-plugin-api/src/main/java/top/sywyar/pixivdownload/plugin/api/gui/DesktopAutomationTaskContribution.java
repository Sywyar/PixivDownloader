package top.sywyar.pixivdownload.plugin.api.gui;

import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiNode.TextToken;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 可选插件 publication 发布的一条自动化任务展示纯值。
 *
 * <p>可信 owner 由宿主盖章；能力缺席、quiesce、替换或撤回时该投影自然缺席。该值只用于
 * best-effort 观察，不含写动作、定义正文、凭据、持久化行为或安全判断。
 */
public record DesktopAutomationTaskContribution(
        String taskId,
        int order,
        TextToken title,
        TextToken triggerSummary,
        Status status,
        LastResult lastResult,
        List<Instant> nextRuns,
        Instant observedAt
) {
    /** 自动化任务当前的稳定机器状态。 */
    public enum Status {
        /** 任务当前空闲。 */
        IDLE,
        /** 任务已进入队列。 */
        QUEUED,
        /** 任务正在执行。 */
        RUNNING,
        /** 任务已请求协作式取消。 */
        CANCEL_REQUESTED,
        /** 任务已挂起。 */
        SUSPENDED,
        /** 任务已禁用。 */
        DISABLED,
        /** 事实源无法映射为已知状态。 */
        UNKNOWN
    }

    /** 自动化任务最近一次运行的稳定结果。 */
    public enum LastResult {
        /** 尚未运行。 */
        NEVER,
        /** 最近一次运行成功。 */
        OK,
        /** 最近一次运行失败。 */
        ERROR,
        /** 最近一次运行已取消。 */
        CANCELLED,
        /** 最近一次运行被进程中断。 */
        INTERRUPTED,
        /** 事实源无法映射为已知结果。 */
        UNKNOWN
    }

    /**
     * 校验并规范化一条自动化任务展示纯值。
     *
     * @param taskId owner 内稳定任务 id
     * @param order owner 内排序值
     * @param title 任务标题
     * @param triggerSummary 受控触发摘要
     * @param status 当前机器状态
     * @param lastResult 最近运行结果
     * @param nextRuns 接下来 24 小时内的运行时间点
     * @param observedAt 事实观测时间
     */
    public DesktopAutomationTaskContribution {
        if (taskId == null || !taskId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new IllegalArgumentException("taskId must be a stable id");
        }
        title = Objects.requireNonNull(title, "title");
        triggerSummary = Objects.requireNonNull(triggerSummary, "triggerSummary");
        status = Objects.requireNonNull(status, "status");
        lastResult = Objects.requireNonNull(lastResult, "lastResult");
        nextRuns = List.copyOf(nextRuns == null ? List.of() : nextRuns);
        if (nextRuns.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("nextRuns must not contain null");
        }
        observedAt = Objects.requireNonNull(observedAt, "observedAt");
    }
}
