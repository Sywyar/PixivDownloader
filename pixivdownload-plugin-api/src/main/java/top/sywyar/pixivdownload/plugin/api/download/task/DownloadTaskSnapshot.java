package top.sywyar.pixivdownload.plugin.api.download.task;

import top.sywyar.pixivdownload.plugin.api.download.lifecycle.DownloadAttempt;
import top.sywyar.pixivdownload.plugin.api.download.lifecycle.DownloadEvent;
import java.time.Instant;

/**
 * 宿主持有的纯值任务快照，仅向可见身份返回，不含凭据、owner UUID 或插件对象。
 * @param attempt 稳定的执行身份
 * @param queueType 下载页类型键；尚在准备时为空
 * @param title 安全展示标题；未知时使用作品键
 * @param phase 当前阶段
 * @param updatedAt 最近状态变化时间
 * @param revision 当前进程内的状态版本
 * @param failureReason 安全失败原因；无附加原因时为 null
 * @param progress 本次执行最近测得的进度，尚无测量时为 null
 * @param outputPath 执行时确定的服务器绝对输出目录；尚未知或未报告时为 null
 */
public record DownloadTaskSnapshot(DownloadAttempt attempt, String queueType, String title,
                                   DownloadEvent.Phase phase, Instant updatedAt, long revision,
                                   DownloadEvent.FailureReason failureReason, String outputPath,
                                   DownloadTaskProgress progress) {
    /**
     * 创建未报告进度的任务快照。
     * @param attempt 本次执行身份
     * @param queueType 队列类型键
     * @param title 展示标题
     * @param phase 生命周期阶段
     * @param updatedAt 最近更新时间
     * @param revision 更新时间对应的进程版本
     * @param failureReason 执行器报告的失败原因
     * @param outputPath 已知的输出目录
     */
    public DownloadTaskSnapshot(DownloadAttempt attempt, String queueType, String title,
                                DownloadEvent.Phase phase, Instant updatedAt, long revision,
                                DownloadEvent.FailureReason failureReason, String outputPath) {
        this(attempt, queueType, title, phase, updatedAt, revision, failureReason, outputPath, null);
    }

    /**
     * 创建没有输出目录的任务快照。
     * @param attempt 本次执行身份
     * @param queueType 下载页类型键
     * @param title 安全展示标题
     * @param phase 当前阶段
     * @param updatedAt 最近状态变化时间
     * @param revision 当前进程内的状态版本
     * @param failureReason 执行器报告的失败原因
     */
    public DownloadTaskSnapshot(DownloadAttempt attempt, String queueType, String title,
                                DownloadEvent.Phase phase, Instant updatedAt, long revision,
                                DownloadEvent.FailureReason failureReason) {
        this(attempt, queueType, title, phase, updatedAt, revision, failureReason, null);
    }

    /**
     * 构造不含附加失败原因的快照。
     * @param attempt 本次执行身份
     * @param queueType 下载页类型键
     * @param title 安全展示标题
     * @param phase 当前阶段
     * @param updatedAt 最近状态变化时间
     * @param revision 当前进程内的状态版本
     */
    public DownloadTaskSnapshot(DownloadAttempt attempt, String queueType, String title,
                                DownloadEvent.Phase phase, Instant updatedAt, long revision) {
        this(attempt, queueType, title, phase, updatedAt, revision, null);
    }
    /** 校验失败原因只能用于失败终态。 */
    public DownloadTaskSnapshot {
        if (outputPath != null && (outputPath.isBlank() || outputPath.length() > 32_767 || outputPath.indexOf(0) >= 0))
            throw new IllegalArgumentException("Invalid task output directory");
        if (failureReason != null && phase != DownloadEvent.Phase.FAILED)
            throw new IllegalArgumentException("failure reason requires FAILED");
    }

    /**
     * {@return 是否已经终结}
     */
    public boolean terminal() {
        return phase == DownloadEvent.Phase.COMPLETED || phase == DownloadEvent.Phase.FAILED
                || phase == DownloadEvent.Phase.CANCELLED;
    }
}
