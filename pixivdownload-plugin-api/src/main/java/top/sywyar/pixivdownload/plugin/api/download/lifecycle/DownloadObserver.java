package top.sywyar.pixivdownload.plugin.api.download.lifecycle;

/**
 * 可选的完整信任插件 Bean。宿主按精确 publication 发布和撤回，普通异常不影响下载。
 * 回调同步执行，必须快速返回；不得把通知当作持久化任务或鉴权依据。
 */
public interface DownloadObserver {
    /**
     * 接收即时通知；不同执行可并发调用，慢回调会延迟调用线程。
     * @param event 不可变执行通知
     */
    void onDownloadEvent(DownloadEvent event);
}
