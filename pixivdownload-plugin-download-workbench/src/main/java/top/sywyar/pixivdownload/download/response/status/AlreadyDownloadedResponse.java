package top.sywyar.pixivdownload.download.response.status;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class AlreadyDownloadedResponse {
    private final boolean success;
    private final boolean alreadyDownloaded;
    private final String message;
}
