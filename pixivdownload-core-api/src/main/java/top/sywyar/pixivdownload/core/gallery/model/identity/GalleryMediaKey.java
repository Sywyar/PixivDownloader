package top.sywyar.pixivdownload.core.gallery.model.identity;

import java.util.Objects;

/** Stable source-owned identity of a media asset belonging to a work. */
public record GalleryMediaKey(GalleryWorkKey workKey, String mediaId) {

    public GalleryMediaKey {
        Objects.requireNonNull(workKey, "workKey");
        if (mediaId == null || mediaId.isBlank()) {
            throw new IllegalArgumentException("mediaId must not be blank");
        }
        mediaId = mediaId.trim();
    }
}
