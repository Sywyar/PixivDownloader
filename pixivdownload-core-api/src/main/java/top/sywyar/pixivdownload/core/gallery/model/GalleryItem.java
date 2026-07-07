package top.sywyar.pixivdownload.core.gallery.model;

import java.util.Map;

public record GalleryItem(
        GalleryWorkRef ref,
        String title,
        String thumbnailUrl,
        String detailUrl,
        Map<String, String> attributes
) {

    public GalleryItem {
        title = blankToNull(title);
        thumbnailUrl = blankToNull(thumbnailUrl);
        detailUrl = blankToNull(detailUrl);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
