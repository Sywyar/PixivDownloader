package top.sywyar.pixivdownload.core.gallery.model.media;

import top.sywyar.pixivdownload.core.gallery.model.identity.GalleryMediaKey;

import java.util.Map;
import java.util.Objects;

/**
 * One resource locator in the neutral media set of a gallery work.
 *
 * <p>This contract carries display-safe locators and metadata only. Source-owned bodies,
 * translations and persistence details stay behind the owning source endpoint.</p>
 */
public record GalleryMediaAsset(
        GalleryMediaKey key,
        GalleryMediaKind kind,
        String url,
        String thumbnailUrl,
        String mimeType,
        Map<String, String> attributes
) {

    public GalleryMediaAsset {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(kind, "kind");
        url = blankToNull(url);
        thumbnailUrl = blankToNull(thumbnailUrl);
        mimeType = blankToNull(mimeType);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
