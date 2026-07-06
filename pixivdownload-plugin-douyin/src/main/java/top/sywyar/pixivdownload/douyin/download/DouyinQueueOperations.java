package top.sywyar.pixivdownload.douyin.download;

import top.sywyar.pixivdownload.core.download.queue.QueueOperations;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;

@PluginManagedBean
public class DouyinQueueOperations implements QueueOperations {

    private final DouyinDownloadService downloadService;

    public DouyinQueueOperations(DouyinDownloadService downloadService) {
        this.downloadService = downloadService;
    }

    @Override
    public String queueType() {
        return DouyinDownloadService.QUEUE_TYPE;
    }

    @Override
    public void cancel(long workId, String ownerUuid, boolean admin) {
        downloadService.cancel(workId, ownerUuid, admin);
    }

    @Override
    public int clearAll() {
        return downloadService.clearAll();
    }

    @Override
    public int clearForOwner(String ownerUuid) {
        return downloadService.clearForOwner(ownerUuid);
    }
}
