package top.sywyar.pixivdownload.novel.download;

import lombok.RequiredArgsConstructor;
import top.sywyar.pixivdownload.core.download.queue.QueueGenerationDrain;
import top.sywyar.pixivdownload.plugin.api.download.queue.QueueOperations;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;

/**
 * 小说作品类型的跨类型队列宿主操作适配器（{@link QueueOperations#queueType()} == {@code novel}）：把队列控制器需要的
 * 清空操作薄包到 {@link NovelDownloadService}。小说无单项取消入口（无 {@code /api/novel/cancel} 端点、服务亦无单项
 * 取消方法），故沿用 {@link QueueOperations#cancel} 的默认空实现——不为它编造新行为，与现状逐字一致。
 *
 * <p>随小说插件生命周期归属（{@code @PluginManagedBean}，由 {@code NovelPluginConfiguration} 经
 * {@code @ConditionalOnPluginEnabled("novel")} 显式装配）：小说插件被禁 / 卸载时本适配器缺席，注册中心解析不到
 * {@code novel} 操作，跨类型清空只作用于在场的作品类型——与 {@code novel} 队列类型从 {@code /api/download/extensions}
 * 消失同一条禁用语义。
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
        return novelDownloadService.prepareQuiesceDownloads();
    }

    @Override
    public void cancelQuiescedTasks() {
        novelDownloadService.cancelQuiescedDownloads();
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
