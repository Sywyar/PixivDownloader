package top.sywyar.pixivdownload.douyin.model;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.List;

public record DouyinMedia(
        String id,
        DouyinMediaType type,
        URI url,
        String fileNameStem,
        String extension,
        Long sizeBytes,
        String contentType,
        List<URI> fallbackUrls
) {

    public DouyinMedia {
        if (type == null) {
            type = DouyinMediaType.VIDEO;
        }
        extension = normalizeExtension(extension, type);
        LinkedHashSet<URI> alternatives = new LinkedHashSet<>();
        if (fallbackUrls != null) {
            fallbackUrls.stream()
                    .filter(candidate -> candidate != null && !candidate.equals(url))
                    .forEach(alternatives::add);
        }
        fallbackUrls = List.copyOf(alternatives);
    }

    public DouyinMedia(String id,
                       DouyinMediaType type,
                       URI url,
                       String fileNameStem,
                       String extension,
                       Long sizeBytes,
                       String contentType) {
        this(id, type, url, fileNameStem, extension, sizeBytes, contentType, List.of());
    }

    private static String normalizeExtension(String extension, DouyinMediaType type) {
        String value = extension == null ? "" : extension.trim().toLowerCase(java.util.Locale.ROOT);
        if (value.startsWith(".")) {
            value = value.substring(1);
        }
        if (!value.matches("[a-z0-9]{1,8}")) {
            return type == DouyinMediaType.IMAGE ? "jpg" : "mp4";
        }
        return value;
    }
}
