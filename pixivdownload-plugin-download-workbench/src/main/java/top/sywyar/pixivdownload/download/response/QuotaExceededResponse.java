package top.sywyar.pixivdownload.download.response;

import lombok.Getter;
import top.sywyar.pixivdownload.plugin.api.web.ApiErrorResponse;

@Getter
public class QuotaExceededResponse implements ApiErrorResponse {
    private final String code = "download.quota.exceeded";
    private final boolean quotaExceeded;
    private final String error;
    private final String archiveToken;
    private final long archiveExpireSeconds;
    private final int artworksUsed;
    private final int maxArtworks;
    private final long resetSeconds;

    public QuotaExceededResponse(boolean quotaExceeded, String error, String archiveToken,
                                 long archiveExpireSeconds, int artworksUsed, int maxArtworks,
                                 long resetSeconds) {
        this.quotaExceeded = quotaExceeded;
        this.error = error;
        this.archiveToken = archiveToken;
        this.archiveExpireSeconds = archiveExpireSeconds;
        this.artworksUsed = artworksUsed;
        this.maxArtworks = maxArtworks;
        this.resetSeconds = resetSeconds;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String error() {
        return error;
    }
}
