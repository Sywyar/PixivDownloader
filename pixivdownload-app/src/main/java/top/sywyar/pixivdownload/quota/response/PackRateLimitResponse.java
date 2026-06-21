package top.sywyar.pixivdownload.quota.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class PackRateLimitResponse {
    private final String error;
    private final int maxPacks;
    private final int windowMinutes;
}
