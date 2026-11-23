package top.sywyar.pixivdownload.gallery.web;

import top.sywyar.pixivdownload.plugin.api.web.HttpApiNullable;

/**
 * gallery 标签筛选目录的 HTTP 投影。
 */
public record GalleryTagOptionResponse(
        long tagId,
        String name,
        @HttpApiNullable String translatedName,
        int artworkCount) {
}
