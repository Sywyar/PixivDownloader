package top.sywyar.pixivdownload.download;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

@Getter
public class DownloadProgressEvent extends ApplicationEvent {
    private final Long artworkId;
    private final DownloadStatus downloadStatus;
    /** 该作品下载任务所属用户的 UUID。null 表示来源未知（旧调用点或非多人模式触发的事件）。 */
    private final String userUuid;

    public DownloadProgressEvent(Object source, Long artworkId) {
        this(source, artworkId, null, null);
    }

    public DownloadProgressEvent(Object source, Long artworkId, DownloadStatus downloadStatus) {
        this(source, artworkId, downloadStatus, null);
    }

    public DownloadProgressEvent(Object source, Long artworkId, DownloadStatus downloadStatus, String userUuid) {
        super(source);
        this.artworkId = artworkId;
        this.downloadStatus = downloadStatus;
        this.userUuid = userUuid;
    }

}