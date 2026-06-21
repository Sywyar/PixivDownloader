package top.sywyar.pixivdownload.quota.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class TriggerPackResponse {
    private final String archiveToken;
    private final long archiveExpireSeconds;
}
