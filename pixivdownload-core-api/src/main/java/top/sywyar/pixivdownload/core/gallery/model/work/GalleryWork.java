package top.sywyar.pixivdownload.core.gallery.model.work;

import top.sywyar.pixivdownload.core.gallery.model.identity.GalleryWorkKey;
import top.sywyar.pixivdownload.core.gallery.model.media.GalleryMediaAsset;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Complete neutral work detail shared by image, video and novel projections. */
public record GalleryWork(
        GalleryWorkKey key,
        String title,
        String description,
        GalleryActor author,
        List<GalleryTag> tags,
        Instant createdAt,
        Instant downloadedAt,
        Instant updatedAt,
        GalleryContentRating contentRating,
        GalleryAiStatus aiStatus,
        List<GalleryMediaAsset> media,
        Map<String, String> attributes
) {

    public GalleryWork {
        Objects.requireNonNull(key, "key");
        title = blankToNull(title);
        description = blankToNull(description);
        tags = tags == null ? List.of() : List.copyOf(tags);
        contentRating = contentRating == null ? GalleryContentRating.UNKNOWN : contentRating;
        aiStatus = aiStatus == null ? GalleryAiStatus.UNKNOWN : aiStatus;
        media = media == null ? List.of() : List.copyOf(media);
        if (media.stream().anyMatch(asset -> !key.equals(asset.key().workKey()))) {
            throw new IllegalArgumentException("media work key must match gallery work key");
        }
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
