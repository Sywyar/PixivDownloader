package top.sywyar.pixivdownload.douyin.model;

import java.net.URI;

public record DouyinMedia(
        String id,
        DouyinMediaType type,
        URI url,
        String fileNameStem,
        String extension,
        Long sizeBytes,
        String contentType
) {

    public DouyinMedia {
        if (type == null) {
            type = DouyinMediaType.VIDEO;
        }
        extension = normalizeExtension(extension, type);
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
