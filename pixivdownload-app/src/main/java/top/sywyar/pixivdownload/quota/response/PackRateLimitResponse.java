package top.sywyar.pixivdownload.quota.response;

import io.swagger.v3.oas.annotations.media.Schema;
import top.sywyar.pixivdownload.plugin.api.web.ApiErrorResponse;

public record PackRateLimitResponse(@Schema(allowableValues = "archive.pack.rate-limited") String code, String error, int maxPacks, int windowMinutes)
        implements ApiErrorResponse {

    public PackRateLimitResponse(String error, int maxPacks, int windowMinutes) {
        this("archive.pack.rate-limited", error, maxPacks, windowMinutes);
    }
}
