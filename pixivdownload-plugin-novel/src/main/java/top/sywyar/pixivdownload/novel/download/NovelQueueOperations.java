package top.sywyar.pixivdownload.novel.download;

import lombok.RequiredArgsConstructor;
import top.sywyar.pixivdownload.plugin.api.download.queue.QueueGenerationDrain;
import top.sywyar.pixivdownload.plugin.api.download.queue.QueueOperations;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;

/**
 * 将小说队列清空与生命周期清退委托给下载服务，随小说插件启停注册和撤回。
 * 单次执行的精确取消由任务登记器处理，本适配器不提供按作品键取消的能力。
 */
@PluginManagedBean
@RequiredArgsConstructor
public class NovelQueueOperations implements QueueOperations {

    private final NovelDownloadService novelDownloadService;

    @Override
    public String queueType() {
        return "novel";
    }

    @Override
    public QueueGenerationDrain prepareQuiesce(String registeredQueueType) {
        return novelDownloadService.prepareQuiesceRuntimeTasks();
    }

    @Override
    public void cancelQuiescedTasks() {
        novelDownloadService.cancelQuiescedRuntimeTasks();
    }

    @Override
    public int clearAll() {
        return novelDownloadService.forceClearDownloads();
    }

    @Override
    public int clearForOwner(String ownerUuid) {
        return novelDownloadService.forceClearDownloadsForOwner(ownerUuid);
    }
}
