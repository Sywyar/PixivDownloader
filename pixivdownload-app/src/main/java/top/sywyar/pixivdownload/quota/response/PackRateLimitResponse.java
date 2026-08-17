package top.sywyar.pixivdownload.quota.response;

import top.sywyar.pixivdownload.plugin.api.web.ApiErrorResponse;

public record PackRateLimitResponse(String code, String error, int maxPacks, int windowMinutes)
        implements ApiErrorResponse {

    public PackRateLimitResponse(String error, int maxPacks, int windowMinutes) {
        this("archive.pack.rate-limited", error, maxPacks, windowMinutes);
    }
}
