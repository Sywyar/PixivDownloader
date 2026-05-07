package top.sywyar.pixivdownload.setup.guest;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.download.db.ArtworkRecord;
import top.sywyar.pixivdownload.download.db.PixivDatabase;
import top.sywyar.pixivdownload.download.db.TagDto;
import top.sywyar.pixivdownload.i18n.LocalizedException;
import top.sywyar.pixivdownload.novel.db.NovelDatabase;
import top.sywyar.pixivdownload.novel.db.NovelRecord;

import java.util.List;

/**
 * 访客邀请的越界守卫。在单作品访问端点入口处调用 {@link #requireVisible(HttpServletRequest, long)}，
 * 越界（年龄分级超出 / 标签作者均不在白名单）抛 403。
 *
 * <p>请求未携带 GuestInviteSession 时直接放行（管理员或非访客访问不受此守卫影响）。
 */
@Component
@RequiredArgsConstructor
public class GuestAccessGuard {

    private final PixivDatabase pixivDatabase;
    private final NovelDatabase novelDatabase;

    /**
     * 抽出当前请求挂载的访客邀请会话；可能为 {@code null}。
     */
    public static GuestInviteSession extractSession(HttpServletRequest request) {
        if (request == null) return null;
        Object attr = request.getAttribute(GuestInviteSession.REQUEST_ATTR);
        return attr instanceof GuestInviteSession s ? s : null;
    }

    /**
     * 若当前请求是访客身份，校验作品是否在其可见范围内；越界抛 403。
     */
    public void requireVisible(HttpServletRequest request, long artworkId) {
        GuestInviteSession session = extractSession(request);
        if (session == null) return; // 非访客身份直接放行
        if (!isVisibleToGuest(artworkId, session)) {
            throw new LocalizedException(HttpStatus.FORBIDDEN,
                    "guest.invite.forbidden",
                    "该作品不在你的可见范围内");
        }
    }

    /**
     * 单作品可见性判定：
     * 1) 年龄分级必须在允许集合内；
     * 2) OR 语义白名单：作品任一标签命中 {@code tagIds} 或作者在 {@code authorIds} 即可见。
     *    某维度 {@code unrestricted=true} 视为无限制。
     */
    public boolean isVisibleToGuest(long artworkId, GuestInviteSession session) {
        ArtworkRecord rec = pixivDatabase.getArtwork(artworkId);
        if (rec == null) return false;
        if (!matchesAgeRating(rec.xRestrict(), session)) return false;
        return matchesWhitelist(rec, session);
    }

    /**
     * 若当前请求是访客身份，校验小说是否在其可见范围内；越界抛 403。
     */
    public void requireNovelVisible(HttpServletRequest request, long novelId) {
        GuestInviteSession session = extractSession(request);
        if (session == null) return;
        if (!isNovelVisibleToGuest(novelId, session)) {
            throw new LocalizedException(HttpStatus.FORBIDDEN,
                    "guest.invite.forbidden",
                    "该小说不在你的可见范围内");
        }
    }

    /**
     * 单篇小说的可见性判定。规则与 {@link #isVisibleToGuest} 相同，但读取 {@code novels} 表与
     * {@code novel_tags} 表（标签 id 与插画共享 {@code tags} 池，所以 OR 语义与白名单可直接复用）。
     */
    public boolean isNovelVisibleToGuest(long novelId, GuestInviteSession session) {
        NovelRecord rec = novelDatabase.getNovel(novelId);
        if (rec == null) return false;
        if (!matchesAgeRating(rec.xRestrict(), session)) return false;
        return matchesNovelWhitelist(rec, session);
    }

    private boolean matchesNovelWhitelist(NovelRecord rec, GuestInviteSession session) {
        List<TagDto> tags = novelDatabase.getNovelTags(rec.novelId());

        if (!session.tagUnrestricted()) {
            if (tags != null && !tags.isEmpty()) {
                for (TagDto tag : tags) {
                    Long id = tag.getTagId();
                    if (id != null && !session.tagIds().contains(id)) {
                        return false;
                    }
                }
            }
        }
        if (!session.authorUnrestricted()) {
            if (rec.authorId() != null && !session.authorIds().contains(rec.authorId())) {
                return false;
            }
        }

        boolean tagPass = session.tagUnrestricted() || hasTagHit(tags, session.tagIds());
        if (tagPass) return true;
        return session.authorUnrestricted()
                || (rec.authorId() != null && session.authorIds().contains(rec.authorId()));
    }

    private boolean matchesAgeRating(Integer xRestrict, GuestInviteSession session) {
        int rating = xRestrict == null ? 0 : xRestrict;
        return switch (rating) {
            case 0 -> session.allowSfw();
            case 1 -> session.allowR18();
            case 2 -> session.allowR18g();
            default -> session.allowR18(); // 兼容未知值，按 R18 处理
        };
    }

    /**
     * 判定流程与 {@code GalleryRepository.appendVisibilityClauses} 一致：
     * (1) 不可见维度优先排除：作品任一标签/作者命中不可见集合即返回 false；
     * (2) OR 正向匹配：通过排除后，至少一个维度命中可见白名单即可。
     */
    private boolean matchesWhitelist(ArtworkRecord rec, GuestInviteSession session) {
        List<TagDto> tags = pixivDatabase.getArtworkTags(rec.artworkId());

        // (1) 不可见排除
        if (!session.tagUnrestricted()) {
            if (tags != null && !tags.isEmpty()) {
                for (TagDto tag : tags) {
                    Long id = tag.getTagId();
                    if (id != null && !session.tagIds().contains(id)) {
                        return false; // 命中不可见标签
                    }
                }
            }
        }
        if (!session.authorUnrestricted()) {
            if (rec.authorId() != null && !session.authorIds().contains(rec.authorId())) {
                return false; // 命中不可见作者
            }
        }

        // (2) OR 正向匹配
        boolean tagPass = session.tagUnrestricted() || hasTagHit(tags, session.tagIds());
        if (tagPass) return true;
        return session.authorUnrestricted()
                || (rec.authorId() != null && session.authorIds().contains(rec.authorId()));
    }

    private boolean hasTagHit(List<TagDto> tags, java.util.Set<Long> whitelist) {
        if (whitelist == null || whitelist.isEmpty() || tags == null) return false;
        for (TagDto tag : tags) {
            if (tag.getTagId() != null && whitelist.contains(tag.getTagId())) return true;
        }
        return false;
    }
}
