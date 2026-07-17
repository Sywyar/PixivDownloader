package top.sywyar.pixivdownload.gallery.web;

/**
 * gallery 标签目录与作品详情的 HTTP 投影。
 */
public record GalleryTagResponse(
        Long tagId,
        String name,
        String translatedName) {
}
