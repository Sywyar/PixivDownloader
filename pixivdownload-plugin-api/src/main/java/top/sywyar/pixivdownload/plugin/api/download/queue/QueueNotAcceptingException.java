package top.sywyar.pixivdownload.plugin.api.download.queue;

/** 下载队列已经进入清退，因而不能再接收新任务。 */
public final class QueueNotAcceptingException extends IllegalStateException {

    /**
     * 队列类型。
     */
    private final String queueType;

    /**
     * 创建 {@code QueueNotAcceptingException} 实例。
     *
     * @param queueType 队列类型
     */
    public QueueNotAcceptingException(String queueType) {
        super("queue is not accepting new tasks: " + requireQueueType(queueType));
        this.queueType = queueType;
    }

    /**
     * 返回队列类型。
     *
     * @return 方法返回的字符串
     */
    public String queueType() {
        return queueType;
    }

    private static String requireQueueType(String queueType) {
        if (queueType == null || queueType.isBlank()) {
            throw new IllegalArgumentException("queueType must not be blank");
        }
        return queueType;
    }
}
