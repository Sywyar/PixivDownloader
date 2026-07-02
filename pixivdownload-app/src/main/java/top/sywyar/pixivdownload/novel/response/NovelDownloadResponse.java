package top.sywyar.pixivdownload.novel.response;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class NovelDownloadResponse {
    private boolean success;
    private String message;
    private String downloadPath;
    private int downloadedCount;
}
