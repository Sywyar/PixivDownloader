package top.sywyar.pixivdownload.pixivcontent;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.function.LongPredicate;
import top.sywyar.pixivdownload.plugin.api.web.HttpApiNullable;

/** Responses for Pixiv novels and their series. */
public final class NovelContentModels {
    private NovelContentModels() {}

    /** A novel in search, bookmarks or a series. The character count can be absent upstream. */
    public record NovelCard(long id, String title, long userId, String userName, @HttpApiNullable Integer characterCount,
                            String detailPath, @HttpApiNullable String coverPath, List<String> tags,
                            @HttpApiNullable String description, @HttpApiNullable Instant createdAt,
                            @HttpApiNullable Instant uploadedAt, @HttpApiNullable Integer bookmarkCount,
                            @HttpApiNullable Integer xRestrict, @HttpApiNullable Integer aiType) {}

    /** One search page; total counts the upstream results before visibility filtering. */
    public record NovelList(int page, int total, boolean hasNext, List<NovelCard> items) {
        NovelList filter(LongPredicate visible) {
            return new NovelList(page, total, hasNext, items.stream().filter(item -> visible.test(item.id())).toList());
        }
    }

    /** The content is Pixiv's original novel text, including newline, page, chapter and embedded-image markers. */
    public record Novel(long id, String title, long userId, String userName, String language, List<String> tags,
                        @HttpApiNullable ContentModels.SeriesMembership series, String contentFormat, String content,
                        @HttpApiNullable String coverPath, List<NovelImage> images,
                        @HttpApiNullable String description, @HttpApiNullable Instant createdAt,
                        @HttpApiNullable Instant uploadedAt, @HttpApiNullable Integer characterCount,
                        @HttpApiNullable Integer bookmarkCount, @HttpApiNullable Integer viewCount,
                        @HttpApiNullable Integer likeCount, @HttpApiNullable Integer commentCount,
                        @HttpApiNullable Integer xRestrict, @HttpApiNullable Integer aiType) {
        Novel filterIllustrations(LongPredicate visible) {
            var allowed = images.stream().filter(image -> !image.kind().equals("artwork") || visible.test(image.id())).toList();
            return new Novel(id, title, userId, userName, language, tags, series, contentFormat, content, coverPath,
                    allowed, description, createdAt, uploadedAt, characterCount, bookmarkCount, viewCount,
                    likeCount, commentCount, xRestrict, aiType);
        }
    }

    /** One distinct image marker in reading order, linked to media Pixiv makes available for this novel. */
    public record NovelImage(String marker, String kind, long id, @HttpApiNullable Integer pageIndex, String imagePath) {}

    public record RankedNovel(int rank, NovelCard novel) {}

    /** A daily ranking or the period ending on rankingDate. Ranks retain their upstream positions. */
    public record NovelRanking(String mode, LocalDate rankingDate, @HttpApiNullable LocalDate periodStart,
                               int page, boolean hasNext, List<RankedNovel> items) {
        NovelRanking filter(LongPredicate visible) {
            return new NovelRanking(mode, rankingDate, periodStart, page, hasNext,
                    items.stream().filter(item -> visible.test(item.novel().id())).toList());
        }
    }

    /** One chapter, with its original order in the series. */
    public record NovelChapter(int order, NovelCard novel) {}

    /** Chapters in ascending reading order. Page numbers start at 1 and each page spans up to 30 chapters. */
    public record NovelSeries(long id, String title, long userId, String userName, String description,
                              int page, int total, boolean hasNext, List<NovelChapter> items) {
        NovelSeries filter(LongPredicate visible) {
            return new NovelSeries(id, title, userId, userName, description, page, total, hasNext,
                    items.stream().filter(item -> visible.test(item.novel().id())).toList());
        }
    }
}
