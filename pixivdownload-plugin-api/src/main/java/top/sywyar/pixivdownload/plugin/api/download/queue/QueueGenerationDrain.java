package top.sywyar.pixivdownload.plugin.api.download.queue;

/**
 * 单个队列操作实例停止接收任务后的归零凭据。凭据只持有队列标量身份与宿主计数状态；
 * 任务全部退出后不保留插件 Bean、任务 delegate、状态对象或异常对象。
 */
public final class QueueGenerationDrain implements QueueDrain {

    private final String queueType;
    private final long generation;
    private final QueueTaskTracker.State state;

    QueueGenerationDrain(String queueType, long generation, QueueTaskTracker.State state) {
        if (generation <= QueueDrain.COMPLETED_GENERATION) {
            throw new IllegalArgumentException("tracked queue generation must be positive");
        }
        this.queueType = queueType;
        this.generation = generation;
        this.state = state;
    }

    @Override
    public String queueType() {
        return queueType;
    }

    @Override
    public long generation() {
        return generation;
    }

    /** 等到绝对 {@link System#nanoTime()} 截止值；中断时恢复中断标志并返回 {@code false}。 */
    @Override
    public boolean awaitDrained(long deadlineNanos) {
        return state.awaitDrained(deadlineNanos);
    }

    /** 无截止时间等待；中断时恢复中断标志并返回 {@code false}。 */
    @Override
    public boolean awaitDrained() {
        return state.awaitDrained();
    }

    @Override
    public boolean isDrained() {
        return state.isDrained();
    }

    @Override
    public int activeCount() {
        return state.activeTaskCount();
    }
}
