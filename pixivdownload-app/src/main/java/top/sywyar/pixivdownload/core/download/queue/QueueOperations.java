package top.sywyar.pixivdownload.core.download.queue;

/**
 * 下载队列中某一<b>作品类型</b>（插画 / 小说 ...）的跨类型队列宿主操作契约（核心 owned）。
 * 每个作品类型由一个操作适配器承载，经 {@link QueueOperationRegistry} 按 {@link #queueType()} 索引；
 * 下载队列控制器只依赖本接口与注册中心，不再直接依赖任一具体下载实现
 * （清偿队列控制器对插画 / 小说下载服务的硬编码反向耦合）。
 *
 * <p>与计划任务执行器契约 {@code core.schedule.work.ScheduledWorkRunner} 平行：调用方只持有核心接口，
 * 具体作品类型由贡献该类型的一侧实现并显式装配（插画操作住下载工作台，小说操作住小说插件、随小说插件生命周期归属）。
 * 某作品类型操作缺席（如对应插件被禁 / 卸载）时，{@link QueueOperationRegistry#resolve} 返回空、
 * 跨类型清空只作用于在场的作品类型——残留队列项的暂停 / 隐藏由前端据 {@code /api/download/extensions} 处理，
 * 后端不报错、不删数据。
 *
 * <p>清退、清空 / 按 owner 清空对所有作品类型都成立
 * （{@link #prepareQuiesce()} / {@link #cancelQuiescedTasks()} / {@link #clearAll()} /
 * {@link #clearForOwner(String)} 必须实现）；
 * 单项取消是可选能力（只有提供单项取消入口的作品类型才覆写 {@link #cancel(long, String, boolean)}，默认空实现），
 * 与 {@code ScheduledWorkRunner.mergeSeries / translateStatus} 的「按能力覆写默认方法」同构。
 */
public interface QueueOperations {

    /** 本适配器承载的作品类型路由键（与 {@code QueueTypeContribution.type()} 同口径，如 {@code illust} / {@code novel}），在注册中心内全局唯一。 */
    String queueType();

    /**
     * 原子停止本操作实例接收新任务并返回真实退出的 drain；此步不得执行插件 callback。
     * 重复调用必须返回同一 generation 的 drain；不得用清空状态 map 或线程池 activeCount 伪造归零。
     */
    QueueGenerationDrain prepareQuiesce();

    /** 调用方已经保存 {@link #prepareQuiesce()} 的 drain 后，向本代排队 / 运行任务发送协作式取消。 */
    void cancelQuiescedTasks();

    /**
     * 取消单件作品的下载。仅提供单项取消入口的作品类型才覆写；默认无能力（空实现）。
     *
     * @param workId    作品 ID
     * @param ownerUuid 归属 owner 的 UUID（{@code admin} 为 {@code true} 时忽略，可为 {@code null}）
     * @param admin     是否管理员 / solo 作用域（{@code true} 取消该作品在所有 owner 的下载、{@code false} 仅取消归属 owner 的）
     */
    default void cancel(long workId, String ownerUuid, boolean admin) {
    }

    /**
     * 强制清空该作品类型的全部在途 / 残留队列状态。
     *
     * @return 实际清空的条目数
     */
    int clearAll();

    /**
     * 强制清空归属某 owner 的该作品类型队列状态（multi 模式访客只清自己）。
     *
     * @param ownerUuid 归属 owner 的 UUID，可为 {@code null}
     * @return 实际清空的条目数
     */
    int clearForOwner(String ownerUuid);
}
