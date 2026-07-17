package top.sywyar.pixivdownload.plugin.api.download.queue;

/**
 * 插件贡献的 owner-scoped 队列操作契约。
 *
 * <p>{@link #queueType()} 是注册中心内全局唯一的稳定路由键。下载作品类型通常与
 * {@code QueueTypeContribution.type()} 使用同一口径，但注册中心也承载已有的非下载后台队列；调用方不得假定
 * 每个操作都存在下载类型 descriptor。
 *
 * <p>{@link #clearAll()} 与 {@link #clearForOwner(String)} 是必选能力；单项取消是可选能力。清退方法的默认实现
 * 只适用于严格同步且无后台任务的操作。存在排队、执行器 handoff 或其它后台任务的实现必须覆写
 * {@link #prepareQuiesce(String)} 与 {@link #cancelQuiescedTasks()}，返回正代际真实 drain，并保证重复准备返回
 * 相同的 {@code queueType + generation}。
 */
public interface QueueOperations {

    /** 本操作承载的稳定队列路由键，在注册中心内全局唯一。 */
    String queueType();

    /**
     * 原子停止本操作实例接收新任务并返回真实退出的 drain；此步不得执行取消 callback。
     *
     * <p>{@code registeredQueueType} 是宿主在注册期恰好读取一次 {@link #queueType()} 后捕获的键。实现必须使用或
     * 校验这个参数，不得在 teardown 期间重新读取可能已不可安全调用的 getter。默认实现创建 generation=0 的
     * 已完成哨兵，只适用于严格同步且无后台任务的操作。
     */
    default QueueDrain prepareQuiesce(String registeredQueueType) {
        return QueueDrain.completed(registeredQueueType);
    }

    /** 调用方已经保存 {@link #prepareQuiesce(String)} 的 drain 后，向本代任务发送协作式取消。 */
    default void cancelQuiescedTasks() {
    }

    /**
     * 取消单件作品或任务。只有提供单项取消入口的实现才覆写；默认表示无此能力。
     *
     * <p>{@code workKey} 是队列类型内的不透明稳定键；宿主必须先按 {@link #queueType()}
     * 定向解析单个队列实现，不得把同一键广播给其它队列类型。实现可按自身领域校验键格式，
     * 但契约本身不得将它收窄为数字 ID。
     *
     * @param workKey  队列类型内的不透明稳定键
     * @param ownerUuid 归属 owner 的 UUID；管理员作用域可忽略，可为 {@code null}
     * @param admin     是否为管理员 / 全 owner 作用域
     */
    default void cancel(String workKey, String ownerUuid, boolean admin) {
    }

    /**
     * 强制清空该队列的全部在途或残留状态。
     *
     * @return 实际清空的条目数
     */
    int clearAll();

    /**
     * 强制清空归属某 owner 的队列状态。
     *
     * @param ownerUuid 归属 owner 的 UUID，可为 {@code null}
     * @return 实际清空的条目数
     */
    int clearForOwner(String ownerUuid);
}
