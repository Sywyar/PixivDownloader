package top.sywyar.pixivdownload.quota.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class ProxyRateLimitResponse {
    private final String error;
    private final int maxRequests;
    private final int windowHours;
}
