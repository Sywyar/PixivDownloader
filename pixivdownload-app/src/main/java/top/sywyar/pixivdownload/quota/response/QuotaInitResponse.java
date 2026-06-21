package top.sywyar.pixivdownload.quota.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Getter;
import top.sywyar.pixivdownload.quota.UserQuotaService;

@Getter
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class QuotaInitResponse {
    private final boolean enabled;
    private final boolean adminMode;
    private final String uuid;
    private final Integer artworksUsed;
    private final Integer maxArtworks;
    private final Long resetSeconds;
    private final UserQuotaService.ArchiveInfo archive;
}
