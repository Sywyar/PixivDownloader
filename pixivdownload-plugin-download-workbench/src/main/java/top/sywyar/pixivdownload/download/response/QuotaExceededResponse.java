package top.sywyar.pixivdownload.download.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class QuotaExceededResponse {
    private final boolean quotaExceeded;
    private final String message;
    private final String archiveToken;
    private final long archiveExpireSeconds;
    private final int artworksUsed;
    private final int maxArtworks;
    private final long resetSeconds;
}
