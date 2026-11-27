package top.sywyar.pixivdownload.plugin.api.download.lifecycle;

import java.util.Objects;

/**
 * 同步、非持久化的执行通知。ACCEPTED 表示开始接纳；QUEUED 表示已登记到后端队列；
 * STARTED 表示开始处理，不承诺已经开始网络传输；
 * COMPLETED 表示文件及历史已写入，辅助收藏等后置动作可以尚未完成。
 * 不重放、不承诺进程崩溃时的终态交付，也不代表浏览器队列状态。
 * @param attempt 本次执行身份
 * @param failureReason 安全失败原因；非失败事件为 null
 * @param phase 已到达的执行阶段
 */
public record DownloadEvent(DownloadAttempt attempt, Phase phase, FailureReason failureReason) {
    /** 不携带凭据或底层异常的失败原因。 */
    public enum FailureReason {
        /** 当前选择的凭据均不可用，保留队列等待恢复。 */
        CREDENTIALS_UNAVAILABLE,
        /** 已有部分输出，须沿用原选页设置或显式要求整作替换。 */
        OUTPUT_LAYOUT_CONFLICT,
        /** 部分覆盖会与已登记的完整作品冲突。 */
        OUTPUT_ALREADY_REGISTERED,
        /** 创建、替换或清理输出时遇到未登记的文件、其他作品的引用或并发变更。 */
        OUTPUT_FILE_CONFLICT,
        /** 提交期间，提供方未找到请求的作品。 */
        WORK_NOT_FOUND,
        /** 提供方在提交时遇到来源限流。 */
        UPSTREAM_RATE_LIMITED
    }
    /**
     * 构造没有附加失败原因的事件。
     * @param attempt 本次执行身份
     * @param phase 已到达的执行阶段
     */
    public DownloadEvent(DownloadAttempt attempt, Phase phase) { this(attempt, phase, null); }

    /** 当前执行器与本地导入公开的阶段。 */
    public enum Phase {
        /** 已开始接纳或同步执行，尚不代表已排队。 */
        ACCEPTED,
        /** 后端已登记可跟踪的排队任务；后续调度拒绝会进入失败终态。 */
        QUEUED,
        /** 开始处理；不保证已开始网络传输。 */
        STARTED,
        /** 必需文件和历史已写入。 */
        COMPLETED,
        /** 未完成必要处理。 */
        FAILED,
        /** 执行取消或导入发现并发的已有记录。 */
        CANCELLED
    }
    /**
     * 拒绝空身份和空阶段。
     * @param attempt 本次执行身份
     * @param phase 已到达的执行阶段
     */
    public DownloadEvent {
        Objects.requireNonNull(attempt, "attempt");
        Objects.requireNonNull(phase, "phase");
        if (failureReason != null && phase != Phase.FAILED) throw new IllegalArgumentException("failure reason requires FAILED");
    }
}
