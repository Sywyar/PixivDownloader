package top.sywyar.pixivdownload.pixivcontent;

import java.time.LocalDate;
import java.time.Instant;
import java.util.List;
import java.util.function.LongPredicate;
import top.sywyar.pixivdownload.plugin.api.web.HttpApiNullable;

/** Public JSON returned by the Pixiv content routes. */
public final class ContentModels {
    private ContentModels() {}

    public enum SearchMode { s_tag, s_tag_full, s_tc }
    public enum SeriesType { manga, novel }
    public enum RankingContent { all, illust, manga, ugoira }

    public enum ArtworkSearchType {
        all("artworks", "all", "illustManga"), illustration("illustrations", "illust", "illust"),
        manga("manga", "manga", "manga"), ugoira("illustrations", "ugoira", "illust");

        final String path, pixivType, results;

        ArtworkSearchType(String path, String pixivType, String results) {
            this.path = path;
            this.pixivType = pixivType;
            this.results = results;
        }
    }

    /** A Pixiv artwork and the same-origin path for its first preview. */
    public record Card(long id, String title, long userId, String userName, String type,
                       int pageCount, String previewPath, @HttpApiNullable Integer xRestrict,
                       @HttpApiNullable Integer aiType, @HttpApiNullable Instant createdAt,
                       @HttpApiNullable Instant uploadedAt) {}

    /** One result page. The total counts upstream results before visibility filtering. */
    public record ArtworkList(int page, int total, boolean hasNext, List<Card> items) {
        ArtworkList filter(LongPredicate visible) {
            return new ArtworkList(page, total, hasNext, items.stream().filter(item -> visible.test(item.id())).toList());
        }
    }

    /** The upstream rank is retained when some entries are hidden. */
    public record RankedArtwork(int rank, Card artwork) {}

    /** The actual published date is returned even if the caller omitted the date. */
    public record Ranking(String mode, RankingContent content, LocalDate rankingDate, int page, boolean hasNext, List<RankedArtwork> items) {
        Ranking filter(LongPredicate visible) {
            return new Ranking(mode, content, rankingDate, page, hasNext,
                    items.stream().filter(item -> visible.test(item.artwork().id())).toList());
        }
    }

    /** The two same-origin resources address one zero-based page in a work. */
    public record ImagePage(int pageIndex, String previewPath, String originalPath,
                            @HttpApiNullable Integer width, @HttpApiNullable Integer height) {}

    /** Membership in an illustration or manga series. */
    public record SeriesMembership(long id, String title, int order) {}

    /** Artwork metadata with the pages readers can retrieve directly. */
    public record Artwork(long id, String title, long userId, String userName, String type, int pageCount,
                          List<String> tags, List<ImagePage> pages, @HttpApiNullable SeriesMembership series,
                          @HttpApiNullable String animationPath, @HttpApiNullable Integer xRestrict,
                          @HttpApiNullable Integer aiType, @HttpApiNullable Instant createdAt,
                          @HttpApiNullable Instant uploadedAt, @HttpApiNullable String description,
                          @HttpApiNullable Integer bookmarkCount) {}

    /** Public artist profile; the avatar URL belongs to Pixiv's CDN. */
    public record User(long id, String name, String description, @HttpApiNullable String avatarUrl,
                       @HttpApiNullable String avatarPath) {
        User(long id, String name, String description, String avatarUrl) {
            this(id, name, description, avatarUrl, avatarUrl == null ? null : PixivContentPlugin.API + "/users/" + id + "/avatar");
        }
    }

    public record RelatedArtworks(List<Card> items, @HttpApiNullable String nextCursor) {}
    public record RelatedNovels(List<NovelContentModels.NovelCard> items, @HttpApiNullable String nextCursor) {}
    public record UserAndTagSearch(PersonalContentModels.FollowingPage users, TagPage tags) {}
    public record TagPage(int page, int total, boolean hasNext, List<TagSuggestion> items) {}
    public record BookmarkTag(String tag, int count) {}
    public record BookmarkTags(List<BookmarkTag> items) {}
    public record SeriesLink(long id, String detailPath) {}
    public record Watchlist(SeriesType type, int page, int total, boolean hasNext, List<SeriesLink> items) {}
    public record WorkFeed(List<PersonalContentModels.PersonalCard> items, @HttpApiNullable Long nextCursor) {}
    public record GroupedNovel(long id, String type, String title, long userId, String userName, String detailPath) {}
    public record GroupedNovels(int page, int total, boolean hasNext, List<GroupedNovel> items) {}
    public record SeriesSummary(long id, String title, @HttpApiNullable String coverUrl, String detailPath) {}
    public record UserSeries(SeriesType type, int page, int total, boolean hasNext, List<SeriesSummary> items) {}
    public record TagSuggestion(String name, @HttpApiNullable String translation) {}
    public record TagSuggestions(List<TagSuggestion> items) {}
    public record Comment(long id, @HttpApiNullable Long userId, @HttpApiNullable String userName,
                          @HttpApiNullable String text, @HttpApiNullable Long stampId, String commentDate, boolean hasReplies) {}
    public record Comments(int page, boolean hasNext, List<Comment> items) {}

    /** Chapter number assigned by the series author. */
    public record SeriesEntry(int order, Card artwork) {}

    /** One page of chapters, in Pixiv's series display order (newest chapter first). */
    public record Series(long id, String title, long userId, String description, int page,
                         int total, boolean hasNext, List<SeriesEntry> items) {
        Series filter(LongPredicate visible) {
            return new Series(id, title, userId, description, page, total, hasNext,
                    items.stream().filter(item -> visible.test(item.artwork().id())).toList());
        }
    }
}
