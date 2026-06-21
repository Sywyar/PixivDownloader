package top.sywyar.pixivdownload.setup.guest.dto;

import lombok.Builder;

@Builder
public record InviteSummary(
        long id,
        String code,
        String name,
        Long expireTime,
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
        Long firstUsedTime,
        Long lastUsedTime,
        long createdTime) {
}
