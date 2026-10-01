package top.sywyar.pixivdownload.plugin.api.download.task;

import top.sywyar.pixivdownload.plugin.api.download.lifecycle.DownloadAttempt;
import top.sywyar.pixivdownload.plugin.api.download.lifecycle.DownloadEvent;
import java.time.Instant;

/**
 * 宿主持有的纯值任务快照，不含凭据、文件路径、owner UUID 或插件对象。
 * @param attempt 稳定的执行身份
 * @param queueType 下载页类型键；尚在准备时为空
 * @param title 安全展示标题；未知时使用作品键
 * @param phase 当前阶段
 * @param updatedAt 最近状态变化时间
 * @param revision 当前进程内的状态版本
 */
public record DownloadTaskSnapshot(DownloadAttempt attempt, String queueType, String title,
                                   DownloadEvent.Phase phase, Instant updatedAt, long revision) {
    /** @return 是否已经终结 */
    public boolean terminal() {
        return phase == DownloadEvent.Phase.COMPLETED || phase == DownloadEvent.Phase.FAILED
                || phase == DownloadEvent.Phase.CANCELLED;
    }
}
