package top.sywyar.pixivdownload.gallery.web;

import top.sywyar.pixivdownload.plugin.api.web.HttpApiNullable;

/**
 * gallery 标签目录与作品详情的 HTTP 投影。
 */
public record GalleryTagResponse(
        Long tagId,
        String name,
        @HttpApiNullable String translatedName) {
}
