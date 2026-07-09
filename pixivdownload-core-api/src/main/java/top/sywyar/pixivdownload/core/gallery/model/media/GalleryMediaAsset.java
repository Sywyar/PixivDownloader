package top.sywyar.pixivdownload.core.gallery.model.media;

import top.sywyar.pixivdownload.core.gallery.model.identity.GalleryMediaKey;

import java.util.Map;
import java.util.Objects;

/** One concrete asset in the complete media set of a gallery work. */
public record GalleryMediaAsset(
        GalleryMediaKey key,
        GalleryMediaKind kind,
        String url,
        String thumbnailUrl,
        String mimeType,
        String content,
        Map<String, String> attributes
) {

    public GalleryMediaAsset {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(kind, "kind");
        url = blankToNull(url);
        thumbnailUrl = blankToNull(thumbnailUrl);
        mimeType = blankToNull(mimeType);
        content = blankToNull(content);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
