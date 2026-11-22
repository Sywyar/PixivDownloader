package top.sywyar.pixivdownload.setup.guest.dto;

import top.sywyar.pixivdownload.plugin.api.web.HttpApiNullable;
import lombok.Builder;

@Builder
public record InviteSummary(
        long id,
        String code,
        String name,
        @HttpApiNullable Long expireTime,
        boolean allowSfw,
        boolean allowR18,
        boolean allowR18g,
        boolean tagUnrestricted,
        boolean authorUnrestricted,
        boolean novelTagUnrestricted,
        boolean novelAuthorUnrestricted,
        boolean paused,
        boolean used,
        long totalRequestCount,
        @HttpApiNullable Long firstUsedTime,
        @HttpApiNullable Long lastUsedTime,
        long createdTime) {
}
