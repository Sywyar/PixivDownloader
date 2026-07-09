package top.sywyar.pixivdownload.core.gallery.model.projection;

import top.sywyar.pixivdownload.core.gallery.model.identity.GalleryProjectionKey;
import top.sywyar.pixivdownload.core.gallery.model.media.GalleryMediaKind;
import top.sywyar.pixivdownload.core.gallery.model.work.GalleryActor;
import top.sywyar.pixivdownload.core.gallery.model.work.GalleryAiStatus;
import top.sywyar.pixivdownload.core.gallery.model.work.GalleryContentRating;
import top.sywyar.pixivdownload.core.gallery.model.work.GalleryTag;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Lightweight list projection. It is not the unique type or complete detail of a work. */
public record GalleryProjection(
        GalleryProjectionKey key,
        String title,
        String summary,
        String thumbnailUrl,
        GalleryActor author,
        List<GalleryTag> tags,
        Instant createdAt,
        Instant downloadedAt,
        Instant updatedAt,
        Set<GalleryMediaKind> containedMediaKinds,
        GalleryContentRating contentRating,
        GalleryAiStatus aiStatus,
        String preferredMediaId,
        Map<String, String> attributes
) {

    public GalleryProjection {
        Objects.requireNonNull(key, "key");
        title = blankToNull(title);
        summary = blankToNull(summary);
        thumbnailUrl = blankToNull(thumbnailUrl);
        tags = tags == null ? List.of() : List.copyOf(tags);
        containedMediaKinds = containedMediaKinds == null ? Set.of() : Set.copyOf(containedMediaKinds);
        contentRating = contentRating == null ? GalleryContentRating.UNKNOWN : contentRating;
        aiStatus = aiStatus == null ? GalleryAiStatus.UNKNOWN : aiStatus;
        preferredMediaId = blankToNull(preferredMediaId);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
