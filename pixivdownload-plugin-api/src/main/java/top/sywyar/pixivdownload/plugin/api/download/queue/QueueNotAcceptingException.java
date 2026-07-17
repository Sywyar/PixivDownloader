package top.sywyar.pixivdownload.plugin.api.download.queue;

/** 下载队列已经进入清退，因而不能再接收新任务。 */
public final class QueueNotAcceptingException extends IllegalStateException {

    private final String queueType;

    public QueueNotAcceptingException(String queueType) {
        super("queue is not accepting new tasks: " + requireQueueType(queueType));
        this.queueType = queueType;
    }

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
