package top.sywyar.pixivdownload.core.work.model;

import java.nio.file.Path;

/**
 * 作品的单个本地文件（某一页的原图或缩略图）。
 *
 * @param page      从 0 开始的页标识；拥有稳定页号时保留该页号，否则为当前快照的临时索引，
 *                  不得持久化或用于关联两次独立快照
 * @param path      文件的本地路径
 * @param extension 小写扩展名（不含点，如 {@code jpg} / {@code png} / {@code webp}），
 *                  缩略图场景同时是图片的写出格式
 */
public record WorkAssetFile(
        int page,
        Path path,
        String extension) {
}
