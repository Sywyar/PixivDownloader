package top.sywyar.pixivdownload.core.download.queue;

/** Host-owned signal that an exact queue-operation publication cannot accept a control-plane call. */
public final class QueueOperationUnavailableException extends IllegalStateException {

    public QueueOperationUnavailableException(String message) {
        super(message);
    }
}
