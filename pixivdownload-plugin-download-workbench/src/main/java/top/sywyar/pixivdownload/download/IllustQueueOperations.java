package top.sywyar.pixivdownload.download;

import lombok.RequiredArgsConstructor;
import top.sywyar.pixivdownload.core.download.queue.QueueGenerationDrain;
import top.sywyar.pixivdownload.plugin.api.download.queue.QueueOperations;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;

/**
 * 插画作品类型的跨类型队列宿主操作适配器（{@link QueueOperations#queueType()} == {@code illust}）：把队列控制器需要的
 * 取消 / 清空操作薄包到下载工作台自有的 {@link ArtworkDownloadExecutor}。插画类型支持单项取消，故覆写
 * {@link #cancel}；清空 / 按 owner 清空委托执行器既有方法。
 *
 * <p>随下载工作台插件生命周期归属（{@code @PluginManagedBean}，由 {@code DownloadWorkbenchPluginConfiguration} 显式装配、
 * 排除出根包扫描），经 {@link top.sywyar.pixivdownload.core.download.queue.QueueOperationRegistry} 注册。下载工作台是必选
 * 插件、恒在场，故插画队列操作恒可解析。
 */
@PluginManagedBean
@RequiredArgsConstructor
public class IllustQueueOperations implements QueueOperations {

    private final ArtworkDownloadExecutor artworkDownloadExecutor;

    @Override
    public String queueType() {
        return "illust";
    }

    @Override
    public QueueGenerationDrain prepareQuiesce(String registeredQueueType) {
        return artworkDownloadExecutor.prepareQuiesceDownloads();
    }

    @Override
    public void cancelQuiescedTasks() {
        artworkDownloadExecutor.cancelQuiescedDownloads();
    }

    @Override
    public void cancel(String workKey, String ownerUuid, boolean admin) {
        Long workId = numericWorkId(workKey);
        if (workId == null) {
            return;
        }
        // 执行器三参重载内部据 admin 分流：admin 取消该作品在所有 owner 的下载、否则仅取消归属 owner 的。
        artworkDownloadExecutor.cancelDownload(workId, ownerUuid, admin);
    }

    @Override
    public int clearAll() {
        return artworkDownloadExecutor.forceClearDownloads();
    }

    @Override
    public int clearForOwner(String ownerUuid) {
        return artworkDownloadExecutor.forceClearDownloadsForOwner(ownerUuid);
    }

    private static Long numericWorkId(String workKey) {
        if (workKey == null || !workKey.matches("[0-9]{1,18}")) {
            return null;
        }
        try {
            return Long.parseLong(workKey);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
