package top.sywyar.pixivdownload.plugin.api.download.lifecycle;

import top.sywyar.pixivdownload.plugin.api.download.task.DownloadTaskProgress;

/** 宿主必需门面；插件实现由宿主管理，消费者不得缓存插件 Bean。 */
public interface DownloadLifecycle {
    /**
     * 登记同步执行事实；来源 owner 只用于查询隔离，不进入公开事件。
     * @param attempt 执行身份
     * @param ownerUuid 用户作用域，管理员为 null
     * @param queueType 下载页类型；导入等无下载页类型时为空
     * @param title 展示标题
     */
    void register(DownloadAttempt attempt, String ownerUuid, String queueType, String title);

    /**
     * 绑定精确队列句柄；排队取消、调度拒绝和执行退出都必须产生终态。
     * @param attempt 执行身份
     * @param task 真实队列包装器
     * @param ownerUuid 用户作用域，管理员为 null
     * @param queueType 下载页类型键
     * @param title 展示标题
     * @param queued 是否正在排队；同步执行传 false
     */
    void track(DownloadAttempt attempt,
               top.sywyar.pixivdownload.plugin.api.download.queue.QueueTaskTracker.Task task,
               String ownerUuid, String queueType, String title, boolean queued);

    /**
     * 登记队列句柄及本次执行在服务器上的最终输出目录。目录在排队前由提供方解析，
     * 不表示文件已写入；该路径仅进入按身份查询的任务回执，不广播给事件观察者。
     * @param attempt 执行身份
     * @param task 真实队列包装器
     * @param ownerUuid 用户作用域
     * @param queueType 下载页类型键
     * @param title 展示标题
     * @param queued 是否正在排队
     * @param outputDirectory 提供方实际使用的绝对目录
     */
    default void track(DownloadAttempt attempt,
                       top.sywyar.pixivdownload.plugin.api.download.queue.QueueTaskTracker.Task task,
                       String ownerUuid, String queueType, String title, boolean queued, java.nio.file.Path outputDirectory) {
        throw new UnsupportedOperationException("Task output directories are not supported");
    }

    /**
     * 报送本次执行已绑定队列句柄的实际进度。
     * 已终结执行的迟到更新会被丢弃。
     * @param attempt 被跟踪的执行身份
     * @param task 本次执行绑定的原始队列句柄
     * @param progress 当前步骤的计数与单位
     */
    default void reportProgress(DownloadAttempt attempt, top.sywyar.pixivdownload.plugin.api.download.queue.QueueTaskTracker.Task task,
                                DownloadTaskProgress progress) {
        throw new UnsupportedOperationException("Task progress reporting is not supported");
    }

    /**
     * 同步调用可选的选项扩展；无扩展返回不可变原选项。
     * @param attempt 执行身份
     * @param options 类型明确允许修改的选项，不含凭据或路径授权
     * @return 经通用大小校验的选项，调用方仍须执行类型校验
     * @throws DownloadAdmissionRejectedException 扩展拒绝、失败或撤回
     */
    java.util.Map<String, String> options(DownloadAttempt attempt, java.util.Map<String, String> options);

    /**
     * 同步执行当前规则；无规则时允许。
     * @param attempt 待接纳的执行身份
     * @throws DownloadAdmissionRejectedException 规则拒绝或不可用，调用方必须停止副作用
     */
    void checkAdmission(DownloadAttempt attempt);
    /**
     * 尽力通知，不改变已经成立的业务事实；普通观察异常隔离，VM 错误继续抛出。
     * @param event 已发生的执行事实
     */
    void publish(DownloadEvent event);
}
