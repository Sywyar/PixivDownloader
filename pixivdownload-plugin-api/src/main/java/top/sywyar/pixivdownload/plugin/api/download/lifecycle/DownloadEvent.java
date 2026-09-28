package top.sywyar.pixivdownload.plugin.api.download.lifecycle;

import java.util.Objects;

/**
 * 同步、非持久化的执行通知。ACCEPTED 表示进入执行器；STARTED 表示开始处理；
 * COMPLETED 表示文件及历史已写入，辅助收藏等后置动作可以尚未完成。
 * 不重放、不承诺进程崩溃时的终态交付，也不代表浏览器队列状态。
 * @param attempt 本次执行身份
 * @param phase 已到达的执行阶段
 */
public record DownloadEvent(DownloadAttempt attempt, Phase phase) {
    /** 当前执行器与本地导入公开的阶段。 */
    public enum Phase {
        /** 执行器已登记任务；执行通道仍可能拒绝调度。 */
        ACCEPTED,
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
    }
}
