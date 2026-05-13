package top.sywyar.pixivdownload.novel;

import lombok.Data;
import top.sywyar.pixivdownload.download.DownloadActionResult;

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
    private boolean completed;
    private boolean failed;
    private String errorMessage;
    private DownloadActionResult bookmarkResult;
    private DownloadActionResult collectionResult;
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
