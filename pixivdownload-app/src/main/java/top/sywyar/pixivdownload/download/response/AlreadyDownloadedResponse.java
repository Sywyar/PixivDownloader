package top.sywyar.pixivdownload.download.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class AlreadyDownloadedResponse {
    private final boolean success;
    private final boolean alreadyDownloaded;
    private final String message;
}
