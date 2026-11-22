package top.sywyar.pixivdownload.collection;

import top.sywyar.pixivdownload.plugin.api.web.HttpApiNullable;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 本地收藏夹。{@code iconExt} 为空表示使用默认心形图标；非空时表示图标文件的扩展名
 * （png/jpg/jpeg/webp），实际文件存在 {@code data/collection_icons/{id}.{ext}}。
 */
public record Collection(
        @Schema(example = "42") long id,
        @Schema(example = "Reading list") String name,
        @Schema(example = "png") @HttpApiNullable String iconExt,
        @HttpApiNullable String downloadRoot,
        @Schema(example = "2") int sortOrder,
        @Schema(example = "1748736000000") long createdTime,
        @Schema(example = "12") int artworkCount,
        @Schema(example = "3") int novelCount
) {
}
