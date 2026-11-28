package top.sywyar.pixivdownload.pixivcontent;

import java.time.Instant;
import java.util.List;
import java.util.function.LongPredicate;
import top.sywyar.pixivdownload.plugin.api.web.HttpApiNullable;

/** A page from the selected Pixiv account's bookmarks or following feed. */
public final class PersonalContentModels {
    private PersonalContentModels() {}

    /** Novels have a character count when reported by Pixiv. Artworks have pages and a preview. */
    public record PersonalCard(long id, String title, long userId, String userName, String type,
                               @HttpApiNullable Integer pageCount, @HttpApiNullable Integer characterCount,
                               String detailPath, @HttpApiNullable String previewPath,
                               @HttpApiNullable String coverPath, @HttpApiNullable String description,
                               List<String> tags, @HttpApiNullable Instant createdAt, @HttpApiNullable Instant uploadedAt,
                               @HttpApiNullable Integer bookmarkCount, @HttpApiNullable Integer xRestrict,
                               @HttpApiNullable Integer aiType) {}

    /** The upstream bookmark total precedes visibility filtering. Pixiv supplies no feed total. */
    public record PersonalPage(String type, int page, @HttpApiNullable Integer total, boolean hasNext,
                               List<PersonalCard> items) {
        PersonalPage filter(LongPredicate visible) {
            return new PersonalPage(type, page, total, hasNext,
                    items.stream().filter(item -> visible.test(item.id())).toList());
        }

        PersonalPage filter(LongPredicate visibleArtwork, LongPredicate visibleNovel) {
            return new PersonalPage(type, page, total, hasNext, items.stream()
                    .filter(item -> ("novel".equals(item.type()) ? visibleNovel : visibleArtwork).test(item.id()))
                    .toList());
        }
    }

    /** Accounts followed by the selected Pixiv user. */
    public record FollowingPage(int page, int total, boolean hasNext, List<ContentModels.User> items) {}

    /** A Pixiv collection ID can exceed the range of a 64-bit integer. */
    public record CollectionCard(String id, String title, String description, @HttpApiNullable String coverUrl,
                                 List<String> tags, String worksPath) {}

    /** Pixiv does not identify the source work for a collection cover. */
    public record CollectionPage(int page, int total, boolean hasNext, List<CollectionCard> items) {
        CollectionPage withoutCovers() {
            return new CollectionPage(page, total, hasNext, items.stream().map(item -> new CollectionCard(
                    item.id(), item.title(), item.description(), null, item.tags(), item.worksPath())).toList());
        }
    }
}
