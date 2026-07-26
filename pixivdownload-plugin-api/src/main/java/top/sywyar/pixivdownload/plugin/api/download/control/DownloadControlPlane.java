package top.sywyar.pixivdownload.plugin.api.download.control;

import top.sywyar.pixivdownload.plugin.api.web.RequestOwnerIdentity;

import java.util.function.Supplier;

/**
 * 宿主提供的下载扩展查询与队列控制面。
 *
 * <p>插件只消费不可变扩展投影和稳定操作结果；宿主继续拥有 contribution registry、可信 owner 盖章、
 * publication currentness 与命令代理生命周期。调用方不得据此接口返回的数据推导或替代请求鉴权。
 */
public interface DownloadControlPlane {

    /** 返回当前下载类型与下载页 UI 槽位的单一不可变快照。 */
    DownloadExtensionSnapshot extensions();

    /**
     * 针对调用时的当前 descriptor publication 取消单项。
     *
     * <p>只用于不携带 publication 身份的兼容入口。请求 owner supplier 仅在 descriptor 预检、初次 operation
     * 解析和调用前 descriptor 复核通过后调用一次；命令对象的最终 currentness 仍由宿主在调用时校验。
     * 调用方必须从宿主的请求身份解析器取得该值。
     */
    DownloadQueueCancelResult cancelCurrent(
            String queueType,
            String workKey,
            Supplier<RequestOwnerIdentity> requestOwner);

    /**
     * 针对请求明确携带的 descriptor publication 取消单项。
     *
     * <p>宿主在捕获命令后、调用命令前再次复核精确 publication，并拒绝把旧请求改投 replacement。请求 owner supplier 的
     * 惰性调用语义与 {@link #cancelCurrent(String, String, Supplier)} 相同。
     */
    DownloadQueueCancelResult cancelExact(
            DownloadQueueCancelCommand command,
            Supplier<RequestOwnerIdentity> requestOwner);

    /** 按宿主解析出的请求 owner 作用域清空当前在场的全部队列，返回清除项数。 */
    int clearQueues(RequestOwnerIdentity requestOwner);
}
