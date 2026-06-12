package top.sywyar.pixivdownload.plugin.api;

import java.nio.file.Path;

/**
 * 作品的单个本地文件（某一页的原图或缩略图）。
 *
 * @param page      页号（0 起）
 * @param path      文件的本地路径
 * @param extension 小写扩展名（不含点，如 {@code jpg} / {@code png} / {@code webp}），
 *                  缩略图场景同时是图片的写出格式
 */
public record WorkAssetFile(
        int page,
        Path path,
        String extension) {
}
