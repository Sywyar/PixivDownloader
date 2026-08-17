package top.sywyar.pixivdownload.novel.response;

import top.sywyar.pixivdownload.plugin.api.web.ApiErrorResponse;

public record NovelQuotaExceededResponse(
        String code,
        boolean quotaExceeded,
        String error,
        String archiveToken,
        long archiveExpireSeconds,
        int artworksUsed,
        int maxArtworks,
        long resetSeconds
) implements ApiErrorResponse {

    public NovelQuotaExceededResponse(boolean quotaExceeded, String error, String archiveToken,
                                      long archiveExpireSeconds, int artworksUsed, int maxArtworks,
                                      long resetSeconds) {
        this("download.quota.exceeded", quotaExceeded, error, archiveToken, archiveExpireSeconds,
                artworksUsed, maxArtworks, resetSeconds);
    }
}
