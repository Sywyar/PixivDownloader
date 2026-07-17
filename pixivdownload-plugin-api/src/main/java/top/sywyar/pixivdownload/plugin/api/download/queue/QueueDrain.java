package top.sywyar.pixivdownload.plugin.api.download.queue;

/**
 * 单个队列操作实例停止接收新任务后的归零凭据。
 *
 * <p>实现必须只暴露稳定的队列身份、代际与等待状态。生命周期会在取消任务前保存本凭据，并在关闭插件
 * child context 前等待它真实归零。实现不得用线程池 {@code activeCount}、清空状态 map 或提前完成的
 * {@code Future} 伪造退出。
 *
 * <p>{@link #COMPLETED_GENERATION generation 0} 保留给 {@link #completed(String)} 创建的无后台任务哨兵。
 * 任何可能越过当前同步调用栈继续运行任务的实现都必须使用正代际，并让同一操作实例重复准备时返回相同的
 * {@code queueType + generation}；新操作实例必须使用新代际。
 */
public interface QueueDrain {

    /** 同步、无后台任务队列的已完成哨兵代际。 */
    long COMPLETED_GENERATION = 0L;

    /** 注册中心捕获的稳定队列键。 */
    String queueType();

    /** 本操作实例的代际；0 仅表示 {@link #completed(String)} 的无后台任务哨兵。 */
    long generation();

    /** 等到绝对 {@link System#nanoTime()} 截止值；中断时恢复中断标志并返回 {@code false}。 */
    boolean awaitDrained(long deadlineNanos);

    /** 无截止时间等待；中断时恢复中断标志并返回 {@code false}。 */
    boolean awaitDrained();

    /** 当前代际是否已经没有任何排队或运行任务。 */
    boolean isDrained();

    /** 当前代际仍在排队或运行的任务数。 */
    int activeCount();

    /**
     * 为严格同步且没有后台任务的操作创建立即归零凭据。
     *
     * <p>该工厂只适用于任务不会越过当前调用栈继续运行的实现。异步实现必须返回能等待真实任务退出的
     * 正代际凭据，不能用本工厂绕过插件清退。
     */
    static QueueDrain completed(String queueType) {
        if (queueType == null || queueType.isBlank()) {
            throw new IllegalArgumentException("queueType must not be blank");
        }
        return new QueueDrain() {
            @Override
            public String queueType() {
                return queueType;
            }

            @Override
            public long generation() {
                return COMPLETED_GENERATION;
            }

            @Override
            public boolean awaitDrained(long deadlineNanos) {
                return true;
            }

            @Override
            public boolean awaitDrained() {
                return true;
            }

            @Override
            public boolean isDrained() {
                return true;
            }

            @Override
            public int activeCount() {
                return 0;
            }
        };
    }
}
