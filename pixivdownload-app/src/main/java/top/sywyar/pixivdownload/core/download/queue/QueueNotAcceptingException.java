package top.sywyar.pixivdownload.core.download.queue;

import org.springframework.http.HttpStatus;
import top.sywyar.pixivdownload.i18n.LocalizedException;

/** 下载队列已经进入清退，因而不能再接收新任务。 */
public final class QueueNotAcceptingException extends LocalizedException {

    private final String queueType;

    public QueueNotAcceptingException(String queueType) {
        super(HttpStatus.SERVICE_UNAVAILABLE, "plugin.unavailable.quiesced", null);
        this.queueType = queueType;
    }

    public String queueType() {
        return queueType;
    }
}
