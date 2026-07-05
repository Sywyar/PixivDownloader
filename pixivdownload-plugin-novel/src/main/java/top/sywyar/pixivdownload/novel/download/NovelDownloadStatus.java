package top.sywyar.pixivdownload.novel.download;

import lombok.Data;
import top.sywyar.pixivdownload.core.work.WorkActionResult;

import java.time.LocalDateTime;

@Data
public class NovelDownloadStatus {
    private Long novelId;
    private String title;
    private String ownerUuid;
    private String format;
    private String stage;
    private String folderName;
    private String downloadPath;
    /** 内嵌图片总数（本次需下载的占位符数量）；无内嵌图时为 0。 */
    private int embeddedTotal;
    /** 已处理的内嵌图片数量（无论成功失败都计入，用于进度展示）。 */
    private int embeddedDone;
    /** 封面图片总字节数（来自上游 Content-Length）；未知时为 0。 */
    private long coverTotalBytes;
    /** 封面图片已下载字节数，用于流式进度条。 */
    private long coverDownloadedBytes;
    private boolean completed;
    private boolean failed;
    private boolean cancelled;
    private String errorMessage;
    private WorkActionResult bookmarkResult;
    private WorkActionResult collectionResult;
    private LocalDateTime startTime;
    private LocalDateTime endTime;

    public NovelDownloadStatus(Long novelId, String title, String format) {
        this(novelId, title, format, null);
    }

    public NovelDownloadStatus(Long novelId, String title, String format, String ownerUuid) {
        this.novelId = novelId;
        this.title = title;
        this.ownerUuid = ownerUuid;
        this.format = format;
        this.stage = "pending";
        this.startTime = LocalDateTime.now();
    }
}
