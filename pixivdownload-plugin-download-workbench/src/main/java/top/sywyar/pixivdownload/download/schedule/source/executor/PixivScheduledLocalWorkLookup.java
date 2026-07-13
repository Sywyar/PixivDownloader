package top.sywyar.pixivdownload.download.schedule.source.executor;

import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkKey;
import top.sywyar.pixivdownload.schedule.snapshot.ScheduleTaskSnapshot;

/** Pixiv 来源发现使用的本地作品终态查询端口。 */
@FunctionalInterface
public interface PixivScheduledLocalWorkLookup {

    /**
     * 按任务保存时的下载设置判断作品是否已经完整存在于本地。
     *
     * <p>实现必须同时考虑 {@code workType + id}，并保持 {@code verifyFiles} 与
     * {@code redownloadDeleted} 的既有去重语义。
     */
    boolean isAlreadyCompleted(ScheduledWorkKey key, ScheduleTaskSnapshot.Download download);
}
