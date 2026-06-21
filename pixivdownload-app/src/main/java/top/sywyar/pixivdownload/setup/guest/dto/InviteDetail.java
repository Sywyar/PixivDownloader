package top.sywyar.pixivdownload.setup.guest.dto;

import lombok.Builder;

import java.util.List;

@Builder
public record InviteDetail(
        long id,
        String code,
        String url,
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
        long createdTime,
        List<TagBrief> tags,
        List<AuthorBrief> authors,
        List<TagBrief> novelTags,
        List<AuthorBrief> novelAuthors) {

    public record TagBrief(long tagId, String name, String translatedName) {}

    public record AuthorBrief(long authorId, String name) {}
}
