package top.sywyar.pixivdownload.setup.guest.dto;

import top.sywyar.pixivdownload.plugin.api.web.HttpApiNullable;
import lombok.Builder;

import java.util.List;

@Builder
public record InviteDetail(
        long id,
        String code,
        String url,
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
        long createdTime,
        List<TagBrief> tags,
        List<AuthorBrief> authors,
        List<TagBrief> novelTags,
        List<AuthorBrief> novelAuthors,
        boolean collectionUnrestricted,
        List<Long> collectionIds,
        boolean collectionRestrictsWorks) {

    public record TagBrief(long tagId, String name, @HttpApiNullable String translatedName) {}

    public record AuthorBrief(long authorId, String name) {}
}
