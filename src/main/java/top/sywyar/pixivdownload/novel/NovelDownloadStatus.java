package top.sywyar.pixivdownload.novel;

import lombok.Data;
import top.sywyar.pixivdownload.download.DownloadActionResult;

import java.time.LocalDateTime;

@Data
public class NovelDownloadStatus {
    private Long novelId;
    private String title;
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
        this.novelId = novelId;
        this.title = title;
        this.format = format;
        this.stage = "pending";
        this.startTime = LocalDateTime.now();
    }
}
