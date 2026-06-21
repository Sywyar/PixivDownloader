package top.sywyar.pixivdownload.plugin.api.work.model;

import java.nio.file.Path;

/**
 * 作品的单个本地文件（某一页的原图或缩略图）。
 *
 * @param page      页号（0 起）；插画为 Pixiv 真实页号，小说为枚举快照序号
 *                  （临时编号，不得持久化，见 {@link WorkAssetService} 的小说资产语义）
 * @param path      文件的本地路径
 * @param extension 小写扩展名（不含点，如 {@code jpg} / {@code png} / {@code webp}），
 *                  缩略图场景同时是图片的写出格式
 */
public record WorkAssetFile(
        int page,
        Path path,
        String extension) {
}
