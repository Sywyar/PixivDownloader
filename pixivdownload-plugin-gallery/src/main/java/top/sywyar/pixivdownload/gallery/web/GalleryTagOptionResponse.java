package top.sywyar.pixivdownload.gallery.web;

/**
 * gallery 标签筛选目录的 HTTP 投影。
 */
public record GalleryTagOptionResponse(
        long tagId,
        String name,
        String translatedName,
        int artworkCount) {
}
