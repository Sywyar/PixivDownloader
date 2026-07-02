package top.sywyar.pixivdownload.novel.response;

public record NovelQuotaExceededResponse(
        boolean quotaExceeded,
        String message,
        String archiveToken,
        long archiveExpireSeconds,
        int artworksUsed,
        int maxArtworks,
        long resetSeconds
) {
}
