package top.sywyar.pixivdownload.setup.guest;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.core.db.ArtworkRecord;
import top.sywyar.pixivdownload.core.db.PixivDatabase;
import top.sywyar.pixivdownload.core.db.TagDto;
import top.sywyar.pixivdownload.core.metadata.novel.NovelMetadataRepository;
import top.sywyar.pixivdownload.core.metadata.novel.NovelMetadataRow;
import top.sywyar.pixivdownload.core.work.model.WorkRestriction;
import top.sywyar.pixivdownload.core.work.model.WorkType;
import top.sywyar.pixivdownload.core.work.model.WorkVisibilityScope;
import top.sywyar.pixivdownload.core.work.service.WorkVisibilityService;
import top.sywyar.pixivdownload.core.work.service.WorkVisibilityDeniedException;

import java.util.List;
import java.util.Objects;

/**
 * {@link WorkVisibilityService} 的核心实现：按宿主签发的作用域读取插画 / 小说元数据，统一执行年龄分级、
 * 标签与作者可见性判定。请求与邀请会话解析由 {@link GuestWorkVisibilityScopeFactory} 承担。
 */
@Component
@RequiredArgsConstructor
public class GuestWorkVisibilityService implements WorkVisibilityService {

    private final PixivDatabase pixivDatabase;
    private final NovelMetadataRepository novelMetadataRepository;

    @Override
    public void requireVisible(WorkVisibilityScope scope, WorkType workType, long workId) {
        if (!isVisible(scope, workType, workId)) {
            throw new WorkVisibilityDeniedException(workType, workId);
        }
    }

    @Override
    public boolean isVisible(WorkVisibilityScope scope, WorkType workType, long workId) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(workType, "workType");
        if (!scope.enforceVisibility()) {
            return true;
        }
        WorkRestriction restriction = scope.restrictionFor(workType);
        return switch (workType) {
            case ARTWORK -> isArtworkVisible(workId, restriction);
            case NOVEL -> isNovelVisible(workId, restriction);
        };
    }

    private boolean isArtworkVisible(long workId, WorkRestriction restriction) {
        ArtworkRecord artwork = pixivDatabase.getArtwork(workId);
        return artwork != null
                && matchesAgeRating(artwork.xRestrict(), restriction)
                && matchesWhitelist(artwork.authorId(), pixivDatabase.getArtworkTags(workId), restriction);
    }

    private boolean isNovelVisible(long workId, WorkRestriction restriction) {
        NovelMetadataRow novel = novelMetadataRepository.getNovel(workId);
        return novel != null
                && matchesAgeRating(novel.xRestrict(), restriction)
                && matchesWhitelist(novel.authorId(), novelMetadataRepository.getNovelTags(workId), restriction);
    }

    private boolean matchesAgeRating(Integer xRestrict, WorkRestriction restriction) {
        int rating = xRestrict == null ? 0 : xRestrict;
        int normalized = rating >= 0 && rating <= 2 ? rating : 1;
        return restriction.allowedXRestricts().contains(normalized);
    }

    private boolean matchesWhitelist(
            Long authorId,
            List<TagDto> tags,
            WorkRestriction restriction) {
        if (!restriction.tagUnrestricted() && tags != null) {
            for (TagDto tag : tags) {
                Long tagId = tag.getTagId();
                if (tagId != null && !restriction.tagIds().contains(tagId)) {
                    return false;
                }
            }
        }
        if (!restriction.authorUnrestricted()
                && authorId != null
                && !restriction.authorIds().contains(authorId)) {
            return false;
        }

        boolean tagPass = restriction.tagUnrestricted() || hasTagHit(tags, restriction.tagIds());
        if (tagPass) {
            return true;
        }
        return restriction.authorUnrestricted()
                || (authorId != null && restriction.authorIds().contains(authorId));
    }

    private boolean hasTagHit(List<TagDto> tags, List<Long> whitelist) {
        if (whitelist.isEmpty() || tags == null) {
            return false;
        }
        for (TagDto tag : tags) {
            if (tag.getTagId() != null && whitelist.contains(tag.getTagId())) {
                return true;
            }
        }
        return false;
    }
}
